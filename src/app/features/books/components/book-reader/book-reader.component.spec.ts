import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpResponse } from '@angular/common/http';
import { Subject } from 'rxjs';
import { BooksApiService } from '../../data-access/books-api.service';
import { BookDownloadService } from '../../data-access/book-download.service';
import { PdfReaderService } from '../../data-access/pdf-reader.service';
import type { Book } from '../../models/book.models';
import type { PdfLoadHandle, PdfReaderDocument, ReaderRequest } from '../../models/reader.models';
import { BookReaderComponent } from './book-reader.component';

const book: Book = {
  bookId: 8,
  bookName: 'A complete PDF',
  bookType: 'Physics',
  authorName: null,
  bookDetails: null,
  totalPages: 3,
  bookUploadId: null,
  authorDetails: null,
  createdAt: null,
  updatedAt: null,
  topicCount: 2,
  topics: [21, 35].map((topicId, index) => ({
    topicId,
    topicName: `Topic ${topicId}`,
    topicDetails: null,
    startPage: index + 1,
    endPage: index + 1,
    printedStartPage: null,
    printedEndPage: null,
    createdAt: null,
    updatedAt: null,
  })),
};
const scopes: ReaderRequest[] = [
  { mode: 'book', book, topics: [] },
  { mode: 'topic', book, topics: [book.topics[1]] },
  { mode: 'selected-topics', book, topics: book.topics },
];

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((accept) => {
    resolve = accept;
  });
  return { promise, resolve };
}

