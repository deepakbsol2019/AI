import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DeferBlockBehavior, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { PdfReaderService } from '../../data-access/pdf-reader.service';
import type { Book, BookSearchResponse } from '../../models/book.models';
import { HomeComponent } from './home.component';

const book: Book = {
  bookId: 5,
  bookName: 'Actual PDF book',
  bookType: 'Programming',
  authorName: null,
  bookDetails: 'Metadata only',
  totalPages: 20,
  bookUploadId: null,
  createdAt: null,
  updatedAt: null,
  authorDetails: null,
  topicCount: 3,
  topics: [
    { topicId: 21, topicName: 'First topic' },
    { topicId: 45, topicName: 'Other topic' },
    { topicId: 76, topicName: 'Last topic' },
  ].map((topic, index) => ({
    ...topic,
    topicDetails: null,
    startPage: index * 5 + 1,
    endPage: (index + 1) * 5,
    printedStartPage: null,
    printedEndPage: null,
    createdAt: null,
    updatedAt: null,
  })),
};
const results = (page = 0, size = 10): BookSearchResponse => ({
  books: [book],
  page,
  size,
  totalElements: 21,
  totalPages: Math.ceil(21 / size),
});

describe('Reader origin and Back state', () => {
  const render = vi.fn(() => ({ promise: Promise.resolve(), cancel: vi.fn() }));
  const destroy = vi.fn(async () => undefined);
  const engine = {
    load: vi.fn(() => ({
      promise: Promise.resolve({
        pageCount: 5,
        getPage: async () => ({ width: 612, render, cleanup: vi.fn() }),
      }),
      destroy,
    })),
  };

  beforeEach(() => {
    vi.clearAllMocks();
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
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PdfReaderService, useValue: engine },
      ],
      deferBlockBehavior: DeferBlockBehavior.Playthrough,
    });
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('keeps the originating popup mounted through selected/individual reading and returns to the same results', async () => {
    const fixture = TestBed.createComponent(HomeComponent);
    const home = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/search/books?size=5').flush(results(0, 5));
    fixture.detectChanges();
    await fixture.whenStable();
    // A deliberate larger page size and restored Search survive every reader/Back path.
    home.changeSize({ target: { value: '10' } } as unknown as Event);
    http.expectOne('/api/search/books?size=10').flush(results());
    home.searchMinimized.set(false);
    home.search({ bookName: 'Actual PDF book' });
    http.expectOne('/api/search/books?bookName=Actual%20PDF%20book&size=10').flush(results());
    home.changePage(1);
    const savedResults = results(1);
    http
      .expectOne('/api/search/books?bookName=Actual%20PDF%20book&page=1&size=10')
      .flush(savedResults);
    fixture.detectChanges();
    await home.viewTopics(book);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const popup = element.querySelector<HTMLDialogElement>('.topics-dialog')!;
    const select = (id: number) => {
      popup.querySelector<HTMLInputElement>(`[data-topic-id="${id}"]`)!.click();
      fixture.detectChanges();
    };
    select(76);
    select(21);
    const filter = popup.querySelector<HTMLInputElement>('#topic-filter')!;
    filter.value = 'Other';
    filter.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    popup.querySelector<HTMLButtonElement>('.selected-view')!.click();
    const openReader = async (url: string) => {
      await vi.waitFor(() => {
        const requests = http.match(url);
        expect(requests).toHaveLength(1);
        requests[0].flush(new Blob(['%PDF-inline'], { type: 'application/pdf' }));
      });
      await fixture.whenStable();
      expect(element.querySelector<HTMLDialogElement>('.reader-dialog')?.open).toBe(true);
      expect(popup.open).toBe(true);
      expect(element.querySelector('.topics-dialog')).toBe(popup);
      element.querySelector<HTMLButtonElement>('.reader-back')!.click();
      await fixture.whenStable();
      expect(element.querySelector('.reader-dialog')).toBeNull();
      expect(element.querySelector('.topics-dialog')).toBe(popup);
      expect(filter.value).toBe('Other');
      expect(popup.querySelector('#selection-count')?.textContent).toContain('2 selected');
      expect(home.results()).toEqual(savedResults);
      expect(home.pageSize()).toBe(10);
      expect(home.searchMinimized()).toBe(false);
      http.expectNone(() => true);
    };
    await openReader('/api/read/topics?bookId=5&topicIds=21&topicIds=76');
    // Reader Back preserves selection; explicitly deselect before an individual view.
    filter.value = '';
    filter.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    select(21);
    select(76);
    popup.querySelector<HTMLButtonElement>('.topic-view')!.click();
    await vi.waitFor(() => {
      const requests = http.match('/api/read/topics?bookId=5&topicIds=21');
      expect(requests).toHaveLength(1);
      requests[0].flush(new Blob(['%PDF-single'], { type: 'application/pdf' }));
    });
    await fixture.whenStable();
    element.querySelector<HTMLButtonElement>('.reader-back')!.click();
    await fixture.whenStable();
    expect(element.querySelector('.topics-dialog')).toBe(popup);
    popup.querySelector<HTMLButtonElement>('.close-button')!.click();
    await fixture.whenStable();
    expect(element.querySelector('.topics-dialog')).toBeNull();

    await home.viewBook(book);
    fixture.detectChanges();
    await vi.waitFor(() => {
      const requests = http.match('/api/read/book?bookId=5');
      expect(requests).toHaveLength(1);
      requests[0].flush(new Blob(['%PDF-full'], { type: 'application/pdf' }));
    });
    await fixture.whenStable();
    expect(element.querySelector('#reader-scope')?.textContent).toContain('Complete book');
    element.querySelector<HTMLButtonElement>('.reader-back')!.click();
    await fixture.whenStable();
    expect(home.results()).toEqual(savedResults);
    expect(home.activeFilterCount()).toBe(1);
    expect(home.pageSize()).toBe(10);
    expect(home.searchMinimized()).toBe(false);
    expect(element.querySelector('.page-label')?.textContent).toContain('2 / 3');
    expect(engine.load).toHaveBeenCalledTimes(3);
    expect(destroy).toHaveBeenCalledTimes(3);
    http.expectNone(() => true);
  }, 15_000);
});
