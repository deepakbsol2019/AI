import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HeaderComponent } from './header.component';

describe('PDF file picker', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [HeaderComponent],
      providers: [provideRouter([])],
    }),
  );

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
    expect(fixture.nativeElement.querySelector('button').disabled).toBe(true);
    expect(selected).not.toHaveBeenCalled();
  });
});
