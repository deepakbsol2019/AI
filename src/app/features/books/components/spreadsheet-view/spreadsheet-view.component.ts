import { DOCUMENT } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  OnDestroy,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { firstValueFrom, Subject, takeUntil } from 'rxjs';
import { ViewerDatePipe } from '../../../../shared/pipes/viewer-date.pipe';
import { NotificationOutletComponent } from '../../../../shared/ui/notification-outlet.component';
import { apiErrorMessage } from '../../../../shared/utils/api-error';
import { lockLibraryScroll } from '../../../../shared/utils/scroll-lock';
import { AuthBoundaryService } from '../../../user/services/auth-boundary.service';
import { BookDownloadService } from '../../data-access/book-download.service';
import { SpreadsheetApiService } from '../../data-access/spreadsheet-api.service';
import {
  SpreadsheetDetails,
  SpreadsheetFile,
  SpreadsheetRowsPage,
  SpreadsheetSheet,
} from '../../models/spreadsheet.models';

@Component({
  selector: 'app-spreadsheet-view',
  imports: [ViewerDatePipe, NotificationOutletComponent],
  templateUrl: './spreadsheet-view.component.html',
  styleUrl: './spreadsheet-view.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SpreadsheetViewComponent implements OnDestroy {
  readonly file = input.required<SpreadsheetFile>();
  readonly mode = input<'file' | 'sheets'>('file');
  readonly closed = output<void>();
  readonly downloads = inject(BookDownloadService);
  private readonly api = inject(SpreadsheetApiService);
  private readonly boundary = inject(AuthBoundaryService);
  private readonly destroy = inject(DestroyRef);
  private readonly document = inject(DOCUMENT);
  private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('sheetDialog');
  readonly details = signal<SpreadsheetDetails | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly activeSheetId = signal<number | null>(null);
  readonly rows = signal<SpreadsheetRowsPage | null>(null);
  readonly rowsLoading = signal(false);
  readonly rowsError = signal<string | null>(null);
  readonly activeSheet = computed(
    () => this.details()?.sheets.find((sheet) => sheet.sheetId === this.activeSheetId()) || null,
  );
  readonly columns = computed(() =>
    Array.from(
      { length: this.activeSheet()?.columnCount || 0 },
      (_, index) => `column_${index + 1}`,
    ),
  );
  private generation = 0;
  private rowGeneration = 0;
  private readonly cancelMetadata = new Subject<void>();
  private readonly cancelRows = new Subject<void>();
  private finished = false;
  private releaseScroll: (() => void) | null = null;

  constructor() {
    effect(() => {
      const fileId = this.file().fileId;
      untracked(() => void this.load(fileId));
    });
    this.boundary.resets.pipe(takeUntilDestroyed()).subscribe(() => this.close());
    afterNextRender(() => {
      if (this.finished) return;
      this.releaseScroll = lockLibraryScroll(this.document);
      this.dialog()?.nativeElement.showModal();
    });
  }

  async load(fileId = this.file().fileId): Promise<void> {
    const generation = ++this.generation;
    ++this.rowGeneration;
    this.cancelMetadata.next();
    this.cancelRows.next();
    this.details.set(null);
    this.rows.set(null);
    this.activeSheetId.set(null);
    this.loading.set(true);
    this.error.set(null);
    try {
      const details = await firstValueFrom(
        this.api
          .details(fileId)
          .pipe(
            takeUntil(this.cancelMetadata),
            takeUntil(this.boundary.resets),
            takeUntilDestroyed(this.destroy),
          ),
      );
      if (this.finished || generation !== this.generation) return;
      this.details.set({
        ...details,
        sheets: [...details.sheets].sort((a, b) => a.sheetIndex - b.sheetIndex),
      });
      if (this.mode() === 'file' && this.details()!.sheets.length)
        await this.viewSheet(this.details()!.sheets[0]);
    } catch (error) {
      const message = await apiErrorMessage(error);
      if (!this.finished && generation === this.generation) this.error.set(message);
    } finally {
      if (generation === this.generation) this.loading.set(false);
    }
  }

  async viewSheet(sheet: SpreadsheetSheet): Promise<void> {
    if (!this.details()?.sheets.some((item) => item.sheetId === sheet.sheetId)) return;
    this.activeSheetId.set(sheet.sheetId);
    await this.loadRows(0);
  }

  async loadRows(page = 0): Promise<void> {
    const sheetId = this.activeSheetId();
    if (sheetId === null || page < 0) return;
    const generation = ++this.rowGeneration;
    this.cancelRows.next();
    this.rowsLoading.set(true);
    this.rowsError.set(null);
    this.rows.set(null);
    try {
      const rows = await firstValueFrom(
        this.api
          .rows(this.file().fileId, sheetId, page, 100)
          .pipe(
            takeUntil(this.cancelRows),
            takeUntil(this.boundary.resets),
            takeUntilDestroyed(this.destroy),
          ),
      );
      if (!this.finished && generation === this.rowGeneration) this.rows.set(rows);
    } catch (error) {
      const message = await apiErrorMessage(error);
      if (!this.finished && generation === this.rowGeneration) this.rowsError.set(message);
    } finally {
      if (generation === this.rowGeneration) this.rowsLoading.set(false);
    }
  }

  selectSheet(event: Event): void {
    const id = Number((event.target as HTMLSelectElement).value);
    const sheet = this.details()?.sheets.find((item) => item.sheetId === id);
    if (sheet) void this.viewSheet(sheet);
  }

  back(): void {
    if (this.mode() === 'sheets' && this.activeSheetId() !== null) {
      ++this.rowGeneration;
      this.cancelRows.next();
      this.activeSheetId.set(null);
      this.rows.set(null);
      this.rowsError.set(null);
      this.rowsLoading.set(false);
    } else this.close();
  }

  downloadFile(): void {
    const file = this.file();
    this.downloads.downloadDocument(
      `file:${file.fileId}`,
      this.api.downloadFile(file.fileId),
      file.fileName,
    );
  }

  downloadSheet(sheet: SpreadsheetSheet): void {
    const file = this.file();
    if (!this.details()?.sheets.some((item) => item.sheetId === sheet.sheetId)) return;
    const base = file.fileName.replace(/\.[^.]+$/, '');
    const extension = file.fileType === 'CSV' ? 'csv' : 'xlsx';
    this.downloads.downloadDocument(
      `sheet:${file.fileId}:${sheet.sheetId}`,
      this.api.downloadSheet(file.fileId, sheet.sheetId),
      `${base}_${sheet.sheetName}.${extension}`,
    );
  }

  close(): void {
    if (this.finished) return;
    this.finished = true;
    this.cleanup();
    this.dialog()?.nativeElement.close();
    this.closed.emit();
  }

  ngOnDestroy(): void {
    this.finished = true;
    this.cleanup();
    this.cancelMetadata.complete();
    this.cancelRows.complete();
    this.dialog()?.nativeElement.close();
  }
  private cleanup(): void {
    ++this.generation;
    ++this.rowGeneration;
    this.cancelMetadata.next();
    this.cancelRows.next();
    this.details.set(null);
    this.rows.set(null);
    this.activeSheetId.set(null);
    this.releaseScroll?.();
    this.releaseScroll = null;
  }
}
