import {
  Component,
  ComponentRef,
  computed,
  DestroyRef,
  inject,
  signal,
  viewChild,
  ViewContainerRef,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, from, map, Observable, startWith, Subject, switchMap, takeUntil } from 'rxjs';
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
import { SpreadsheetApiService } from '../../data-access/spreadsheet-api.service';
import { SpreadsheetFile, SpreadsheetPage } from '../../models/spreadsheet.models';
import { DocumentType, INITIAL_DOCUMENT_TYPE } from '../../models/document-type.config';
import { SpreadsheetTableComponent } from '../../components/spreadsheet-table/spreadsheet-table.component';
import { SpreadsheetViewComponent } from '../../components/spreadsheet-view/spreadsheet-view.component';
import { AuthBoundaryService } from '../../../user/services/auth-boundary.service';

const DEFAULT_PAGE_SIZE = 5;

@Component({
  selector: 'app-home',
  imports: [
    ApplicationLayoutComponent,
    IconComponent,
    BookSearchComponent,
    BookTableComponent,
    SpreadsheetTableComponent,
    SpreadsheetViewComponent,
  ],
  templateUrl: './home.component.html',
  styleUrl: './home.component.css',
})
export class HomeComponent {
  private readonly api = inject(BooksApiService);
  private readonly spreadsheetApi = inject(SpreadsheetApiService);
  private readonly boundary = inject(AuthBoundaryService);
  private readonly destroyRef = inject(DestroyRef);
  readonly downloads = inject(BookDownloadService);
  readonly upload = inject(BookUploadService);
  private readonly notifications = inject(NotificationService);
  readonly searchMinimized = signal(true);
  readonly collectionMinimized = signal(false);
  readonly results = signal<BookSearchResponse | null>(null);
  readonly documentType = signal<DocumentType>(inject(INITIAL_DOCUMENT_TYPE));
  readonly spreadsheetResults = signal<SpreadsheetPage | null>(null);
  readonly selectedSpreadsheet = signal<{ file: SpreadsheetFile; mode: 'file' | 'sheets' } | null>(
    null,
  );
  readonly resultPage = computed(() =>
    this.documentType() === 'pdf'
      ? (this.results()?.page ?? 0)
      : (this.spreadsheetResults()?.number ?? 0),
  );
  readonly resultTotalPages = computed(() =>
    this.documentType() === 'pdf'
      ? (this.results()?.totalPages ?? 0)
      : (this.spreadsheetResults()?.totalPages ?? 0),
  );
  readonly resultTotalElements = computed(() =>
    this.documentType() === 'pdf'
      ? (this.results()?.totalElements ?? 0)
      : (this.spreadsheetResults()?.totalElements ?? 0),
  );
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly dialogLoading = signal(false);
  readonly dialogError = signal<string | null>(null);
  readonly pageSize = signal(DEFAULT_PAGE_SIZE);
  readonly activeFilterCount = signal(0);
  private criteria: BookSearchCriteria = {};
  private draftSearchText: string | undefined;
  private currentPage = 0;
  private readonly requests = new Subject<{
    criteria: BookSearchCriteria;
    background: boolean;
    documentType: DocumentType;
  }>();
  private readonly resetQueries = new Subject<void>();
  private sheetOpener: HTMLElement | null = null;
  private readonly dialogHost = viewChild.required('dialogHost', { read: ViewContainerRef });
  private activeDialog: ComponentRef<BookTopicDescriptionComponent> | null = null;
  private dialogSelection = 0;
  private activeReader: ComponentRef<unknown> | null = null;
  private readerSelection = 0;

