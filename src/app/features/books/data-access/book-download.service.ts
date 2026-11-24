import { DOCUMENT } from '@angular/common';
import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, finalize, from, ignoreElements, Observable, switchMap, tap } from 'rxjs';
import { apiErrorMessage } from '../../../shared/utils/api-error';
import { downloadFilename } from '../../../shared/utils/download-filename';
import { Book, BookTopic } from '../models/book.models';
import { BooksApiService } from './books-api.service';

@Injectable({ providedIn: 'root' })
export class BookDownloadService {
  private readonly api = inject(BooksApiService);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);
  private readonly active = signal<ReadonlySet<string>>(new Set());
  readonly error = signal<string | null>(null);
  readonly message = signal<string | null>(null);

  isBookDownloading(id: number): boolean {
    return this.active().has(`book:${id}`);
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
    this.active.update((current) => new Set([...current, key]));
    this.error.set(null);
    this.message.set(null);
    request
      .pipe(
        switchMap((response) => from(this.save(response, fallback))),
        tap((filename) => this.message.set(`Download ready: ${filename}`)),
        catchError((error: unknown) =>
          from(apiErrorMessage(error)).pipe(
            tap((message) => this.error.set(message)),
            ignoreElements(),
          ),
        ),
        finalize(() =>
          this.active.update((current) => {
            const next = new Set(current);
            next.delete(key);
            return next;
          }),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();
  }

  private async save(response: HttpResponse<Blob>, fallback: string): Promise<string> {
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
