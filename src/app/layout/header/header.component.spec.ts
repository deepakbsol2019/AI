import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HeaderComponent } from './header.component';

describe('Library header controls', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [HeaderComponent],
      providers: [provideRouter([]), provideHttpClient()],
    }),
  );

  it('exposes AI Help to signed-in superadmins even outside the library controls', () => {
    const fixture = TestBed.createComponent(HeaderComponent);
    fixture.componentRef.setInput('sidebarAvailable', true);
    fixture.componentRef.setInput('libraryControls', false);
    fixture.componentRef.setInput('profileName', 'Superadmin');
    fixture.detectChanges();
    const link = fixture.nativeElement.querySelector('a[aria-label="AI Help"]') as HTMLAnchorElement;
    expect(link).not.toBeNull();
    expect(link.getAttribute('href')).toBe('/ai-help');
    expect(link.getAttribute('aria-disabled')).not.toBe('true');
  });

  it('replaces the navigation toggle with a leftmost profile icon and compact identity tooltip', () => {
    const fixture = TestBed.createComponent(HeaderComponent);
    const toggled = vi.fn();
    fixture.componentInstance.sidebarToggle.subscribe(toggled);
    fixture.componentRef.setInput('sidebarAvailable', true);
    fixture.componentRef.setInput('profileUsername', 'reader.name');
    fixture.componentRef.setInput('profileName', 'Reader Name');
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const control = element.querySelector('.profile-control') as HTMLElement;
    const button = control.querySelector('button')!;
    expect(control.querySelector('[role="tooltip"]')).toBeNull();
    control.dispatchEvent(new MouseEvent('mouseenter'));
    fixture.detectChanges();
    const tooltip = control.querySelector('[role="tooltip"]')!;
    expect(element.querySelector('.header-content')?.firstElementChild).toBe(control);
    expect(button.classList.contains('profile-trigger')).toBe(true);
    expect(button.getAttribute('aria-label')).toBe('Open profile');
    expect(button.getAttribute('aria-describedby')).toBe(tooltip.id);
    expect(tooltip.textContent).toContain('reader.name');
    expect(tooltip.textContent).toContain('Reader Name');
    expect(tooltip.querySelector('button,a,select')).toBeNull();
    expect(toggled).not.toHaveBeenCalled();
    button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    expect(control.querySelector('[role="tooltip"]')).toBeNull();
    button.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    fixture.detectChanges();
    expect(control.querySelector('[role="tooltip"]')).not.toBeNull();
    button.click();
    expect(toggled).toHaveBeenCalledTimes(1);
    fixture.componentRef.setInput('sidebarExpanded', true);
    fixture.detectChanges();
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(button.getAttribute('aria-label')).toBe('Close profile');
    expect(control.querySelector('[role="tooltip"]')).toBeNull();
  });

  it('does not emit a selection when the picker is cancelled', () => {
    const fixture = TestBed.createComponent(HeaderComponent);
    const selected = vi.fn();
    fixture.componentInstance.fileSelected.subscribe(selected);
    fixture.detectChanges();
    const input: HTMLInputElement = fixture.nativeElement.querySelector('input');
    input.dispatchEvent(new Event('change'));
    expect(selected).not.toHaveBeenCalled();
  });

  it('clears the native value so the same file can be chosen again', () => {
    const fixture = TestBed.createComponent(HeaderComponent);
    const selected = vi.fn();
    fixture.componentInstance.fileSelected.subscribe(selected);
    fixture.detectChanges();
    const input: HTMLInputElement = fixture.nativeElement.querySelector('input');
    const file = new File(['%PDF-1.7'], 'book.pdf', { type: 'application/pdf' });
    Object.defineProperty(input, 'files', { value: { item: () => file } });
    Object.defineProperty(input, 'value', { value: 'book.pdf', writable: true });
    input.dispatchEvent(new Event('change'));
    expect(input.value).toBe('');
    input.value = 'book.pdf';
    input.dispatchEvent(new Event('change'));
    expect(input.value).toBe('');
    expect(selected).toHaveBeenCalledTimes(2);
    expect(selected).toHaveBeenLastCalledWith(file);
  });

  it('prevents another upload selection while an upload is active', () => {
    const fixture = TestBed.createComponent(HeaderComponent);
    const selected = vi.fn();
    fixture.componentInstance.fileSelected.subscribe(selected);
    fixture.componentRef.setInput('uploadBusy', true);
    fixture.detectChanges();
    const input: HTMLInputElement = fixture.nativeElement.querySelector('input');
    Object.defineProperty(input, 'files', { value: { item: () => new File(['pdf'], 'book.pdf') } });
    input.dispatchEvent(new Event('change'));
    expect(input.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.upload-button').disabled).toBe(true);
    expect(selected).not.toHaveBeenCalled();
  });

  it('always renders both restore controls natively disabled while their sections are visible', () => {
    const fixture = TestBed.createComponent(HeaderComponent);
    const restoreSearch = vi.fn();
    const restoreCollection = vi.fn();
    fixture.componentInstance.restoreSearch.subscribe(restoreSearch);
    fixture.componentInstance.restoreCollection.subscribe(restoreCollection);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(
      element.querySelectorAll<HTMLButtonElement>('.section-restores button'),
    );
    expect(buttons.map((button) => button.textContent?.trim())).toEqual([
      'Search',
      'Book Collection',
    ]);
    for (const button of buttons) {
      expect(button.disabled).toBe(true);
      expect(button.matches(':disabled')).toBe(true);
      button.focus();
      expect(document.activeElement).not.toBe(button);
      button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      button.dispatchEvent(new KeyboardEvent('keyup', { key: ' ', bubbles: true }));
      button.click();
      // Even a manually dispatched click must respect the current minimized state.
      button.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    }
    expect(restoreSearch).not.toHaveBeenCalled();
    expect(restoreCollection).not.toHaveBeenCalled();
  });

  it('enables and emits only the corresponding minimized section restore action', () => {
    const fixture = TestBed.createComponent(HeaderComponent);
    const restoreSearch = vi.fn();
    const restoreCollection = vi.fn();
    fixture.componentInstance.restoreSearch.subscribe(restoreSearch);
    fixture.componentInstance.restoreCollection.subscribe(restoreCollection);
    fixture.componentRef.setInput('searchMinimized', true);
    fixture.detectChanges();
    const search = fixture.nativeElement.querySelector('.restore-search') as HTMLButtonElement;
    const collection = fixture.nativeElement.querySelector(
      '.restore-collection',
    ) as HTMLButtonElement;
    expect(search.disabled).toBe(false);
    expect(collection.disabled).toBe(true);
    search.click();
    collection.click();
    expect(restoreSearch).toHaveBeenCalledTimes(1);
    expect(restoreCollection).not.toHaveBeenCalled();

    fixture.componentRef.setInput('searchMinimized', false);
    fixture.componentRef.setInput('collectionMinimized', true);
    fixture.detectChanges();
    expect(search.disabled).toBe(true);
    expect(collection.disabled).toBe(false);
    search.click();
    collection.click();
    expect(restoreSearch).toHaveBeenCalledTimes(1);
    expect(restoreCollection).toHaveBeenCalledTimes(1);
  });
});
