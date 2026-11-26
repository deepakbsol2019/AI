import { HttpEventType } from '@angular/common/http';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  exhaustMap,
  filter,
  map,
  Observable,
  of,
  Subject,
  switchMap,
  takeUntil,
  takeWhile,
  tap,
  timer,
} from 'rxjs';
import { API_CONFIG } from '../../../core/configuration/api.config';
import { apiErrorMessage } from '../../../shared/utils/api-error';
import { NotificationService } from '../../../shared/services/notification.service';
import { BooksApiService } from '../data-access/books-api.service';
import { UploadStatus, uploadState } from '../models/book.models';
import { AuthBoundaryService } from '../../user/services/auth-boundary.service';
import { BackgroundAuthenticationPausedError } from '../../user/security/background-auth.context';

type UploadPhase = 'idle' | 'uploading' | 'processing' | 'unknown' | 'success' | 'error';

/** The server accepts a PDF first, then processes it asynchronously. */
@Injectable({ providedIn: 'root' })
export class BookUploadService {
  private readonly api = inject(BooksApiService);
  private readonly notifications = inject(NotificationService);
  private readonly config = inject(API_CONFIG);
  private readonly destroyRef = inject(DestroyRef);
  private readonly completedSubject = new Subject<void>();
  private readonly stopWork = new Subject<void>();
  private workVersion = 0;
  private acceptedNotification: number | null = null;
  readonly completed$ = this.completedSubject.asObservable();
  readonly phase = signal<UploadPhase>('idle');
  readonly busy = computed(() => ['uploading', 'processing', 'unknown'].includes(this.phase()));
  readonly progress = signal<number | null>(null);
  readonly fileName = signal('');
  readonly message = signal('');
  readonly receiptId = signal<number | null>(null);
  readonly validationError = signal<string | null>(null);

  constructor() {
    inject(AuthBoundaryService)
      .resets.pipe(takeUntilDestroyed())
      .subscribe(() => {
        this.workVersion++;
        this.dismissAcceptedNotification();
        this.stopWork.next();
        this.receiptId.set(null);
        this.fileName.set('');
        this.message.set('');
        this.validationError.set(null);
        this.progress.set(null);
        this.phase.set('idle');
      });
    this.destroyRef.onDestroy(() => {
      this.stopWork.complete();
      this.completedSubject.complete();
    });
  }

  upload(file: File): void {
    if (this.busy()) return;
    const version = ++this.workVersion;
    this.validationError.set(null);
    this.receiptId.set(null);
    this.fileName.set(file.name);
    this.progress.set(null);
    // File type is decided from content by the backend; a browser MIME/extension is only a hint.
    if (file.size === 0 || file.size > this.config.maxUploadBytes) {
      this.validationError.set('Choose a non-empty PDF of 100 MiB or less.');
      this.fail('Choose a non-empty PDF of 100 MiB or less.');
      return;
    }
    this.phase.set('uploading');
    this.message.set('Uploading your document…');
    this.api
      .upload(file)
      .pipe(
        tap((event) => {
          if (event.type === HttpEventType.UploadProgress && event.total) {
            this.progress.set(Math.round((event.loaded / event.total) * 100));
            if (event.loaded === event.total)
              this.message.set('PDF transferred. Waiting for the upload receipt…');
          }
        }),
        filter((event) => event.type === HttpEventType.Response),
        map((event) => {
          if (!event.body) throw new Error('The server returned an empty upload receipt.');
          return event.body;
        }),
        tap((receipt) => {
          if (!Number.isSafeInteger(receipt.book_upload_id) || receipt.book_upload_id <= 0)
            throw new Error('The server returned an invalid Upload ID.');
          this.receiptId.set(receipt.book_upload_id);
          this.fileName.set(receipt.fileName || file.name);
          if (!['COMPLETED', 'FAILED'].includes(uploadState(receipt))) {
            this.dismissAcceptedNotification();
            this.acceptedNotification = this.notifications.show(
              `Your file is being processed in the background. Please check your upload status after some time. Upload ID: ${receipt.book_upload_id}.`,
              'info',
              {
                label: 'View Upload Status',
                url: `/user/uploads?uploadId=${receipt.book_upload_id}`,
              },
            );
          }
        }),
        switchMap((receipt) => this.followStatus(receipt, true)),
        takeUntil(this.stopWork),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (receipt) => this.updateStatus(receipt),
        error: (error: unknown) => void this.handleError(error, version),
      });
  }

  retryStatus(): void {
    const id = this.receiptId();
    if (id === null || this.phase() !== 'unknown') return;
    const version = ++this.workVersion;
    this.phase.set('processing');
    this.message.set('Checking the existing upload…');
    this.api
      .uploadStatus(id)
      .pipe(
        switchMap((receipt) => this.followStatus(receipt, false)),
        takeUntil(this.stopWork),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (receipt) => this.updateStatus(receipt),
        error: (error: unknown) => void this.handleError(error, version),
      });
  }

  private followStatus(
    receipt: UploadStatus,
    firstPollIsForeground: boolean,
  ): Observable<UploadStatus> {
    if (['COMPLETED', 'FAILED'].includes(uploadState(receipt))) return of(receipt);
    this.phase.set('processing');
    this.message.set(receipt.message || 'Processing your book and preparing topic PDFs…');
    // Exhaust avoids overlapping requests when one status check takes longer than the interval.
    return timer(this.config.uploadPollIntervalMs, this.config.uploadPollIntervalMs).pipe(
      exhaustMap((tick) =>
        this.api.uploadStatus(receipt.book_upload_id, {
          background: !firstPollIsForeground || tick > 0,
        }),
      ),
      takeWhile((status) => !['COMPLETED', 'FAILED'].includes(uploadState(status)), true),
    );
  }

  private updateStatus(receipt: UploadStatus): void {
    if (uploadState(receipt) === 'COMPLETED') {
      this.dismissAcceptedNotification();
      this.phase.set('success');
      this.message.set(receipt.message || 'Your book is ready in the library.');
      this.notifications.show(this.message(), 'success');
      this.completedSubject.next();
    } else if (uploadState(receipt) === 'FAILED') {
      this.dismissAcceptedNotification();
      this.fail(
        receipt.failureReason || receipt.message || 'The server could not process this PDF.',
      );
      this.notifications.show(this.message(), 'error');
    } else {
      this.message.set(receipt.message || 'Processing your book…');
    }
  }

  private async handleError(error: unknown, version: number): Promise<void> {
    const message = await apiErrorMessage(error);
    if (version !== this.workVersion) return;
    if (this.receiptId() !== null) {
      this.phase.set('unknown');
      this.message.set(
        error instanceof BackgroundAuthenticationPausedError
          ? 'Automatic status checks are paused. Select Resume status check to verify your sign-in and continue. The server may still be processing your PDF.'
          : `Status check interrupted. The server may still be processing your PDF. ${message}`,
      );
    } else {
      this.fail(message);
      this.notifications.show(message, 'error');
    }
  }

  private fail(message: string): void {
    this.phase.set('error');
    this.message.set(message);
  }

  private dismissAcceptedNotification(): void {
    if (this.acceptedNotification !== null) this.notifications.dismiss(this.acceptedNotification);
    this.acceptedNotification = null;
  }
}
