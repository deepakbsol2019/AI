import { HttpEventType, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { API_CONFIG } from '../../../core/configuration/api.config';
import { NotificationService } from '../../../shared/services/notification.service';
import { UploadStatus } from '../models/book.models';
import { BookUploadService } from './book-upload.service';
import { BACKGROUND_AUTH_REQUEST } from '../../user/security/background-auth.context';
import { AuthBoundaryService } from '../../user/services/auth-boundary.service';

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

  it('rejects empty and oversized selections before making any request', () => {
    const invalid = [pdf('empty.pdf', ''), pdf('large.pdf', 'x'.repeat(maxBytes + 1))];
    for (const file of invalid) {
      service.upload(file);
      expect(service.phase()).toBe('error');
      expect(service.busy()).toBe(false);
      expect(service.message()).toBeTruthy();
      expect(service.validationError()).toBeTruthy();
      http.expectNone('/api/upload-file');
    }
    expect(service.receiptId()).toBeNull();
    expect(notifications.items()).toHaveLength(invalid.length);
    expect(notifications.items().every((item) => item.severity === 'error')).toBe(true);
  });

  it('lets the backend determine content type instead of trusting a declared MIME or extension', async () => {
    service.upload(new File(['not a PDF'], 'document.txt', { type: 'text/plain' }));
    const request = http.expectOne('/api/upload-file');
    request.flush(
      { message: 'Only PDF documents are supported.' },
      { status: 415, statusText: 'Unsupported Media Type' },
    );
    await Promise.resolve();
    expect(service.phase()).toBe('error');
    expect(service.message()).toContain('Only PDF');
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
    expect(notifications.items()).toMatchObject([
      {
        severity: 'info',
        action: { label: 'View Upload Status', url: '/user/uploads?uploadId=41' },
      },
    ]);
    http.expectNone('/api/upload-status/41');
    vi.advanceTimersByTime(pollInterval);
    const firstStatus = http.expectOne('/api/upload-status/41');
    expect(firstStatus.request.context.get(BACKGROUND_AUTH_REQUEST)).toBe(false);
    firstStatus.flush(pending);
    expect(completed).not.toHaveBeenCalled();
    vi.advanceTimersByTime(pollInterval);
    const automaticStatus = http.expectOne('/api/upload-status/41');
    expect(automaticStatus.request.context.get(BACKGROUND_AUTH_REQUEST)).toBe(true);
    automaticStatus.flush({ ...pending, status: 'Success', message: 'Book ready' });
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
    expect(service.busy()).toBe(false);
    expect(service.message()).toContain('server may still be processing');
    expect(notifications.items()).toMatchObject([
      {
        severity: 'warning',
        action: { label: 'View Upload Status', url: '/user/uploads?uploadId=41' },
      },
    ]);
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

  it('allows another upload after interrupted polling while keeping the previous server receipt recoverable', async () => {
    service.upload(pdf());
    http.expectOne('/api/upload-file').flush(pending, { status: 202, statusText: 'Accepted' });
    vi.advanceTimersByTime(pollInterval);
    http.expectOne('/api/upload-status/41').error(new ProgressEvent('error'));
    await Promise.resolve();
    expect(service.busy()).toBe(false);
    service.upload(pdf('next.pdf'));
    http
      .expectOne('/api/upload-file')
      .flush(
        { book_upload_id: 42, status: 'Success', message: 'Next book ready' },
        { status: 202, statusText: 'Accepted' },
      );
    expect(service.receiptId()).toBe(42);
    expect(service.phase()).toBe('success');
    expect(
      notifications.items().some((item) => item.action?.url === '/user/uploads?uploadId=41'),
    ).toBe(true);
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
  it('cancels an in-flight browser transfer and clears its private state at an auth boundary', () => {
    service.upload(pdf('private-user-book.pdf'));
    const transfer = http.expectOne('/api/upload-file');
    transfer.event({ type: HttpEventType.UploadProgress, loaded: 4, total: 8 });
    TestBed.inject(AuthBoundaryService).reset();
    expect(transfer.cancelled).toBe(true);
    expect(service.phase()).toBe('idle');
    expect(service.busy()).toBe(false);
    expect(service.fileName()).toBe('');
    expect(service.message()).toBe('');
    expect(service.receiptId()).toBeNull();
    expect(service.progress()).toBeNull();
    vi.advanceTimersByTime(pollInterval * 3);
    http.expectNone('/api/upload-status/41');
    service.upload(pdf('new-user-book.pdf'));
    http.expectOne('/api/upload-file').flush({ ...pending, status: 'Success' });
    expect(service.fileName()).toBe('new-user-book.pdf');
    expect(service.phase()).toBe('success');
  });
  it('auto-hides the background acceptance notice after fifteen seconds without stopping status work', () => {
    service.upload(pdf());
    http
      .expectOne('/api/upload-file')
      .flush({ ...pending, state: 'PENDING' }, { status: 202, statusText: 'Accepted' });
    expect(notifications.items()[0].message).toContain('Upload ID: 41');
    expect(notifications.items()[0].message).toContain('processed in the background');
    expect(service.phase()).toBe('processing');
    vi.advanceTimersByTime(15_000);
    expect(notifications.items()).toEqual([]);
    expect(service.phase()).toBe('processing');
    const stillRunning = http.expectOne('/api/upload-status/41');
    expect(stillRunning.cancelled).toBe(false);
    stillRunning.flush({ ...pending, state: 'COMPLETED', status: 'Success' });
    expect(service.phase()).toBe('success');
  });
  it('dismisses the notice without cancelling the accepted background upload', () => {
    service.upload(pdf());
    http.expectOne('/api/upload-file').flush(pending, { status: 202, statusText: 'Accepted' });
    notifications.dismiss(notifications.items()[0].id);
    expect(service.receiptId()).toBe(41);
    expect(service.phase()).toBe('processing');
    vi.advanceTimersByTime(pollInterval);
    http.expectOne('/api/upload-status/41').flush({
      ...pending,
      state: 'FAILED',
      failureReason: 'Actual worker failure',
      status: 'failed',
    });
    expect(service.message()).toBe('Actual worker failure');
  });
});