describe('BookReaderComponent', () => {
  let books: Subject<HttpResponse<Blob>>;
  let topics: Subject<HttpResponse<Blob>>;
  const api = { readBook: vi.fn(), readTopics: vi.fn() };
  const downloads = {
    downloadBook: vi.fn(),
    downloadTopic: vi.fn(),
    downloadSelectedTopics: vi.fn(),
    isBookDownloading: () => false,
    isTopicDownloading: () => false,
    isSelectedTopicsDownloading: () => false,
    isAnyTopicDownloading: () => false,
  };
  const cancel = vi.fn();
  const cleanup = vi.fn();
  const page = {
    width: 600,
    render: vi.fn(() => ({ promise: Promise.resolve(), cancel })),
    cleanup,
  };
  const pdf: PdfReaderDocument = { pageCount: 3, getPage: vi.fn(async () => page) };
  const destroy = vi.fn(async () => undefined);
  const engine = { load: vi.fn<(blob: Blob) => PdfLoadHandle>() };

  beforeEach(() => {
    vi.clearAllMocks();
    cancel.mockImplementation(() => undefined);
    books = new Subject();
    topics = new Subject();
    api.readBook.mockImplementation(() => books.asObservable());
    api.readTopics.mockImplementation(() => topics.asObservable());
    page.render.mockImplementation(() => ({ promise: Promise.resolve(), cancel }));
    engine.load.mockReturnValue({ promise: Promise.resolve(pdf), destroy });
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
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    TestBed.configureTestingModule({
      imports: [BookReaderComponent],
      providers: [
        { provide: BooksApiService, useValue: api },
        { provide: BookDownloadService, useValue: downloads },
        { provide: PdfReaderService, useValue: engine },
      ],
    });
  });
  afterEach(() => vi.restoreAllMocks());

  async function settle(fixture: ComponentFixture<BookReaderComponent>): Promise<void> {
    for (let index = 0; index < 12; index++) await Promise.resolve();
    fixture.detectChanges();
    await fixture.whenStable();
  }
  async function create(request = scopes[0]): Promise<ComponentFixture<BookReaderComponent>> {
    const fixture = TestBed.createComponent(BookReaderComponent);
    fixture.componentRef.setInput('request', request);
    fixture.detectChanges();
    await settle(fixture);
    return fixture;
  }
  const response = () =>
    new HttpResponse({ body: new Blob(['%PDF'], { type: 'application/pdf' }) });

  it.each(scopes)(
    'fetches inline PDF and delegates only the explicit $mode download scope',
    async (request) => {
      const fixture = await create(request);
      expect(fixture.componentInstance.status()).toBe('loading');
      if (request.mode === 'book') expect(api.readBook).toHaveBeenCalledExactlyOnceWith(8);
      else
        expect(api.readTopics).toHaveBeenCalledExactlyOnceWith(
          8,
          request.topics.map((topic) => topic.topicId),
        );
      expect(downloads.downloadBook).not.toHaveBeenCalled();
      expect(downloads.downloadTopic).not.toHaveBeenCalled();
      expect(downloads.downloadSelectedTopics).not.toHaveBeenCalled();
      expect(HTMLAnchorElement.prototype.click).not.toHaveBeenCalled();
      (request.mode === 'book' ? books : topics).next(response());
      await settle(fixture);
      expect(fixture.componentInstance.status()).toBe('ready');
      (fixture.nativeElement.querySelector('.reader-download') as HTMLButtonElement).click();
      if (request.mode === 'book') expect(downloads.downloadBook).toHaveBeenCalledWith(book);
      if (request.mode === 'topic')
        expect(downloads.downloadTopic).toHaveBeenCalledWith(book, book.topics[1]);
      if (request.mode === 'selected-topics')
        expect(downloads.downloadSelectedTopics).toHaveBeenCalledWith(book, book.topics);
      expect(HTMLAnchorElement.prototype.click).not.toHaveBeenCalled();
    },
  );

  it('renders just one page at a time and navigates without another PDF request', async () => {
    const fixture = await create();
    books.next(response());
    await settle(fixture);
    expect(pdf.getPage).toHaveBeenCalledExactlyOnceWith(1);
    expect(fixture.nativeElement.querySelectorAll('canvas')).toHaveLength(1);
    expect(fixture.nativeElement.textContent).toContain('Page 1 of 3');
    (fixture.nativeElement.querySelector('.reader-next') as HTMLButtonElement).click();
    await settle(fixture);
    expect(pdf.getPage).toHaveBeenLastCalledWith(2);
    expect(fixture.nativeElement.textContent).toContain('Page 2 of 3');
    expect(cleanup).toHaveBeenCalled();
    expect(api.readBook).toHaveBeenCalledTimes(1);
    (fixture.nativeElement.querySelector('.zoom-in') as HTMLButtonElement).click();
    await settle(fixture);
    expect(fixture.componentInstance.zoom()).toBe(1.25);
    (fixture.nativeElement.querySelector('.reader-fit') as HTMLButtonElement).click();
    await settle(fixture);
    expect(fixture.componentInstance.zoom()).toBe(1);
  });

  it('defaults to original light pages and toggles dark mode without another request, render, or download', async () => {
    const fixture = await create();
    books.next(response());
    await settle(fixture);
    const element = fixture.nativeElement as HTMLElement;
    const dialog = element.querySelector('.reader-dialog') as HTMLDialogElement;
    const canvas = element.querySelector('canvas') as HTMLCanvasElement;
    const mode = element.querySelector('#reader-color-mode') as HTMLSelectElement;
    expect(fixture.componentInstance.pageTheme()).toBe('original');
    expect(mode.value).toBe('original');
    expect(Array.from(mode.options, (option) => option.textContent)).toEqual([
      'Light mode (original PDF)',
      'Dark mode',
    ]);
    expect(dialog.classList.contains('dark-mode')).toBe(false);
    expect(canvas.classList.contains('page-dark')).toBe(false);
    expect(page.render).toHaveBeenLastCalledWith(canvas, expect.any(Number), 'original');
    const renderCount = page.render.mock.calls.length;

    mode.value = 'dark';
    mode.dispatchEvent(new Event('change', { bubbles: true }));
    await settle(fixture);
    expect(fixture.componentInstance.pageTheme()).toBe('dark');
    expect(dialog.classList.contains('dark-mode')).toBe(true);
    expect(canvas.classList.contains('page-dark')).toBe(true);
    mode.value = 'original';
    mode.dispatchEvent(new Event('change', { bubbles: true }));
    await settle(fixture);
    expect(dialog.classList.contains('dark-mode')).toBe(false);
    expect(canvas.classList.contains('page-dark')).toBe(false);
    fixture.componentInstance.changeTheme({ target: { value: 'inverted' } } as unknown as Event);
    fixture.detectChanges();
    expect(fixture.componentInstance.pageTheme()).toBe('dark');
    expect(canvas.classList.contains('page-dark')).toBe(true);
    expect(page.render).toHaveBeenCalledTimes(renderCount);
    expect(api.readBook).toHaveBeenCalledTimes(1);
    expect(api.readTopics).not.toHaveBeenCalled();
    expect(downloads.downloadBook).not.toHaveBeenCalled();
    expect(downloads.downloadTopic).not.toHaveBeenCalled();
    expect(downloads.downloadSelectedTopics).not.toHaveBeenCalled();
    expect(HTMLAnchorElement.prototype.click).not.toHaveBeenCalled();
    expect(element.querySelector('.color-help')?.textContent).toContain('scanned pages and photos');
  });

  it('keeps reading and downloading selected topics in the original book order', async () => {
    const fixture = await create({
      mode: 'selected-topics',
      book,
      topics: [...book.topics].reverse(),
    });
    expect(api.readTopics).toHaveBeenCalledExactlyOnceWith(8, [21, 35]);
    (fixture.nativeElement.querySelector('.reader-download') as HTMLButtonElement).click();
    expect(downloads.downloadSelectedTopics).toHaveBeenCalledWith(book, book.topics);
  });

  it('reads and downloads front matter before a later selected chapter even when the offset ID is last', async () => {
    const physicalBook: Book = {
      ...book,
      totalPages: 100,
      topicCount: 3,
      topics: [
        { ...book.topics[0], topicId: 1, startPage: 23, endPage: 54 },
        { ...book.topics[1], topicId: 3, startPage: 55, endPage: 100 },
        {
          ...book.topics[0],
          topicId: 90,
          topicName: `${book.bookName}_offset`,
          startPage: 1,
          endPage: 22,
        },
      ],
    };
    const fixture = await create({
      mode: 'selected-topics',
      book: physicalBook,
      topics: [physicalBook.topics[1], physicalBook.topics[2]],
    });
    expect(api.readTopics).toHaveBeenCalledExactlyOnceWith(8, [90, 3]);
    (fixture.nativeElement.querySelector('.reader-download') as HTMLButtonElement).click();
    expect(downloads.downloadSelectedTopics).toHaveBeenCalledWith(physicalBook, [
      physicalBook.topics[2],
      physicalBook.topics[1],
    ]);
  });

  it('destroys replaced loading resources and ignores a previous selection that resolves late', async () => {
    const previous = deferred<PdfReaderDocument>();
    const oldDestroy = vi.fn(async () => undefined);
    engine.load.mockReturnValueOnce({ promise: previous.promise, destroy: oldDestroy });
    const fixture = await create();
    books.next(response());
    await settle(fixture);
    fixture.componentRef.setInput('request', scopes[1]);
    await settle(fixture);
    expect(oldDestroy).toHaveBeenCalledTimes(1);
    topics.next(response());
    await settle(fixture);
    previous.resolve({ pageCount: 99, getPage: vi.fn(async () => page) });
    await settle(fixture);
    expect(fixture.componentInstance.pageCount()).toBe(3);
    expect(fixture.componentInstance.title()).toBe('Topic 35');
  });

  it('reports actual PDF-engine failure, frees resources, and retries the inline request', async () => {
    engine.load.mockImplementationOnce(() => ({
      promise: Promise.reject(new Error('Invalid PDF bytes')),
      destroy,
    }));
    const fixture = await create();
    books.next(response());
    await settle(fixture);
    expect(fixture.componentInstance.status()).toBe('error');
    expect(fixture.nativeElement.textContent).toContain('Invalid PDF bytes');
    expect(destroy).toHaveBeenCalledTimes(1);
    (fixture.nativeElement.querySelector('.reader-retry') as HTMLButtonElement).click();
    expect(api.readBook).toHaveBeenCalledTimes(2);
    books.next(response());
    await settle(fixture);
    expect(fixture.componentInstance.status()).toBe('ready');
  });

  it('shows empty file feedback instead of claiming metadata is document content', async () => {
    const fixture = await create();
    books.next(new HttpResponse({ body: new Blob([]) }));
    await settle(fixture);
    expect(fixture.componentInstance.status()).toBe('empty');
    expect(engine.load).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('No PDF content returned');
  });

  it('cancels rendering, destroys the PDF and emits Back once without closing the parent popup', async () => {
    const pending = deferred<void>();
    cancel.mockImplementation(() => pending.resolve());
    page.render.mockReturnValue({ promise: pending.promise, cancel });
    const fixture = await create();
    books.next(response());
    await settle(fixture);
    expect(fixture.componentInstance.rendering()).toBe(true);
    const closed = vi.fn();
    fixture.componentInstance.closed.subscribe(closed);
    (fixture.nativeElement.querySelector('.reader-back') as HTMLButtonElement).click();
    await settle(fixture);
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(destroy).toHaveBeenCalledTimes(1);
    expect(closed).toHaveBeenCalledTimes(1);
    expect((fixture.nativeElement.querySelector('canvas') as HTMLCanvasElement).width).toBe(0);
    fixture.destroy();
    expect(closed).toHaveBeenCalledTimes(1);
  });
});
