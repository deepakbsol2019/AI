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
  readonly results = signal<BookSearchResponse | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly dialogLoading = signal(false);
  readonly dialogError = signal<string | null>(null);
  readonly pageSize = signal(20);
  readonly activeFilterCount = signal(0);
  private criteria: BookSearchCriteria = {};
  private currentPage = 0;
  private readonly requests = new Subject<BookSearchCriteria>();
  private readonly dialogHost = viewChild.required('dialogHost', { read: ViewContainerRef });
  private activeDialog: ComponentRef<unknown> | null = null;
  private dialogSelection = 0;

  constructor() {
    this.requests
      .pipe(
        startWith({} as BookSearchCriteria),
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
          this.currentPage = result.data.page;
          this.pageSize.set(result.data.size);
        }
      });
    this.upload.completed$.pipe(takeUntilDestroyed()).subscribe(() => this.reload());
    this.destroyRef.onDestroy(() => {
      this.dialogSelection++;
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
    this.pageSize.set(20);
    this.requests.next({});
  }

  reload(): void {
    const request = { ...this.criteria };
    if (this.currentPage > 0) request.page = this.currentPage;
    if (this.pageSize() !== 20) request.size = this.pageSize();
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

  async viewBook(book: Book): Promise<void> {
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
      ref.instance.closed.subscribe(() => {
        ref.destroy();
        if (this.activeDialog === ref) this.activeDialog = null;
        if (opener?.isConnected) opener.focus();
      });
    } catch {
      if (selection === this.dialogSelection && !this.destroyRef.destroyed) {
        this.dialogError.set('The book topics view could not load. Please try View Book again.');
      }
    } finally {
      if (selection === this.dialogSelection) this.dialogLoading.set(false);
    }
  }
}
