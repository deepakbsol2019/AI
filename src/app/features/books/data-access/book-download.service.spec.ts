import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { NotificationService } from '../../../shared/services/notification.service';
import { Book, BookTopic } from '../models/book.models';
import { BookDownloadService } from './book-download.service';

const topic: BookTopic = {
  topicId: 91,
  topicName: 'Collections',
  topicDetails: 'Data structures',
  startPage: 10,
  endPage: 20,
  printedStartPage: 1,
  printedEndPage: 11,
  createdAt: null,
  updatedAt: null,
};
const book: Book = {
  bookId: 12,
  bookName: 'Effective Java',
  bookType: 'Unknown',
  authorName: 'Joshua Bloch',
  bookDetails: 'Programming',
  totalPages: 400,
  bookUploadId: 2,
  createdAt: null,
  updatedAt: null,
  authorDetails: null,
  topicCount: 1,
  topics: [topic],
};
const second: BookTopic = { ...topic, topicId: 92, topicName: 'Generics' };
const third: BookTopic = { ...topic, topicId: 93, topicName: 'Concurrency' };
const multiTopicBook: Book = { ...book, topics: [topic, second, third], topicCount: 3 };

describe('BookDownloadService', () => {
  let service: BookDownloadService;
  let http: HttpTestingController;
  let notifications: NotificationService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(BookDownloadService);
    http = TestBed.inject(HttpTestingController);
    notifications = TestBed.inject(NotificationService);
    vi.stubGlobal(
      'URL',
      class extends URL {
        static override createObjectURL = vi.fn(() => 'blob:book-test');
        static override revokeObjectURL = vi.fn();
      },
    );
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    vi.useFakeTimers();
  });

  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('deduplicates a full-book download across callers while allowing a concurrent topic', async () => {
    service.downloadBook(book);
    service.downloadBook(book);
    service.downloadTopic(book, topic);
    expect(service.isBookDownloading(12)).toBe(true);
    expect(service.isTopicDownloading(12, 91)).toBe(true);
    expect(service.isAnyTopicDownloading(12)).toBe(true);
    expect(service.isSelectedTopicsDownloading(12)).toBe(false);
    const full = http.expectOne('/api/download/book-download?bookId=12');
    const selected = http.expectOne('/api/download/book-topic-download?bookId=12&topicIds=91');
    expect(notifications.items()).toEqual([]);
    full.flush(new Blob(['%PDF'], { type: 'application/pdf' }), {
      headers: { 'Content-Disposition': "attachment; filename*=UTF-8''Server%20book.pdf" },
    });
    await Promise.resolve();
    expect(service.isBookDownloading(12)).toBe(false);
    expect(service.isTopicDownloading(12, 91)).toBe(true);
    expect(service.message()).toBe('Download ready: Server book.pdf');
    expect(notifications.items()).toMatchObject([
      { message: 'Download ready: Server book.pdf', severity: 'success' },
    ]);
    selected.flush(new Blob(['%PDF'], { type: 'application/pdf' }));
    await Promise.resolve();
    expect(service.isTopicDownloading(12, 91)).toBe(false);
    expect(service.isAnyTopicDownloading(12)).toBe(false);
    expect(URL.createObjectURL).toHaveBeenCalledTimes(2);
    expect(notifications.items()).toHaveLength(2);
    expect(document.querySelector('a[download]')).toBeNull();
    vi.advanceTimersByTime(1000);
    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(2);
  });

  it('extracts a JSON error delivered as a Blob and allows retry', async () => {
    service.downloadBook(book);
    const errorBlob = new Blob(['{"detail":"Saved topic PDF is missing."}'], {
      type: 'application/problem+json',
    });
    // jsdom versions may lack Blob.text even though browsers support it.
    Object.defineProperty(errorBlob, 'text', {
      value: async () => '{"detail":"Saved topic PDF is missing."}',
    });
    http
      .expectOne('/api/download/book-download?bookId=12')
      .flush(errorBlob, { status: 409, statusText: 'Conflict' });
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    expect(service.error()).toBe('Saved topic PDF is missing.');
    expect(notifications.items()).toMatchObject([
      { message: 'Saved topic PDF is missing.', severity: 'error' },
    ]);
    expect(service.isBookDownloading(12)).toBe(false);
    expect(URL.createObjectURL).not.toHaveBeenCalled();
    service.downloadBook(book);
    http
      .expectOne('/api/download/book-download?bookId=12')
      .flush(new Blob(['pdf'], { type: 'application/pdf' }));
    await Promise.resolve();
    expect(service.error()).toBeNull();
  });

  it('locks selected-topic downloads per book even when selection changes while permitting other books and full books', async () => {
    const selection = Object.freeze([second, topic]);
    service.downloadSelectedTopics(multiTopicBook, selection);
    service.downloadSelectedTopics(multiTopicBook, [topic, third]);
    service.downloadTopic(multiTopicBook, third);
    expect(service.isSelectedTopicsDownloading(12)).toBe(true);
    expect(service.isAnyTopicDownloading(12)).toBe(true);
    expect(service.isAnyTopicDownloading(13)).toBe(false);
    const selected = http.expectOne(
      '/api/download/book-topic-download?bookId=12&topicIds=92&topicIds=91',
    );
    http.expectNone('/api/download/book-topic-download?bookId=12&topicIds=91&topicIds=93');
    http.expectNone('/api/download/book-topic-download?bookId=12&topicIds=93');
    service.downloadBook(multiTopicBook);
    const full = http.expectOne('/api/download/book-download?bookId=12');
    const anotherTopics = [
      { ...topic, topicId: 201 },
      { ...third, topicId: 203 },
    ];
    const anotherBook: Book = {
      ...multiTopicBook,
      bookId: 13,
      topics: anotherTopics,
      topicCount: 2,
    };
    service.downloadSelectedTopics(anotherBook, anotherTopics);
    const unrelated = http.expectOne(
      '/api/download/book-topic-download?bookId=13&topicIds=201&topicIds=203',
    );
    selected.flush(new Blob(['%PDF selected only'], { type: 'application/pdf' }), {
      headers: { 'Content-Disposition': "attachment; filename*=UTF-8''Server%20selected.pdf" },
    });
    await Promise.resolve();
    expect(service.isSelectedTopicsDownloading(12)).toBe(false);
    expect(service.isAnyTopicDownloading(12)).toBe(false);
    expect(service.isBookDownloading(12)).toBe(true);
    expect(service.isSelectedTopicsDownloading(13)).toBe(true);
    expect(service.message()).toBe('Download ready: Server selected.pdf');
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1);
    expect(HTMLAnchorElement.prototype.click).toHaveBeenCalledTimes(1);
    full.flush(new Blob(['%PDF full'], { type: 'application/pdf' }));
    unrelated.flush(new Blob(['%PDF other selected'], { type: 'application/pdf' }));
    await Promise.resolve();
    vi.advanceTimersByTime(1000);
    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(3);
    expect(selection).toEqual([second, topic]);
  });

  it('releases bulk busy state after a Blob error and retries without modifying selection', async () => {
    const selection = Object.freeze([topic, second]);
    service.downloadSelectedTopics(multiTopicBook, selection);
    const errorBlob = new Blob(['{"detail":"A selected topic PDF is missing."}'], {
      type: 'application/problem+json',
    });
    Object.defineProperty(errorBlob, 'text', {
      value: async () => '{"detail":"A selected topic PDF is missing."}',
    });
    http
      .expectOne('/api/download/book-topic-download?bookId=12&topicIds=91&topicIds=92')
      .flush(errorBlob, { status: 409, statusText: 'Conflict' });
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    expect(service.error()).toBe('A selected topic PDF is missing.');
    expect(service.isSelectedTopicsDownloading(12)).toBe(false);
    expect(service.isAnyTopicDownloading(12)).toBe(false);
    expect(URL.createObjectURL).not.toHaveBeenCalled();
    expect(selection).toEqual([topic, second]);
    service.downloadSelectedTopics(multiTopicBook, selection);
    http
      .expectOne('/api/download/book-topic-download?bookId=12&topicIds=91&topicIds=92')
      .flush(new Blob(['%PDF retry'], { type: 'application/pdf' }));
    await Promise.resolve();
    expect(service.error()).toBeNull();
    expect(service.message()).toBe('Download ready: Effective Java-selected-topics.pdf');
    expect(service.isSelectedTopicsDownloading(12)).toBe(false);
  });

  it('rejects insufficient, invalid or foreign selections before sending a request', () => {
    const foreign: BookTopic = { ...topic, topicId: 200 };
    for (const selection of [
      [],
      [topic],
      [topic, topic],
      [topic, foreign],
      [topic, { ...second, topicId: 0 }],
      [topic, { ...second, topicId: Number.NaN }],
    ]) {
      service.downloadSelectedTopics(multiTopicBook, selection);
      expect(service.error()).toBe('Choose at least two valid topics from this book.');
      expect(service.isAnyTopicDownloading(12)).toBe(false);
    }
    service.downloadSelectedTopics({ ...multiTopicBook, bookId: 0 }, [topic, second]);
    http.expectNone((request) => request.url.includes('/download/'));
  });

  it('sends each selected ID once in selection order and waits for an existing individual download', async () => {
    service.downloadTopic(multiTopicBook, third);
    service.downloadSelectedTopics(multiTopicBook, [second, topic, second]);
    http.expectNone('/api/download/book-topic-download?bookId=12&topicIds=92&topicIds=91');
    http
      .expectOne('/api/download/book-topic-download?bookId=12&topicIds=93')
      .flush(new Blob(['%PDF individual'], { type: 'application/pdf' }));
    await Promise.resolve();
    service.downloadSelectedTopics(multiTopicBook, [second, topic, second]);
    const selected = http.expectOne(
      '/api/download/book-topic-download?bookId=12&topicIds=92&topicIds=91',
    );
    expect(selected.request.params.getAll('topicIds')).toEqual(['92', '91']);
    selected.flush(new Blob(['%PDF selected'], { type: 'application/pdf' }));
    await Promise.resolve();
    expect(service.isAnyTopicDownloading(12)).toBe(false);
  });
});
