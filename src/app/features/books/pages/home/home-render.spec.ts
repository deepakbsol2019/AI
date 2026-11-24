import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DeferBlockBehavior, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../../../../app.routes';
import { Book, BookSearchResponse } from '../../models/book.models';
import { HomeComponent } from './home.component';

const book: Book = {
  bookId: 5,
  bookName: 'A stored title',
  bookType: 'Programming',
  authorName: 'A stored author',
  bookDetails: 'Stored description',
  totalPages: 12,
  bookUploadId: 3,
  createdAt: null,
  updatedAt: null,
  authorDetails: null,
  topicCount: 1,
  topics: [
    {
      topicId: 21,
      topicName: 'A stored topic',
      topicDetails: 'Stored topic details',
      startPage: 1,
      endPage: 12,
      printedStartPage: null,
      printedEndPage: null,
      createdAt: null,
      updatedAt: null,
    },
  ],
};
const result: BookSearchResponse = {
  books: [book],
  page: 0,
  size: 5,
  totalElements: 1,
  totalPages: 1,
};

describe('Home rendered integration', () => {
  beforeEach(async () => {
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value() {
        this.open = true;
      },
    });
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
      configurable: true,
      value() {
        if (this.open) {
          this.open = false;
          this.dispatchEvent(new Event('close'));
        }
      },
    });
    TestBed.configureTestingModule({
      imports: [HomeComponent],
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
      deferBlockBehavior: DeferBlockBehavior.Playthrough,
    });
    await TestBed.compileComponents();
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('renders deferred search/table and preserves results and focus across the dynamic topics modal', async () => {
    const fixture = TestBed.createComponent(HomeComponent);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/search/books?size=5').flush(result);
    fixture.detectChanges();
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('main')).not.toBeNull();
    expect(element.querySelectorAll('main')).toHaveLength(1);
    const layout = element.querySelector('app-application-layout') as HTMLElement;
    const scrollRegion = layout.querySelector('[data-library-scroll-region]') as HTMLElement;
    expect(layout.querySelectorAll('[data-library-scroll-region]')).toHaveLength(1);
    expect(scrollRegion.contains(element.querySelector('main'))).toBe(true);
    expect(layout.querySelector('app-header')?.parentElement).toBe(layout);
    expect(layout.querySelector('app-footer')?.parentElement).toBe(layout);
    expect(scrollRegion.querySelector('app-header, app-footer')).toBeNull();
    expect(element.querySelector('main')?.tabIndex).toBe(-1);
    expect(element.querySelector('.welcome, .library-footnote')).toBeNull();
    expect(element.textContent).not.toContain('Your next chapter');
    expect(element.textContent).not.toContain('A whole book or just one topic.');
    expect(element.querySelector('app-book-search input')).not.toBeNull();
    const search = scrollRegion.querySelector('app-book-search') as HTMLElement;
    const table = scrollRegion.querySelector('app-book-table') as HTMLElement;
    expect(search.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(element.querySelectorAll('app-book-table thead th')).toHaveLength(7);
    expect(
      element.querySelector('app-book-table .type-cell .truncated-value')?.textContent?.trim(),
    ).toBe('Programming');
    const opener = element.querySelector('app-book-table .view-topics-button') as HTMLButtonElement;
    opener.focus();
    await fixture.componentInstance.viewTopics(book);
    await fixture.whenStable();
    const dialog = element.querySelector('dialog') as HTMLDialogElement;
    expect(dialog.open).toBe(true);
    expect(dialog.querySelector('h2')?.textContent?.trim()).toBe(book.bookName);
    expect(dialog.querySelectorAll('thead th')).toHaveLength(4);
    (dialog.querySelector('.close-button') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(element.querySelector('dialog')).toBeNull();
    expect(fixture.componentInstance.results()).toEqual(result);
    expect(document.activeElement).toBe(opener);
  }, 15_000);

  it('redirects an unknown URL to the lazy Home page', async () => {
    const harness = await RouterTestingHarness.create();
    const pending = harness.navigateByUrl('/a-route-that-does-not-exist', HomeComponent);
    // Lazy routing must resolve before Home subscribes to its one initial search.
    await vi.waitFor(() => {
      const requests = TestBed.inject(HttpTestingController).match('/api/search/books?size=5');
      expect(requests).toHaveLength(1);
      requests[0].flush(result);
    });
    await pending;
    expect(TestBed.inject(Router).url).toBe('/');
    expect(harness.routeNativeElement?.querySelector('main#main-content')).not.toBeNull();
    expect(harness.routeNativeElement?.querySelector('.welcome, .library-footnote')).toBeNull();
    expect(harness.routeNativeElement?.textContent).toContain('Find something worth reading');
  });
});