  constructor() {
    this.requests
      .pipe(
        startWith({
          criteria: { size: DEFAULT_PAGE_SIZE } as BookSearchCriteria,
          background: false,
          documentType: this.documentType(),
        }),
        switchMap(({ criteria, background, documentType }) => {
          this.loading.set(true);
          this.error.set(null);
          const request: Observable<BookSearchResponse | SpreadsheetPage> =
            documentType === 'pdf'
              ? this.api.search(criteria, { background })
              : this.spreadsheetApi.list(
                  criteria.query || '',
                  criteria.page || 0,
                  criteria.size || DEFAULT_PAGE_SIZE,
                  background,
                );
          return request.pipe(
            map((data) => ({ data, error: null, background, documentType })),
            catchError((error: unknown) =>
              from(apiErrorMessage(error)).pipe(
                map((message) => ({ data: null, error: message, background, documentType })),
              ),
            ),
            takeUntil(this.resetQueries),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => {
        if (result.documentType !== this.documentType()) return;
        this.loading.set(false);
        this.error.set(result.error);
        if (result.data && result.documentType === 'pdf') {
          const data = result.data as BookSearchResponse;
          this.results.set(data);
          const popup = this.activeDialog;
          if (popup) {
            const refreshed = data.books.find(
              (book) => book.bookId === popup.instance.book().bookId,
            );
            if (refreshed) popup.setInput('book', refreshed);
          }
          this.currentPage = data.page;
          this.pageSize.set(data.size);
          if (data.page > 0 && data.page >= data.totalPages) {
            this.currentPage = Math.max(0, data.totalPages - 1);
            this.reload(result.background);
          }
        } else if (result.data) {
          const data = result.data as SpreadsheetPage;
          this.spreadsheetResults.set(data);
          this.currentPage = data.number;
          this.pageSize.set(data.size);
          if (data.number > 0 && data.number >= data.totalPages) {
            this.currentPage = Math.max(0, data.totalPages - 1);
            this.reload(result.background);
          }
        }
      });
    this.boundary.resets.pipe(takeUntilDestroyed()).subscribe(() => {
      this.resetQueries.next();
      this.results.set(null);
      this.spreadsheetResults.set(null);
      this.selectedSpreadsheet.set(null);
      this.error.set(null);
      this.loading.set(false);
      this.currentPage = 0;
      ++this.dialogSelection;
      ++this.readerSelection;
      this.activeReader?.destroy();
      this.activeReader = null;
      this.activeDialog?.destroy();
      this.activeDialog = null;
    });
    this.upload.completed$.pipe(takeUntilDestroyed()).subscribe(() => this.reload(true));
    this.destroyRef.onDestroy(() => {
      this.dialogSelection++;
      this.readerSelection++;
      // Release the nested reader's scroll lock before its originating popup.
      this.activeReader?.destroy();
      this.activeDialog?.destroy();
      this.resetQueries.complete();
    });
  }

  search(criteria: BookSearchCriteria): void {
    this.criteria = criteria;
    this.draftSearchText = criteria.query || '';
    this.activeFilterCount.set(
      this.documentType() === 'pdf'
        ? Object.keys(criteria).filter((key) => key !== 'page' && key !== 'size').length
        : criteria.query?.trim()
          ? 1
          : 0,
    );
    this.currentPage = 0;
    this.reload();
  }

  reset(): void {
    this.criteria = {};
    this.draftSearchText = '';
    this.activeFilterCount.set(0);
    this.currentPage = 0;
    this.reload();
  }

  reload(background = false): void {
    const request = { ...this.criteria };
    if (this.currentPage > 0) request.page = this.currentPage;
    request.size = this.pageSize();
    this.requests.next({ criteria: request, background, documentType: this.documentType() });
  }

  changePage(page: number): void {
    if (this.loading() || page < 0 || page >= this.resultTotalPages()) return;
    this.currentPage = page;
    this.reload();
  }

  changeSize(event: Event): void {
    this.pageSize.set(Number((event.target as HTMLSelectElement).value));
    this.currentPage = 0;
    this.reload();
  }

  setDocumentType(documentType: DocumentType): void {
    if (documentType === this.documentType()) return;
    if (this.draftSearchText !== undefined) {
      this.criteria = { ...this.criteria };
      const query = this.draftSearchText.trim();
      if (query) this.criteria.query = query;
      else delete this.criteria.query;
    }
    this.documentType.set(documentType);
    this.currentPage = 0;
    this.error.set(null);
    this.dialogError.set(null);
    this.selectedSpreadsheet.set(null);
    this.activeFilterCount.set(
      documentType === 'pdf'
        ? Object.keys(this.criteria).filter((key) => key !== 'page' && key !== 'size').length
        : this.criteria.query?.trim()
          ? 1
          : 0,
    );
    this.reload();
  }

  setSearchText(value: string): void {
    this.draftSearchText = value;
  }

  tabKey(event: KeyboardEvent): void {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const type =
      event.key === 'Home'
        ? 'pdf'
        : event.key === 'End'
          ? 'spreadsheet'
          : this.documentType() === 'pdf'
            ? 'spreadsheet'
            : 'pdf';
    this.setDocumentType(type);
    (event.currentTarget as HTMLElement).parentElement
      ?.querySelector<HTMLButtonElement>(`[data-document-type="${type}"]`)
      ?.focus();
  }

  openSpreadsheet(file: SpreadsheetFile, mode: 'file' | 'sheets'): void {
    this.sheetOpener = document.activeElement as HTMLElement | null;
    this.selectedSpreadsheet.set({ file, mode });
  }

  closeSpreadsheet(): void {
    this.selectedSpreadsheet.set(null);
    if (this.sheetOpener?.isConnected) this.sheetOpener.focus({ preventScroll: true });
    this.sheetOpener = null;
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
