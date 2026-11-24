import {
  Component,
  ComponentRef,
  DestroyRef,
  inject,
  signal,
  viewChild,
  ViewContainerRef,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, from, map, startWith, Subject, switchMap } from 'rxjs';
import { ApplicationLayoutComponent } from '../../../../layout/application-layout/application-layout.component';
import { IconComponent } from '../../../../shared/ui/icon.component';
import { apiErrorMessage } from '../../../../shared/utils/api-error';
import { BookSearchComponent } from '../../components/book-search/book-search.component';
import { BookTableComponent } from '../../components/book-table/book-table.component';
import { BooksApiService } from '../../data-access/books-api.service';
import { BookDownloadService } from '../../data-access/book-download.service';
import { Book, BookSearchCriteria, BookSearchResponse } from '../../models/book.models';
import { BookUploadService } from '../../services/book-upload.service';
import type { ReaderRequest } from '../../models/reader.models';
import { NotificationService } from '../../../../shared/services/notification.service';
import type { BookTopicDescriptionComponent } from '../../components/book-topic-description/book-topic-description.component';
import { mergeMetadataSave } from '../../models/metadata-save';
import type { MetadataSave } from '../../models/metadata-save';

const DEFAULT_PAGE_SIZE = 5;

@Component({
  selector: 'app-home',
  imports: [ApplicationLayoutComponent, IconComponent, BookSearchComponent, BookTableComponent],
  templateUrl: './home.component.html',
  styleUrl: './home.component.css',
})
export class HomeComponent {
  private readonly api = inject(BooksApiService);
  private readonly destroyRef = inject(DestroyRef);
  readonly downloads = inject(BookDownloadService);
  readonly upload = inject(BookUploadService);
  private readonly notifications = inject(NotificationService);
  readonly searchMinimized = signal(true);
  readonly collectionMinimized = signal(false);
  readonly results = signal<BookSearchResponse | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly dialogLoading = signal(false);
  readonly dialogError = signal<string | null>(null);
  readonly pageSize = signal(DEFAULT_PAGE_SIZE);
  readonly activeFilterCount = signal(0);
  private criteria: BookSearchCriteria = {};
  private currentPage = 0;
  private readonly requests = new Subject<BookSearchCriteria>();
  private readonly dialogHost = viewChild.required('dialogHost', { read: ViewContainerRef });
  private activeDialog: ComponentRef<BookTopicDescriptionComponent> | null = null;
  private dialogSelection = 0;
  private activeReader: ComponentRef<unknown> | null = null;
  private readerSelection = 0;

