import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TruncatedTextComponent } from './truncated-text.component';

class ObservedSize {
  readonly observe = vi.fn();
  readonly unobserve = vi.fn();
  readonly disconnect = vi.fn();
  constructor(readonly callback: ResizeObserverCallback) {
    observers.push(this);
  }
}
let observers: ObservedSize[] = [];

const bounds = (left: number, top: number, width: number, height: number): DOMRect => ({
  x: left,
  y: top,
  left,
  top,
  width,
  height,
  right: left + width,
  bottom: top + height,
  toJSON: () => ({}),
});

describe('TruncatedTextComponent', () => {
  const fixtures: ComponentFixture<TruncatedTextComponent>[] = [];
  let fonts: EventTarget;
  let originalFonts: PropertyDescriptor | undefined;

  beforeEach(() => {
    observers = [];
    vi.stubGlobal('ResizeObserver', ObservedSize);
    fonts = Object.assign(new EventTarget(), { ready: Promise.resolve() });
    originalFonts = Object.getOwnPropertyDescriptor(document, 'fonts');
    Object.defineProperty(document, 'fonts', { configurable: true, value: fonts });
    TestBed.configureTestingModule({ imports: [TruncatedTextComponent] });
  });

  afterEach(() => {
    for (const fixture of fixtures.splice(0)) fixture.destroy();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    if (originalFonts) Object.defineProperty(document, 'fonts', originalFonts);
    else Reflect.deleteProperty(document, 'fonts');
  });

  async function create(value: string | null, width: number, contentWidth: number) {
    const fixture = TestBed.createComponent(TruncatedTextComponent);
    fixtures.push(fixture);
    fixture.componentRef.setInput('value', value);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const trigger = host.querySelector('.truncated-value') as HTMLElement;
    const preview = host.querySelector('.full-text-preview') as HTMLElement;
    let measuredWidth = width;
    let measuredContent = contentWidth;
    Object.defineProperties(trigger, {
      clientWidth: { configurable: true, get: () => measuredWidth },
      scrollWidth: { configurable: true, get: () => measuredContent },
    });
    Object.defineProperty(preview, 'showPopover', { configurable: true, value: undefined });
    vi.spyOn(trigger, 'getBoundingClientRect').mockReturnValue(bounds(40, 40, width, 20));
    vi.spyOn(preview, 'getBoundingClientRect').mockReturnValue(bounds(0, 0, 304, 180));
    await fixture.whenStable();
    window.dispatchEvent(new Event('resize'));
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(trigger.getAttribute('tabindex')).toBe(contentWidth > width && width > 0 ? '0' : null);
    });
    return {
      fixture,
      host,
      trigger,
      preview,
      geometry: (nextWidth: number, nextContent: number) => {
        measuredWidth = nextWidth;
        measuredContent = nextContent;
      },
    };
  }

  it('does not focus or preview text that fits, regardless of character count', async () => {
    const { trigger, preview } = await create('Long text '.repeat(100), 100, 100);
    expect(trigger.getAttribute('tabindex')).toBeNull();
    expect(trigger.getAttribute('role')).toBeNull();
    trigger.dispatchEvent(new MouseEvent('pointerenter'));
    trigger.dispatchEvent(new FocusEvent('focus'));
    trigger.click();
    expect(preview.hidden).toBe(true);
  });

  it('uses the native top layer for even a short value when geometry shows real truncation', async () => {
    const { trigger, preview, fixture } = await create('A', 100, 101);
    const show = vi.fn();
    const hide = vi.fn();
    Object.defineProperty(preview, 'showPopover', { configurable: true, value: show });
    Object.defineProperty(preview, 'hidePopover', { configurable: true, value: hide });
    trigger.dispatchEvent(new MouseEvent('pointerenter'));
    fixture.detectChanges();
    expect(show).toHaveBeenCalledTimes(1);
    expect(preview.hidden).toBe(false);
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    trigger.dispatchEvent(escape);
    expect(escape.defaultPrevented).toBe(true);
    expect(hide).toHaveBeenCalledTimes(1);
    expect(preview.hidden).toBe(true);
  });

  it('keeps a hover preview open while moving into it and dismisses after leaving it', async () => {
    const { trigger, preview } = await create('Truncated value', 100, 200);
    vi.useFakeTimers();
    trigger.dispatchEvent(new MouseEvent('pointerenter'));
    trigger.dispatchEvent(new MouseEvent('pointerleave'));
    preview.dispatchEvent(new MouseEvent('pointerenter'));
    vi.advanceTimersByTime(250);
    expect(preview.hidden).toBe(false);
    preview.dispatchEvent(new MouseEvent('pointerleave'));
    vi.advanceTimersByTime(250);
    expect(preview.hidden).toBe(true);
  });

  it('supports focus, keyboard and touch-style clicks without letting Escape close the parent popup', async () => {
    const { trigger, preview, fixture } = await create('Complete field value', 100, 200);
    trigger.focus();
    expect(preview.hidden).toBe(false);
    trigger.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
    );
    expect(document.activeElement).toBe(preview);
    const parentEscape = vi.fn();
    document.addEventListener('keydown', parentEscape);
    try {
      preview.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
      );
      fixture.detectChanges();
      await Promise.resolve();
      expect(preview.hidden).toBe(true);
      expect(document.activeElement).toBe(trigger);
      expect(parentEscape).not.toHaveBeenCalled();
      trigger.click();
      expect(preview.hidden).toBe(false);
      document.body.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }));
      expect(preview.hidden).toBe(true);
    } finally {
      document.removeEventListener('keydown', parentEscape);
    }
  });

  it('portals fallback previews inside an active dialog and preserves complete paragraphs as safe text', async () => {
    const value = 'First paragraph.\n\n<script>alert(1)</script> & <b>second paragraph</b>.';
    const { fixture, trigger, preview } = await create(value, 100, 300);
    const dialog = document.createElement('dialog');
    dialog.open = true;
    document.body.appendChild(dialog);
    dialog.appendChild(fixture.nativeElement as HTMLElement);
    try {
      trigger.click();
      expect(preview.parentElement).toBe(dialog);
      expect(preview.querySelector('.preview-content')?.textContent).toBe(value);
      expect(preview.querySelector('script, b')).toBeNull();
      (preview.querySelector('button') as HTMLButtonElement).click();
      expect(preview.hidden).toBe(true);
      expect(dialog.open).toBe(true);
    } finally {
      fixture.destroy();
      dialog.remove();
    }
  });

  it('constrains measured overlay placement and scrolling size to a small viewport', async () => {
    const originalWidth = Object.getOwnPropertyDescriptor(window, 'innerWidth');
    const originalHeight = Object.getOwnPropertyDescriptor(window, 'innerHeight');
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 320 });
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 220 });
    try {
      const { trigger, preview } = await create(
        'Many complete paragraphs.\n\n'.repeat(100),
        100,
        300,
      );
      vi.mocked(trigger.getBoundingClientRect).mockReturnValue(bounds(295, 190, 20, 20));
      trigger.click();
      const left = Number.parseFloat(preview.style.left);
      const top = Number.parseFloat(preview.style.top);
      expect(left).toBeGreaterThanOrEqual(8);
      expect(left + Number.parseFloat(preview.style.width)).toBeLessThanOrEqual(312);
      expect(top).toBeGreaterThanOrEqual(8);
      expect(top + 180).toBeLessThanOrEqual(212);
      expect(Number.parseFloat(preview.style.maxHeight)).toBeLessThanOrEqual(204);
      expect(preview.hidden).toBe(false);
    } finally {
      if (originalWidth) Object.defineProperty(window, 'innerWidth', originalWidth);
      if (originalHeight) Object.defineProperty(window, 'innerHeight', originalHeight);
    }
  });

  it('remeasures font, resize and input changes, and disconnects observers on teardown', async () => {
    const { fixture, trigger, preview, geometry } = await create('Before font load', 100, 100);
    geometry(100, 160);
    fonts.dispatchEvent(new Event('loadingdone'));
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(trigger.getAttribute('tabindex')).toBe('0');
    });
    trigger.click();
    expect(preview.hidden).toBe(false);
    geometry(200, 160);
    observers[0].callback([], observers[0] as unknown as ResizeObserver);
    window.dispatchEvent(new Event('resize'));
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(trigger.getAttribute('tabindex')).toBeNull();
      expect(preview.hidden).toBe(true);
    });
    fixture.componentRef.setInput('value', null);
    fixture.componentRef.setInput('fallback', 'No description');
    fixture.detectChanges();
    expect(trigger.textContent).toBe('No description');
    fixture.destroy();
    expect(observers[0].disconnect).toHaveBeenCalledTimes(1);
    expect(document.querySelector('.full-text-preview:not([hidden])')).toBeNull();
  });
});
