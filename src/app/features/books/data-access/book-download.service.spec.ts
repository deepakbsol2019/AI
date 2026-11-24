import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
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

describe('BookDownloadService', () => {
  let service: BookDownloadService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(BookDownloadService);
    http = TestBed.inject(HttpTestingController);
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
    const full = http.expectOne('/api/download/book-download?bookId=12');
    const selected = http.expectOne('/api/download/book-topic-download?bookId=12&topicIds=91');
    full.flush(new Blob(['%PDF'], { type: 'application/pdf' }), {
      headers: { 'Content-Disposition': "attachment; filename*=UTF-8''Server%20book.pdf" },
    });
    await Promise.resolve();
    expect(service.isBookDownloading(12)).toBe(false);
    expect(service.isTopicDownloading(12, 91)).toBe(true);
    expect(service.message()).toBe('Download ready: Server book.pdf');
    selected.flush(new Blob(['%PDF'], { type: 'application/pdf' }));
    await Promise.resolve();
    expect(service.isTopicDownloading(12, 91)).toBe(false);
    expect(URL.createObjectURL).toHaveBeenCalledTimes(2);
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
    expect(service.isBookDownloading(12)).toBe(false);
    expect(URL.createObjectURL).not.toHaveBeenCalled();
    service.downloadBook(book);
    http
      .expectOne('/api/download/book-download?bookId=12')
      .flush(new Blob(['pdf'], { type: 'application/pdf' }));
    await Promise.resolve();
    expect(service.error()).toBeNull();
  });
});
