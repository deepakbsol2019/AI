import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { HeaderComponent } from '../header/header.component';
import { ApplicationLayoutComponent } from './application-layout.component';

@Component({
  imports: [ApplicationLayoutComponent],
  template: `<app-application-layout [uploadBusy]="busy" (fileSelected)="selected = $event">
    <main id="main-content" tabindex="-1">Projected search and results</main>
  </app-application-layout>`,
})
class LayoutHost {
  busy = false;
  selected: File | null = null;
}

describe('Application viewport layout', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [LayoutHost],
      providers: [provideRouter([])],
    }),
  );

  it('places projected content in one keyboard-accessible scroll region between header and footer', () => {
    const fixture = TestBed.createComponent(LayoutHost);
    fixture.detectChanges();
    const layout = fixture.nativeElement.querySelector('app-application-layout') as HTMLElement;
    const region = layout.querySelector('[data-library-scroll-region]') as HTMLElement;
    expect(region.parentElement).toBe(layout);
    expect(region.getAttribute('role')).toBe('region');
    expect(region.getAttribute('aria-label')).toBe('Library search and book results');
    expect(region.tabIndex).toBe(0);
    expect(region.querySelector('main')?.textContent).toContain('Projected search and results');
    expect(region.previousElementSibling?.tagName).toBe('APP-HEADER');
    expect(region.nextElementSibling?.tagName).toBe('APP-FOOTER');
    expect(region.querySelector('app-header, app-footer')).toBeNull();
    expect(layout.querySelector('.skip-link')?.getAttribute('href')).toBe('#main-content');
  });

  it('preserves upload activity and file-selection events through the fixed shell', () => {
    const fixture = TestBed.createComponent(LayoutHost);
    fixture.componentInstance.busy = true;
    fixture.detectChanges();
    const header = fixture.debugElement.query(By.directive(HeaderComponent))
      .componentInstance as HeaderComponent;
    expect(header.uploadBusy()).toBe(true);
    expect(fixture.nativeElement.querySelector('app-header button').disabled).toBe(true);
    const file = new File(['%PDF-1.7'], 'book.pdf', { type: 'application/pdf' });
    header.fileSelected.emit(file);
    expect(fixture.componentInstance.selected).toBe(file);
  });
});
