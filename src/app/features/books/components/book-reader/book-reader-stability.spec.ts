import { HttpResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { BooksApiService } from '../../data-access/books-api.service';
import { BookDownloadService } from '../../data-access/book-download.service';
import { PdfReaderService } from '../../data-access/pdf-reader.service';
import type { Book } from '../../models/book.models';
import type { PdfReaderDocument, PdfReaderPage } from '../../models/reader.models';
import { BookReaderComponent } from './book-reader.component';

function deferred() {
  let resolve!: () => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<void>((accept, fail) => {
    resolve = accept;
    reject = fail;
  });
  return { promise, resolve, reject };
}

describe('reader stability and fullscreen controls', () => {
  const book: Book = {
    bookId: 91,
    bookName: 'Charts and large pages',
    bookType: 'Reference',
    authorName: null,
    bookDetails: null,
    totalPages: 4,
    bookUploadId: null,
    authorDetails: null,
    createdAt: null,
    updatedAt: null,
    topicCount: 0,
    topics: [],
  };
  let response: Subject<HttpResponse<Blob>>;
  let fixture: ComponentFixture<BookReaderComponent>;
  let reader: BookReaderComponent;
  let viewportWidth: number;
  let viewportHeight: number;
  let observeResize: ResizeObserverCallback;
  let contexts: WeakMap<HTMLCanvasElement, { drawImage: ReturnType<typeof vi.fn> }>;
  let pages: Map<number, PdfReaderPage>;
  let pending: Map<number, ReturnType<typeof deferred>>;
  let rasterCalls: Array<{ page: number; canvas: HTMLCanvasElement; width: number }>;
  const api = { readBook: vi.fn(), readTopics: vi.fn() };
  const downloads = {
    isBookDownloading: () => false,
    isSelectedTopicsDownloading: () => false,
    isTopicDownloading: () => false,
    isAnyTopicDownloading: () => false,
  };
  const engine = { load: vi.fn() };
  let pdf: PdfReaderDocument;

  beforeEach(() => {
    vi.clearAllMocks();
    viewportWidth = 1000;
    viewportHeight = 700;
    contexts = new WeakMap();
    pages = new Map();
    pending = new Map();
    rasterCalls = [];
    response = new Subject();
    api.readBook.mockReturnValue(response.asObservable());
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(function (
      this: HTMLCanvasElement,
    ) {
      let context = contexts.get(this);
      if (!context) {
        context = { drawImage: vi.fn() };
        contexts.set(this, context);
      }
      return context as unknown as CanvasRenderingContext2D;
    } as unknown as typeof HTMLCanvasElement.prototype.getContext);
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(callback: ResizeObserverCallback) {
          observeResize = callback;
        }
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    );
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value() {
        this.open = true;
      },
    });
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
      configurable: true,
      value() {
        this.open = false;
      },
    });
    for (let number = 1; number <= 4; number++) makePage(number, 600, 800);
    pdf = { pageCount: 4, getPage: vi.fn(async (number) => pages.get(number)!) };
    engine.load.mockReturnValue({ promise: Promise.resolve(pdf), destroy: vi.fn(async () => {}) });
    TestBed.configureTestingModule({
      imports: [BookReaderComponent],
      providers: [
        { provide: BooksApiService, useValue: api },
        { provide: BookDownloadService, useValue: downloads },
        { provide: PdfReaderService, useValue: engine },
      ],
    });
  });

  afterEach(() => {
    fixture?.destroy();
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  function makePage(number: number, width: number, height: number) {
    pages.set(number, {
      width,
      height,
      cleanup: vi.fn(),
      render: vi.fn((canvas, targetWidth) => {
        rasterCalls.push({ page: number, canvas, width: targetWidth });
        canvas.width = Math.ceil(targetWidth);
        canvas.height = Math.ceil((targetWidth * height) / width);
        canvas.style.width = `${targetWidth}px`;
        canvas.style.height = `${(targetWidth * height) / width}px`;
        return {
          promise: pending.get(number)?.promise || Promise.resolve(),
          cancel: vi.fn(),
        };
      }),
    });
  }

  async function flush() {
    for (let index = 0; index < 30; index++) await Promise.resolve();
    fixture.detectChanges();
  }

  async function open() {
    fixture = TestBed.createComponent(BookReaderComponent);
    fixture.componentRef.setInput('request', { mode: 'book', book, topics: [] });
    reader = fixture.componentInstance;
    reader.pageMode.set('single');
    fixture.detectChanges();
    await flush();
    const viewport = fixture.nativeElement.querySelector('.reader-content') as HTMLElement;
    Object.defineProperties(viewport, {
      clientWidth: { configurable: true, get: () => viewportWidth },
      clientHeight: { configurable: true, get: () => viewportHeight },
    });
    response.next(new HttpResponse({ body: new Blob(['%PDF'], { type: 'application/pdf' }) }));
    await flush();
    expect(reader.status()).toBe('ready');
  }

  async function fullPages() {
    await reader.enterFullPage();
    await flush();
    await reader.navigate(1);
    await flush();
    expect(reader.stage()).toBe('pages');
  }

  function resize(width: number, height: number) {
    const viewport = fixture.nativeElement.querySelector('.reader-content') as HTMLElement;
    observeResize(
      [
        {
          target: viewport,
          contentRect: new DOMRect(0, 0, width, height),
          borderBoxSize: [],
          contentBoxSize: [],
          devicePixelContentBoxSize: [],
        },
      ],
      {} as ResizeObserver,
    );
  }

  it('does not redraw an unchanged viewport repeatedly, but responds to a real resize', async () => {
    await open();
    vi.useFakeTimers();
    const baseline = rasterCalls.length;
    for (let index = 0; index < 4; index++) {
      resize(viewportWidth, viewportHeight);
      await vi.advanceTimersByTimeAsync(150);
      await flush();
    }
    expect(rasterCalls).toHaveLength(baseline);
    viewportWidth = 820;
    resize(viewportWidth, viewportHeight);
    await vi.advanceTimersByTimeAsync(150);
    await flush();
    expect(rasterCalls).toHaveLength(baseline + 1);
  });

  it('keeps the prior visible bitmap intact until both facing pages are ready, then commits the spread', async () => {
    await open();
    await fullPages();
    const live = Array.from(
      fixture.nativeElement.querySelectorAll('canvas.reader-canvas'),
    ) as HTMLCanvasElement[];
    const firstContext = contexts.get(live[0])!;
    const oldWidth = live[0].width;
    const oldHeight = live[0].height;
    const oldCommits = firstContext.drawImage.mock.calls.length;
    const facing = deferred();
    pending.set(2, facing);
    const switching = reader.setPageMode('double');
    await flush();
    expect(reader.rendering()).toBe(true);
    expect(live[0].width).toBe(oldWidth);
    expect(live[0].height).toBe(oldHeight);
    expect(firstContext.drawImage).toHaveBeenCalledTimes(oldCommits);
    expect(rasterCalls.slice(-2).every((call) => !live.includes(call.canvas))).toBe(true);
    expect(
      fixture.nativeElement.querySelectorAll('canvas.reader-canvas:not([hidden])'),
    ).toHaveLength(1);
    const progress = fixture.nativeElement.querySelector('.render-status') as HTMLElement;
    expect(getComputedStyle(progress).position).toBe('absolute');
    facing.resolve();
    await switching;
    await flush();
    expect(reader.status()).toBe('ready');
    expect(reader.rendering()).toBe(false);
    expect(firstContext.drawImage).toHaveBeenCalledTimes(oldCommits + 1);
    expect(contexts.get(live[1])?.drawImage).toHaveBeenCalledTimes(1);
    expect(
      fixture.nativeElement.querySelectorAll('canvas.reader-canvas:not([hidden])'),
    ).toHaveLength(2);
  });

  it('ignores an obsolete render rejection during resize and commits only the current fullscreen spread', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const unhandled = vi.fn();
    window.addEventListener('unhandledrejection', unhandled);
    try {
      await open();
      await fullPages();
      const live = fixture.nativeElement.querySelector('canvas.reader-canvas') as HTMLCanvasElement;
      const oldCommits = contexts.get(live)!.drawImage.mock.calls.length;
      const obsolete = deferred();
      pending.set(2, obsolete);
      const switching = reader.setPageMode('double');
      await flush();
      const obsoleteWidth = rasterCalls.at(-1)!.width;
      pending.delete(2);
      vi.useFakeTimers();
      viewportWidth = 820;
      resize(viewportWidth, viewportHeight);
      await vi.advanceTimersByTimeAsync(150);
      obsolete.reject(new Error('RenderingCancelledException: superseded chart render'));
      await switching;
      await flush();
      expect(reader.status()).toBe('ready');
      expect(reader.error()).toBe('');
      expect(reader.rendering()).toBe(false);
      expect(rasterCalls.at(-1)!.width).toBeLessThan(obsoleteWidth);
      expect(contexts.get(live)!.drawImage).toHaveBeenCalledTimes(oldCommits + 1);
      expect(
        fixture.nativeElement.querySelectorAll('canvas.reader-canvas:not([hidden])'),
      ).toHaveLength(2);
      expect(consoleError).not.toHaveBeenCalled();
      expect(unhandled).not.toHaveBeenCalled();
    } finally {
      window.removeEventListener('unhandledrejection', unhandled);
    }
  });

  it('provides synchronized custom color pickers before, inside and after fullscreen without fetching or rasterizing again for colors', async () => {
    await open();
    const select = fixture.nativeElement.querySelector('select') as HTMLSelectElement;
    select.value = 'colorful';
    select.dispatchEvent(new Event('change', { bubbles: true }));
    await flush();
    let pickers = fixture.nativeElement.querySelectorAll(
      'input[type="color"]',
    ) as NodeListOf<HTMLInputElement>;
    expect(pickers).toHaveLength(2);
    const baseline = rasterCalls.length;
    pickers[0].value = '#f5e7c6';
    pickers[0].dispatchEvent(new Event('input', { bubbles: true }));
    pickers[1].value = '#213b67';
    pickers[1].dispatchEvent(new Event('input', { bubbles: true }));
    await flush();
    expect(reader.backgroundColor()).toBe('#f5e7c6');
    expect(reader.textColor()).toBe('#213b67');
    expect(rasterCalls).toHaveLength(baseline);
    await reader.enterFullPage();
    await flush();
    expect((fixture.nativeElement.querySelector('select') as HTMLSelectElement).value).toBe(
      'colorful',
    );
    pickers = fixture.nativeElement.querySelectorAll('input[type="color"]');
    expect(Array.from(pickers, (picker) => picker.value)).toEqual(['#f5e7c6', '#213b67']);
    pickers[1].value = '#173f22';
    pickers[1].dispatchEvent(new Event('input', { bubbles: true }));
    await flush();
    await reader.exitFullPage();
    await flush();
    expect(reader.pageTheme()).toBe('colorful');
    expect(reader.textColor()).toBe('#173f22');
    expect(api.readBook).toHaveBeenCalledTimes(1);
    expect(engine.load).toHaveBeenCalledTimes(1);
  });

  it('applies fullscreen zoom and clamps both controls at 50% and 125%', async () => {
    await open();
    await fullPages();
    const originalWidth = rasterCalls.at(-1)!.width;
    reader.changeZoom(0.25);
    await flush();
    expect(reader.zoom()).toBe(1.25);
    expect(rasterCalls.at(-1)!.width).toBeCloseTo(originalWidth * 1.25);
    expect(
      (fixture.nativeElement.querySelector('[aria-label="Zoom in"]') as HTMLButtonElement).disabled,
    ).toBe(true);
    reader.changeZoom(5);
    await flush();
    expect(reader.zoom()).toBe(1.25);
    reader.changeZoom(-5);
    await flush();
    expect(reader.zoom()).toBe(0.5);
    expect(
      (fixture.nativeElement.querySelector('[aria-label="Zoom out"]') as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('50%');
    reader.changeZoom(-0.25);
    await flush();
    expect(reader.zoom()).toBe(0.5);
  });

  it('preserves wide-chart and very tall page aspect ratios in a double-page render at both zoom limits', async () => {
    makePage(1, 16000, 2000);
    makePage(2, 2000, 16000);
    await open();
    await fullPages();
    await reader.setPageMode('double');
    await flush();
    for (const zoom of [0.5, 1.25]) {
      reader.changeZoom(zoom - reader.zoom());
      await flush();
      const spread = rasterCalls.slice(-2);
      expect(spread.map((call) => call.page)).toEqual([1, 2]);
      for (const call of spread) {
        expect(Number.isFinite(call.width)).toBe(true);
        expect(call.width).toBeGreaterThan(0);
        const source = pages.get(call.page)!;
        expect(
          Number.parseFloat(call.canvas.style.height) / Number.parseFloat(call.canvas.style.width),
        ).toBeCloseTo(source.height! / source.width);
      }
      expect(reader.status()).toBe('ready');
      expect(reader.rendering()).toBe(false);
    }
  });

  it('reports an actual facing-page render failure without an uncaught error or rejection', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const unhandled = vi.fn();
    window.addEventListener('unhandledrejection', unhandled);
    try {
      await open();
      await fullPages();
      const facing = deferred();
      pending.set(2, facing);
      const switching = reader.setPageMode('double');
      await flush();
      facing.reject(new Error('Chart rasterization failed'));
      await switching;
      await flush();
      expect(reader.status()).toBe('error');
      expect(reader.error()).toContain('Chart rasterization failed');
      expect(consoleError).not.toHaveBeenCalled();
      expect(unhandled).not.toHaveBeenCalled();
    } finally {
      window.removeEventListener('unhandledrejection', unhandled);
    }
  });

  it('turns the retained page in opposite directions for next and previous with a visible, slower transition', async () => {
    const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'animate');
    const animate = vi.fn(
      (_frames: Keyframe[], _options: KeyframeAnimationOptions) =>
        ({
          cancel: vi.fn(),
          finished: Promise.resolve(),
        }) as unknown as Animation,
    );
    Object.defineProperty(HTMLElement.prototype, 'animate', { configurable: true, value: animate });
    try {
      await open();
      await fullPages();
      animate.mockClear();
      await reader.navigate(1);
      await flush();
      const forward = animate.mock.calls.find(([frames]) =>
        frames.some((frame) => /rotateY\(-\d/.test(String(frame['transform']))),
      );
      expect(forward).toBeDefined();
      expect(Number(forward![1].duration)).toBeGreaterThanOrEqual(450);
      animate.mockClear();
      await reader.navigate(-1);
      await flush();
      const backward = animate.mock.calls.find(([frames]) =>
        frames.some((frame) => /rotateY\([1-9]/.test(String(frame['transform']))),
      );
      expect(backward).toBeDefined();
      expect(Number(backward![1].duration)).toBeGreaterThanOrEqual(450);
      expect(reader.pageNumber()).toBe(1);
    } finally {
      if (original) Object.defineProperty(HTMLElement.prototype, 'animate', original);
      else Reflect.deleteProperty(HTMLElement.prototype, 'animate');
    }
  });
});
