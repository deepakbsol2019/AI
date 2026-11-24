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
  takeWhile,
  tap,
  timer,
} from 'rxjs';
import { API_CONFIG } from '../../../core/configuration/api.config';
import { apiErrorMessage } from '../../../shared/utils/api-error';
import { BooksApiService } from '../data-access/books-api.service';
import { UploadStatus } from '../models/book.models';

type UploadPhase = 'idle' | 'uploading' | 'processing' | 'unknown' | 'success' | 'error';

/** The server accepts a PDF first, then processes it asynchronously. */
@Injectable({ providedIn: 'root' })
export class BookUploadService {
  private readonly api = inject(BooksApiService);
  private readonly config = inject(API_CONFIG);
  private readonly destroyRef = inject(DestroyRef);
  private readonly completedSubject = new Subject<void>();
  readonly completed$ = this.completedSubject.asObservable();
  readonly phase = signal<UploadPhase>('idle');
  readonly busy = computed(() => ['uploading', 'processing', 'unknown'].includes(this.phase()));
  readonly progress = signal<number | null>(null);
  readonly fileName = signal('');
  readonly message = signal('');
  readonly receiptId = signal<number | null>(null);

  upload(file: File): void {
    if (this.busy()) return;
    this.receiptId.set(null);
    this.fileName.set(file.name);
    this.progress.set(null);
    // MIME is advisory; the backend validates PDF structure and actual content.
    if (!/\.pdf$/i.test(file.name) || (file.type && file.type !== 'application/pdf')) {
      this.fail('Choose a PDF file with a .pdf extension.');
      return;
    }
    if (file.size === 0 || file.size > this.config.maxUploadBytes) {
      this.fail('Choose a non-empty PDF of 100 MiB or less.');
      return;
    }
    this.phase.set('uploading');
    this.message.set('Uploading your PDF…');
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
        tap((receipt) => this.receiptId.set(receipt.book_upload_id)),
        switchMap((receipt) => this.followStatus(receipt)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (receipt) => this.updateStatus(receipt),
        error: (error: unknown) => void this.handleError(error),
      });
  }

  retryStatus(): void {
    const id = this.receiptId();
    if (id === null || this.phase() !== 'unknown') return;
    this.phase.set('processing');
    this.message.set('Checking the existing upload…');
    this.api
      .uploadStatus(id)
      .pipe(
        switchMap((receipt) => this.followStatus(receipt)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (receipt) => this.updateStatus(receipt),
        error: (error: unknown) => void this.handleError(error),
      });
  }

  private followStatus(receipt: UploadStatus): Observable<UploadStatus> {
    if (receipt.status !== 'inprogress') return of(receipt);
    this.phase.set('processing');
    this.message.set(receipt.message || 'Processing your book and preparing topic PDFs…');
    // Exhaust avoids overlapping requests when one status check takes longer than the interval.
    return timer(this.config.uploadPollIntervalMs, this.config.uploadPollIntervalMs).pipe(
      exhaustMap(() => this.api.uploadStatus(receipt.book_upload_id)),
      takeWhile((status) => status.status === 'inprogress', true),
    );
  }

  private updateStatus(receipt: UploadStatus): void {
    if (receipt.status === 'Success') {
      this.phase.set('success');
      this.message.set(receipt.message || 'Your book is ready in the library.');
      this.completedSubject.next();
    } else if (receipt.status === 'failed') {
      this.fail(receipt.message || 'The server could not process this PDF.');
    } else {
      this.message.set(receipt.message || 'Processing your book…');
    }
  }

  private async handleError(error: unknown): Promise<void> {
    const message = await apiErrorMessage(error);
    if (this.receiptId() !== null) {
      this.phase.set('unknown');
      this.message.set(
        `Status check interrupted. The server may still be processing your PDF. ${message}`,
      );
    } else {
      this.fail(message);
    }
  }

  private fail(message: string): void {
    this.phase.set('error');
    this.message.set(message);
  }
}
