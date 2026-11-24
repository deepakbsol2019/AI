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

  downloadBook(book: Book): void {
    this.download(
      `book:${book.bookId}`,
      this.api.downloadBook(book.bookId),
      `${book.bookName}.pdf`,
    );
  }

  downloadTopic(book: Book, topic: BookTopic): void {
    this.download(
      `topic:${book.bookId}:${topic.topicId}`,
      this.api.downloadTopic(book.bookId, topic.topicId),
      `${topic.topicName}.pdf`,
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
