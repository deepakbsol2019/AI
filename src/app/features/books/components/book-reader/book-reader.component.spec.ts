import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpResponse } from '@angular/common/http';
import { Subject } from 'rxjs';
import { BooksApiService } from '../../data-access/books-api.service';
import { BookDownloadService } from '../../data-access/book-download.service';
import { PdfReaderService } from '../../data-access/pdf-reader.service';
import { PageTurnSoundService } from '../../data-access/page-turn-sound.service';
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
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
      drawImage: vi.fn(),
    } as unknown as CanvasRenderingContext2D);
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
    // Existing behavior remains available through the explicit Single Page choice.
    fixture.componentInstance.pageMode.set('single');
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
    expect(fixture.nativeElement.querySelectorAll('canvas:not([hidden])')).toHaveLength(1);
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
      'Colorful mode',
    ]);
    expect(dialog.classList.contains('dark-mode')).toBe(false);
    expect(canvas.classList.contains('page-dark')).toBe(false);
    expect(page.render).toHaveBeenLastCalledWith(
      expect.any(HTMLCanvasElement),
      expect.any(Number),
      'original',
    );
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

  it('puts zoom beside the bottom page mode control and exits to the same reader position', async () => {
    const fixture = await create();
    books.next(response());
    await settle(fixture);
    await fixture.componentInstance.navigate(1);
    await settle(fixture);
    expect(fixture.componentInstance.pageNumber()).toBe(2);
    const viewport = fixture.nativeElement.querySelector('.reader-content') as HTMLElement;
    viewport.scrollTop = 240;
    viewport.scrollLeft = 35;
    const closed = vi.fn();
    fixture.componentInstance.closed.subscribe(closed);
    await fixture.componentInstance.enterFullPage();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.reader-header')).toBeNull();
    expect(fixture.nativeElement.querySelector('.reader-footer')).toBeNull();
    expect(
      fixture.nativeElement.querySelector('.full-reader-controls').querySelectorAll('button'),
    ).toHaveLength(8);
    expect(fixture.nativeElement.querySelector('.full-reader-appearance')).toBeNull();
    expect(fixture.nativeElement.querySelector('select')).toBeNull();
    expect(fixture.nativeElement.querySelector('.mode-zoom-controls .zoom-in')).not.toBeNull();
    expect(fixture.componentInstance.stage()).toBe('front');
    expect(fixture.nativeElement.querySelector('.front-cover h2').textContent).toContain(
      book.bookName,
    );
    expect(fixture.componentInstance.canPrevious()).toBe(false);
    await fixture.componentInstance.exitFullPage();
    await settle(fixture);
    expect(fixture.componentInstance.fullPage()).toBe(false);
    expect(fixture.componentInstance.pageNumber()).toBe(2);
    expect(viewport.scrollTop).toBe(240);
    expect(viewport.scrollLeft).toBe(35);
    expect(api.readBook).toHaveBeenCalledTimes(1);
    expect(engine.load).toHaveBeenCalledTimes(1);
    expect(closed).not.toHaveBeenCalled();
  });

  it('shows every PDF page in facing spreads between covers, keeps focus on mode switches and stops at the back cover', async () => {
    const fixture = await create();
    books.next(response());
    await settle(fixture);
    const reader = fixture.componentInstance;
    await reader.setPageMode('double');
    await reader.enterFullPage();
    fixture.detectChanges();
    await reader.navigate(1);
    await settle(fixture);
    expect(reader.visiblePages()).toEqual([1, 2]);
    expect(fixture.nativeElement.querySelectorAll('canvas:not([hidden])')).toHaveLength(2);
    await reader.navigate(1);
    await settle(fixture);
    expect(reader.visiblePages()).toEqual([3]);
    await reader.setPageMode('single');
    expect(reader.pageNumber()).toBe(3);
    await reader.navigate(1);
    fixture.detectChanges();
    expect(reader.stage()).toBe('back');
    expect(reader.canNext()).toBe(false);
    await reader.navigate(1);
    expect(reader.stage()).toBe('back');
    await reader.navigate(-1);
    await settle(fixture);
    expect(reader.pageNumber()).toBe(3);
    await reader.exitFullPage();
    await settle(fixture);
    expect(reader.pageNumber()).toBe(3);
    expect(api.readBook).toHaveBeenCalledTimes(1);
  });

  it('turns sound on only by choice and plays only for a successful navigation, never toggles, mode changes or disabled moves', async () => {
    const fixture = await create();
    books.next(response());
    await settle(fixture);
    const reader = fixture.componentInstance;
    const sound = fixture.debugElement.injector.get(PageTurnSoundService);
    const play = vi.spyOn(sound, 'turn');
    const enable = vi.spyOn(sound, 'enable');
    await reader.enterFullPage();
    fixture.detectChanges();
    expect(reader.soundEnabled()).toBe(false);
    expect(
      fixture.nativeElement.querySelector('.sound-toggle').classList.contains('sound-on'),
    ).toBe(false);
    reader.toggleSound();
    fixture.detectChanges();
    expect(enable).toHaveBeenCalledOnce();
    expect(play).not.toHaveBeenCalled();
    expect(
      fixture.nativeElement.querySelector('.sound-toggle').classList.contains('sound-on'),
    ).toBe(true);
    await reader.navigate(-1);
    expect(play).not.toHaveBeenCalled();
    await reader.setPageMode('double');
    expect(play).not.toHaveBeenCalled();
    await reader.navigate(1);
    await settle(fixture);
    expect(play).toHaveBeenCalledOnce();
    reader.toggleSound();
    await reader.navigate(1);
    await settle(fixture);
    expect(play).toHaveBeenCalledOnce();
    expect(reader.soundEnabled()).toBe(false);
  });

  it('does not play sound for a failed render or advance the stored reading position', async () => {
    const fixture = await create();
    books.next(response());
    await settle(fixture);
    const reader = fixture.componentInstance;
    const play = vi.spyOn(fixture.debugElement.injector.get(PageTurnSoundService), 'turn');
    await reader.enterFullPage();
    await reader.navigate(1);
    await settle(fixture);
    reader.toggleSound();
    page.render.mockImplementationOnce(() => ({
      promise: Promise.reject(new Error('Page unavailable')),
      cancel,
    }));
    await reader.navigate(1);
    await settle(fixture);
    expect(reader.status()).toBe('error');
    expect(reader.pageNumber()).toBe(1);
    expect(play).not.toHaveBeenCalled();
  });

  it('keeps the selected even page when switching single/double and Escape exits only Full Page', async () => {
    const fixture = await create();
    books.next(response());
    await settle(fixture);
    const reader = fixture.componentInstance;
    await reader.navigate(1);
    await settle(fixture);
    expect(reader.pageNumber()).toBe(2);
    await reader.setPageMode('double');
    await settle(fixture);
    expect(reader.visiblePages()).toEqual([1, 2]);
    expect(reader.pageNumber()).toBe(2);
    await reader.setPageMode('single');
    await settle(fixture);
    expect(reader.pageNumber()).toBe(2);
    await reader.enterFullPage();
    const event = new Event('cancel', { cancelable: true });
    reader.onCancel(event);
    await settle(fixture);
    expect(event.defaultPrevented).toBe(true);
    expect(reader.fullPage()).toBe(false);
    expect(reader.pageNumber()).toBe(2);
    expect((fixture.nativeElement.querySelector('.reader-dialog') as HTMLDialogElement).open).toBe(
      true,
    );
  });

  it('uses responsive initial defaults without overwriting a user choice', async () => {
    const old = Object.getOwnPropertyDescriptor(window, 'innerWidth')!;
    Object.defineProperty(window, 'innerWidth', { value: 375, configurable: true });
    const mobile = TestBed.createComponent(BookReaderComponent);
    mobile.componentRef.setInput('request', scopes[0]);
    expect(mobile.componentInstance.pageMode()).toBe('single');
    mobile.destroy();
    Object.defineProperty(window, 'innerWidth', { value: 1200, configurable: true });
    const desktop = TestBed.createComponent(BookReaderComponent);
    desktop.componentRef.setInput('request', scopes[0]);
    expect(desktop.componentInstance.pageMode()).toBe('double');
    desktop.componentInstance.pageMode.set('single');
    Object.defineProperty(window, 'innerWidth', { value: 800, configurable: true });
    expect(desktop.componentInstance.pageMode()).toBe('single');
    desktop.destroy();
    Object.defineProperty(window, 'innerWidth', old);
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
