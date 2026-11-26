import { HttpEventType, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { BooksApiService } from './books-api.service';
import { UploadStatus } from '../models/book.models';
import { BACKGROUND_AUTH_REQUEST } from '../../user/security/background-auth.context';

describe('BooksApiService — backend contract', () => {
  let api: BooksApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(BooksApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads actual PDFs through inline endpoints without invoking the attachment routes', () => {
    api.readBook(12).subscribe();
    const full = http.expectOne('/api/read/book?bookId=12');
    expect(full.request.responseType).toBe('blob');
    full.flush(new Blob(['%PDF'], { type: 'application/pdf' }));
    api.readTopics(12, [34, 55]).subscribe();
    const selected = http.expectOne('/api/read/topics?bookId=12&topicIds=34&topicIds=55');
    expect(selected.request.params.getAll('topicIds')).toEqual(['34', '55']);
    expect(selected.request.responseType).toBe('blob');
    selected.flush(new Blob(['%PDF'], { type: 'application/pdf' }));
    http.expectNone((request) => request.url.includes('/download/'));
  });

  it('lists books with one GET, no criteria, and no request body', () => {
    api.search().subscribe((response) => expect(response.size).toBe(5));
    const request = http.expectOne('/api/search/books');
    expect(request.request.method).toBe('GET');
    expect(request.request.body).toBeNull();
    expect(request.request.params.keys()).toEqual([]);
    request.flush({ books: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
  });

  it('serializes actual filter names, omits empty values, and preserves page zero', () => {
    api
      .search({
        query: ' Java ',
        bookName: ' ',
        authorName: null,
        topicName: '',
        bookDetailsContains: '100%_safe',
        topicDetailsContains: ' memory ',
        page: 0,
        size: 7,
      })
      .subscribe();
    const request = http.expectOne((req) => req.url === '/api/search/books');
    expect(request.request.params.keys().sort()).toEqual([
      'bookDetailsContains',
      'page',
      'query',
      'size',
      'topicDetailsContains',
    ]);
    expect(request.request.params.get('query')).toBe('Java');
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('size')).toBe('7');
    expect(request.request.params.get('bookDetailsContains')).toBe('100%_safe');
    expect(request.request.params.get('topicDetailsContains')).toBe('memory');
    request.flush({ books: [], page: 0, size: 7, totalElements: 0, totalPages: 0 });
  });

  it('posts only the required file part, lets the browser set multipart headers, and exposes async receipt', () => {
    const file = new File(['%PDF-1.7'], 'book.pdf', { type: 'application/pdf' });
    const receipt: UploadStatus = {
      book_upload_id: 41,
      status: 'inprogress',
      message: 'Processing',
    };
    let received: UploadStatus | null = null;
    api.upload(file).subscribe((event) => {
      if (event.type === HttpEventType.Response) received = event.body;
    });
    const request = http.expectOne('/api/upload-file');
    expect(request.request.method).toBe('POST');
    expect(request.request.reportProgress).toBe(true);
    expect(request.request.headers.has('Content-Type')).toBe(false);
    const body = request.request.body as FormData;
    expect([...body.keys()]).toEqual(['file']);
    expect((body.get('file') as File).name).toBe('book.pdf');
    request.flush(receipt, { status: 202, statusText: 'Accepted' });
    expect(received).toEqual(receipt);
    api.uploadStatus(41).subscribe((status) => expect(status.status).toBe('Success'));
    const foreground = http.expectOne('/api/upload-status/41');
    expect(foreground.request.context.get(BACKGROUND_AUTH_REQUEST)).toBe(false);
    foreground.flush({ ...receipt, status: 'Success' });
    api.uploadStatus(41, { background: true }).subscribe();
    const background = http.expectOne('/api/upload-status/41');
    expect(background.request.context.get(BACKGROUND_AUTH_REQUEST)).toBe(true);
    background.flush(receipt);
  });

  it('uses the whole-book and selected-topics binary routes with exactly one topic ID', () => {
    api
      .downloadBook(12)
      .subscribe((response) =>
        expect(response.headers.get('Content-Disposition')).toContain('book-12'),
      );
    const book = http.expectOne('/api/download/book-download?bookId=12');
    expect(book.request.responseType).toBe('blob');
    book.flush(new Blob(['pdf'], { type: 'application/pdf' }), {
      headers: { 'Content-Disposition': 'attachment; filename="book-12.pdf"' },
    });
    api.downloadTopic(12, 34).subscribe();
    const topic = http.expectOne('/api/download/book-topic-download?bookId=12&topicIds=34');
    expect(topic.request.responseType).toBe('blob');
    expect(topic.request.params.getAll('topicIds')).toEqual(['34']);
    topic.flush(new Blob(['topic'], { type: 'application/pdf' }));
  });

  it('downloads only the requested topic IDs as one response using ordered repeated parameters', () => {
    const ids = Object.freeze([92, 34, 91]);
    const received = vi.fn();
    api.downloadTopics(12, ids).subscribe(received);
    const request = http.expectOne(
      '/api/download/book-topic-download?bookId=12&topicIds=92&topicIds=34&topicIds=91',
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.body).toBeNull();
    expect(request.request.params.keys()).toEqual(['bookId', 'topicIds']);
    expect(request.request.params.getAll('topicIds')).toEqual(['92', '34', '91']);
    expect(request.request.responseType).toBe('blob');
    const pdf = new Blob(['%PDF combined topics'], { type: 'application/pdf' });
    request.flush(pdf, {
      headers: { 'Content-Disposition': 'attachment; filename="book-12-selected-topics.pdf"' },
    });
    expect(received).toHaveBeenCalledTimes(1);
    expect(received.mock.calls[0][0].body).toBe(pdf);
    expect(received.mock.calls[0][0].headers.get('Content-Disposition')).toContain(
      'selected-topics.pdf',
    );
    expect(ids).toEqual([92, 34, 91]);
  });
});