  constructor() {
    this.requests
      .pipe(
        startWith({ size: DEFAULT_PAGE_SIZE } as BookSearchCriteria),
        switchMap((criteria) => {
          this.loading.set(true);
          this.error.set(null);
          return this.api.search(criteria).pipe(
            map((data) => ({ data, error: null })),
            catchError((error: unknown) =>
              from(apiErrorMessage(error)).pipe(map((message) => ({ data: null, error: message }))),
            ),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => {
        this.loading.set(false);
        this.error.set(result.error);
        if (result.data) {
          this.results.set(result.data);
          const popup = this.activeDialog;
          if (popup) {
            const refreshed = result.data.books.find(
              (book) => book.bookId === popup.instance.book().bookId,
            );
            if (refreshed) popup.setInput('book', refreshed);
          }
          this.currentPage = result.data.page;
          this.pageSize.set(result.data.size);
          if (result.data.page > 0 && result.data.page >= result.data.totalPages) {
            this.currentPage = Math.max(0, result.data.totalPages - 1);
            this.reload();
          }
        }
      });
    this.upload.completed$.pipe(takeUntilDestroyed()).subscribe(() => this.reload());
    this.destroyRef.onDestroy(() => {
      this.dialogSelection++;
      this.readerSelection++;
      // Release the nested reader's scroll lock before its originating popup.
      this.activeReader?.destroy();
      this.activeDialog?.destroy();
    });
  }

  search(criteria: BookSearchCriteria): void {
    this.criteria = criteria;
    this.activeFilterCount.set(
      Object.keys(criteria).filter((key) => key !== 'page' && key !== 'size').length,
    );
    this.currentPage = 0;
    this.reload();
  }

  reset(): void {
    this.criteria = {};
    this.activeFilterCount.set(0);
    this.currentPage = 0;
    this.reload();
  }

  reload(): void {
    const request = { ...this.criteria };
    if (this.currentPage > 0) request.page = this.currentPage;
    request.size = this.pageSize();
    this.requests.next(request);
  }

  changePage(page: number): void {
    if (this.loading() || page < 0 || page >= (this.results()?.totalPages ?? 0)) return;
    this.currentPage = page;
    this.reload();
  }

  changeSize(event: Event): void {
    this.pageSize.set(Number((event.target as HTMLSelectElement).value));
    this.currentPage = 0;
    this.reload();
  }

  onMetadataSaved(save: MetadataSave): void {
    const book = save.book;
    this.results.update((current) =>
      current
        ? {
            ...current,
            books: current.books.map((stored) =>
              stored.bookId === book.bookId ? mergeMetadataSave(stored, save) : stored,
            ),
          }
        : current,
    );
    if (this.activeDialog?.instance.book().bookId === book.bookId) {
      this.activeDialog.setInput(
        'book',
        mergeMetadataSave(this.activeDialog.instance.book(), save),
      );
    }
    // Reapply the server's full-value matching and stable book ordering after a confirmed edit.
    this.reload();
  }

  async viewBook(book: Book): Promise<void> {
    await this.openReader({ mode: 'book', book, topics: [] });
  }

  async viewTopics(book: Book): Promise<void> {
    const selection = ++this.dialogSelection;
    const opener = document.activeElement as HTMLElement | null;
    this.dialogError.set(null);
    this.dialogLoading.set(true);
    try {
      const { BookTopicDescriptionComponent } =
        await import('../../components/book-topic-description/book-topic-description.component');
      if (this.destroyRef.destroyed || selection !== this.dialogSelection) return;
      this.activeDialog?.destroy();
      const ref = this.dialogHost().createComponent(BookTopicDescriptionComponent);
      this.activeDialog = ref;
      ref.setInput('book', book);
      ref.instance.readRequested.subscribe((request) => void this.openReader(request));
      ref.instance.metadataSaved.subscribe((saved) => this.onMetadataSaved(saved));
      ref.instance.closed.subscribe(() => {
        ref.destroy();
        if (this.activeDialog === ref) this.activeDialog = null;
        if (opener?.isConnected) opener.focus();
      });
    } catch {
      if (selection === this.dialogSelection && !this.destroyRef.destroyed) {
        this.notifications.show(
          'The book topics view could not load. Please try View Topic again.',
          'error',
        );
      }
    } finally {
      if (selection === this.dialogSelection) this.dialogLoading.set(false);
    }
  }

  private async openReader(request: ReaderRequest): Promise<void> {
    if (this.activeReader) return;
    const selection = ++this.readerSelection;
    const opener = document.activeElement as HTMLElement | null;
    try {
      const { BookReaderComponent } =
        await import('../../components/book-reader/book-reader.component');
      if (this.destroyRef.destroyed || selection !== this.readerSelection || this.activeReader)
        return;
      const ref = this.dialogHost().createComponent(BookReaderComponent);
      this.activeReader = ref;
      ref.setInput('request', request);
      ref.instance.closed.subscribe(() => {
        ref.destroy();
        if (this.activeReader === ref) this.activeReader = null;
        // The original list/popup stays mounted, including its filter and ID selection.
        if (opener?.isConnected) opener.focus({ preventScroll: true });
      });
    } catch {
      if (!this.destroyRef.destroyed && selection === this.readerSelection) {
        this.notifications.show('The PDF reader could not load. Please try again.', 'error');
      }
    }
  }
}
