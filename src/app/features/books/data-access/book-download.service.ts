import { DOCUMENT } from '@angular/common';
import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  catchError,
  finalize,
  from,
  ignoreElements,
  Observable,
  switchMap,
  takeUntil,
  tap,
} from 'rxjs';
import { apiErrorMessage } from '../../../shared/utils/api-error';
import { NotificationService } from '../../../shared/services/notification.service';
import { downloadFilename } from '../../../shared/utils/download-filename';
import { AuthBoundaryService } from '../../user/services/auth-boundary.service';
import { Book, BookTopic } from '../models/book.models';
import { BooksApiService } from './books-api.service';

@Injectable({ providedIn: 'root' })
export class BookDownloadService {
  private readonly api = inject(BooksApiService);
  private readonly notifications = inject(NotificationService);
  private readonly authBoundary = inject(AuthBoundaryService);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);
  private readonly active = signal<ReadonlySet<string>>(new Set());
  private generation = 0;
  readonly error = signal<string | null>(null);
  readonly message = signal<string | null>(null);

  constructor() {
    this.authBoundary.resets
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.reset());
    this.destroyRef.onDestroy(() => this.reset());
  }

  isBookDownloading(id: number): boolean {
    return this.active().has(`book:${id}`);
  }

  isDocumentDownloading(key: string): boolean {
    return this.active().has(`document:${key}`);
  }

  /** CSV/Excel use the same binary response, filename, notification and account boundary as PDF. */
  downloadDocument(key: string, request: Observable<HttpResponse<Blob>>, filename: string): void {
    this.download(`document:${key}`, request, filename);
  }

  isTopicDownloading(bookId: number, topicId: number): boolean {
    return this.active().has(`topic:${bookId}:${topicId}`);
  }

  isSelectedTopicsDownloading(bookId: number): boolean {
    return this.active().has(`selected-topics:${bookId}`);
  }

  isAnyTopicDownloading(bookId: number): boolean {
    const active = this.active();
    return (
      active.has(`selected-topics:${bookId}`) ||
      [...active].some((key) => key.startsWith(`topic:${bookId}:`))
    );
  }

  downloadBook(book: Book): void {
    this.download(
      `book:${book.bookId}`,
      this.api.downloadBook(book.bookId),
      `${book.bookName}.pdf`,
    );
  }

  downloadTopic(book: Book, topic: BookTopic): void {
    if (this.isSelectedTopicsDownloading(book.bookId)) return;
    this.download(
      `topic:${book.bookId}:${topic.topicId}`,
      this.api.downloadTopic(book.bookId, topic.topicId),
      `${topic.topicName}.pdf`,
    );
  }

  downloadSelectedTopics(book: Book, topics: readonly BookTopic[]): void {
    // One bulk operation per book, even if the user's selection subsequently changes.
    if (this.isAnyTopicDownloading(book.bookId)) return;
    const topicIds = [...new Set(topics.map((topic) => topic.topicId))];
    const currentBookIds = new Set(book.topics.map((topic) => topic.topicId));
    if (
      !Number.isSafeInteger(book.bookId) ||
      book.bookId <= 0 ||
      topicIds.length < 2 ||
      topicIds.some((id) => !Number.isSafeInteger(id) || id <= 0 || !currentBookIds.has(id))
    ) {
      this.error.set('Choose at least two valid topics from this book.');
      return;
    }
    this.download(
      `selected-topics:${book.bookId}`,
      this.api.downloadTopics(book.bookId, topicIds),
      `${book.bookName}-selected-topics.pdf`,
    );
  }

  private download(key: string, request: Observable<HttpResponse<Blob>>, fallback: string): void {
    if (this.active().has(key)) return;
    const generation = this.generation;
    this.active.update((current) => new Set([...current, key]));
    this.error.set(null);
    this.message.set(null);
    request
      .pipe(
        switchMap((response) => from(this.save(response, fallback, generation))),
        tap((filename) => {
          if (generation !== this.generation || filename === null) return;
          const message = `Download ready: ${filename}`;
          this.message.set(message);
          this.notifications.show(message, 'success');
        }),
        catchError((error: unknown) =>
          from(apiErrorMessage(error)).pipe(
            tap((message) => {
              if (generation !== this.generation) return;
              this.error.set(message);
              this.notifications.show(message, 'error');
            }),
            ignoreElements(),
          ),
        ),
        finalize(() => {
          if (generation !== this.generation) return;
          this.active.update((current) => {
            const next = new Set(current);
            next.delete(key);
            return next;
          });
        }),
        takeUntil(this.authBoundary.resets),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();
  }

  private reset(): void {
    this.generation++;
    this.active.set(new Set());
    this.error.set(null);
    this.message.set(null);
  }

  private async save(
    response: HttpResponse<Blob>,
    fallback: string,
    generation: number,
  ): Promise<string | null> {
    // A promise cannot be cancelled by RxJS. Recheck the account before any browser/file side effect.
    await Promise.resolve();
    if (generation !== this.generation) return null;
    const blob = response.body;
    if (!blob || blob.size === 0)
      throw new Error('The service returned an empty download. Please try again.');
    if (blob.type.includes('json')) {
      throw new HttpErrorResponse({
        error: blob,
        status: response.status,
        statusText: response.statusText,
      });
    }
    const filename = downloadFilename(response.headers.get('Content-Disposition'), fallback);
    const url = URL.createObjectURL(blob);
    const link = this.document.createElement('a');
    link.href = url;
    link.download = filename;
    link.hidden = true;
    this.document.body.appendChild(link);
    try {
      link.click();
    } finally {
      link.remove();
      // Let the browser begin reading before releasing the temporary URL.
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
    return filename;
  }
}
