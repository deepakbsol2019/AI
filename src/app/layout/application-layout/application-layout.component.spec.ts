import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { HeaderComponent } from '../header/header.component';
import { ApplicationLayoutComponent } from './application-layout.component';

@Component({
  imports: [ApplicationLayoutComponent],
  template: `<app-application-layout
    [uploadBusy]="busy"
    [searchMinimized]="searchMinimized()"
    [collectionMinimized]="collectionMinimized()"
    (fileSelected)="selected = $event"
    (restoreSearch)="searchMinimized.set(false); searchRestores = searchRestores + 1"
    (restoreCollection)="
      collectionMinimized.set(false); collectionRestores = collectionRestores + 1
    "
  >
    <main id="main-content" tabindex="-1">Projected search and results</main>
  </app-application-layout>`,
})
class LayoutHost {
  busy = false;
  selected: File | null = null;
  searchMinimized = signal(false);
  collectionMinimized = signal(false);
  searchRestores = 0;
  collectionRestores = 0;
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
    expect(fixture.nativeElement.querySelector('app-header .upload-button').disabled).toBe(true);
    const file = new File(['%PDF-1.7'], 'book.pdf', { type: 'application/pdf' });
    header.fileSelected.emit(file);
    expect(fixture.componentInstance.selected).toBe(file);
  });

  it('forwards each restore state and action independently without replacing the scroll region', () => {
    const fixture = TestBed.createComponent(LayoutHost);
    fixture.componentInstance.busy = true;
    fixture.componentInstance.searchMinimized.set(true);
    fixture.detectChanges();
    const region = fixture.nativeElement.querySelector('[data-library-scroll-region]');
    const header = fixture.debugElement.query(By.directive(HeaderComponent))
      .componentInstance as HeaderComponent;
    const search = fixture.nativeElement.querySelector('.restore-search') as HTMLButtonElement;
    const collection = fixture.nativeElement.querySelector(
      '.restore-collection',
    ) as HTMLButtonElement;
    expect(header.searchMinimized()).toBe(true);
    expect(header.collectionMinimized()).toBe(false);
    expect(search.disabled).toBe(false);
    expect(collection.disabled).toBe(true);
    search.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.searchRestores).toBe(1);
    expect(fixture.componentInstance.collectionRestores).toBe(0);
    expect(search.disabled).toBe(true);

    fixture.componentInstance.collectionMinimized.set(true);
    fixture.detectChanges();
    expect(header.collectionMinimized()).toBe(true);
    collection.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.searchRestores).toBe(1);
    expect(fixture.componentInstance.collectionRestores).toBe(1);
    expect(collection.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('[data-library-scroll-region]')).toBe(region);
  });
});
