import { HttpEventType, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { API_CONFIG } from '../../../core/configuration/api.config';
import { NotificationService } from '../../../shared/services/notification.service';
import { UploadStatus } from '../models/book.models';
import { BookUploadService } from './book-upload.service';

describe('BookUploadService', () => {
  const pollInterval = 25;
  const maxBytes = 64;
  const pending: UploadStatus = {
    book_upload_id: 41,
    status: 'inprogress',
    message: 'Preparing topics',
  };
  let service: BookUploadService;
  let http: HttpTestingController;
  let notifications: NotificationService;

  function pdf(name = 'book.pdf', contents = '%PDF-1.7'): File {
    return new File([contents], name, { type: 'application/pdf' });
  }

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: API_CONFIG,
          useValue: {
            baseUrl: '/api',
            maxUploadBytes: maxBytes,
            uploadPollIntervalMs: pollInterval,
          },
        },
      ],
    });
    service = TestBed.inject(BookUploadService);
    http = TestBed.inject(HttpTestingController);
    notifications = TestBed.inject(NotificationService);
  });

  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });

  it('rejects non-PDF, empty, and oversized selections before making any request', () => {
    const invalid = [
      new File(['text'], 'book.txt', { type: 'text/plain' }),
      new File(['image'], 'book.pdf', { type: 'image/png' }),
      pdf('empty.pdf', ''),
      pdf('large.pdf', 'x'.repeat(maxBytes + 1)),
    ];
    for (const file of invalid) {
      service.upload(file);
      expect(service.phase()).toBe('error');
      expect(service.busy()).toBe(false);
      expect(service.message()).toBeTruthy();
      expect(service.validationError()).toBeTruthy();
      http.expectNone('/api/upload-file');
    }
    expect(service.receiptId()).toBeNull();
    expect(notifications.items()).toEqual([]);
  });

  it('uploads, polls through inprogress, emits completion once, and stops polling at Success', () => {
    const completed = vi.fn();
    service.completed$.subscribe(completed);
    service.upload(pdf());
    expect(service.phase()).toBe('uploading');
    const upload = http.expectOne('/api/upload-file');
    upload.event({ type: HttpEventType.UploadProgress, loaded: 4, total: 8 });
    expect(service.progress()).toBe(50);
    upload.flush(pending, { status: 202, statusText: 'Accepted' });
    expect(service.phase()).toBe('processing');
    expect(service.receiptId()).toBe(41);
    expect(notifications.items()).toEqual([]);
    http.expectNone('/api/upload-status/41');
    vi.advanceTimersByTime(pollInterval);
    http.expectOne('/api/upload-status/41').flush(pending);
    expect(completed).not.toHaveBeenCalled();
    vi.advanceTimersByTime(pollInterval);
    http
      .expectOne('/api/upload-status/41')
      .flush({ ...pending, status: 'Success', message: 'Book ready' });
    expect(service.phase()).toBe('success');
    expect(service.message()).toBe('Book ready');
    expect(service.busy()).toBe(false);
    expect(completed).toHaveBeenCalledTimes(1);
    expect(notifications.items()).toMatchObject([{ message: 'Book ready', severity: 'success' }]);
    vi.advanceTimersByTime(pollInterval * 10);
    http.expectNone('/api/upload-status/41');
  });

  it('ignores duplicate selections during transfer and processing, and prevents overlapping status requests', () => {
    service.upload(pdf());
    service.upload(pdf('other.pdf'));
    http.expectOne('/api/upload-file').flush(pending, { status: 202, statusText: 'Accepted' });
    service.upload(pdf('other.pdf'));
    expect(service.fileName()).toBe('book.pdf');
    http.expectNone('/api/upload-file');
    vi.advanceTimersByTime(pollInterval);
    const firstPoll = http.expectOne('/api/upload-status/41');
    vi.advanceTimersByTime(pollInterval * 3);
    http.expectNone('/api/upload-status/41');
    firstPoll.flush({ ...pending, status: 'Success' });
    expect(service.busy()).toBe(false);
  });

  it('shows terminal processing failure without emitting a refresh or polling further', () => {
    const completed = vi.fn();
    service.completed$.subscribe(completed);
    service.upload(pdf());
    http.expectOne('/api/upload-file').flush(pending, { status: 202, statusText: 'Accepted' });
    vi.advanceTimersByTime(pollInterval);
    http
      .expectOne('/api/upload-status/41')
      .flush({ ...pending, status: 'failed', message: 'Unreadable PDF' });
    expect(service.phase()).toBe('error');
    expect(service.message()).toBe('Unreadable PDF');
    expect(notifications.items()).toMatchObject([{ message: 'Unreadable PDF', severity: 'error' }]);
    expect(completed).not.toHaveBeenCalled();
    vi.advanceTimersByTime(pollInterval * 3);
    http.expectNone('/api/upload-status/41');
  });

  it('retains a known receipt after status transport failure and resumes it without re-uploading', async () => {
    const completed = vi.fn();
    service.completed$.subscribe(completed);
    service.upload(pdf());
    http.expectOne('/api/upload-file').flush(pending, { status: 202, statusText: 'Accepted' });
    vi.advanceTimersByTime(pollInterval);
    http.expectOne('/api/upload-status/41').error(new ProgressEvent('error'));
    await Promise.resolve();
    expect(service.phase()).toBe('unknown');
    expect(service.receiptId()).toBe(41);
    expect(service.busy()).toBe(true);
    expect(service.message()).toContain('server may still be processing');
    expect(notifications.items()).toEqual([]);
    service.upload(pdf('same.pdf'));
    http.expectNone('/api/upload-file');
    service.retryStatus();
    // Duplicate retries while a check is active also do not issue another request.
    service.retryStatus();
    http.expectOne('/api/upload-status/41').flush(pending);
    vi.advanceTimersByTime(pollInterval);
    http.expectOne('/api/upload-status/41').flush({ ...pending, status: 'Success' });
    expect(service.phase()).toBe('success');
    expect(completed).toHaveBeenCalledTimes(1);
    http.expectNone('/api/upload-file');
  });

  it('shows upload HTTP failure and allows selecting the same file again', async () => {
    const file = pdf();
    service.upload(file);
    http
      .expectOne('/api/upload-file')
      .flush({ message: 'Upload queue is full' }, { status: 503, statusText: 'Unavailable' });
    await Promise.resolve();
    expect(service.phase()).toBe('error');
    expect(service.message()).toBe('Upload queue is full');
    expect(notifications.items()).toMatchObject([
      { message: 'Upload queue is full', severity: 'error' },
    ]);
    expect(service.receiptId()).toBeNull();
    service.upload(file);
    http
      .expectOne('/api/upload-file')
      .flush({ ...pending, status: 'Success' }, { status: 202, statusText: 'Accepted' });
    expect(service.phase()).toBe('success');
  });
});
