import { Component, DestroyRef, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom, takeUntil } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { BooksApiService } from '../../data-access/books-api.service';
import { UploadHistoryPage, UploadStatus, uploadState } from '../../models/book.models';
import { ViewerDatePipe } from '../../../../shared/pipes/viewer-date.pipe';
import { apiErrorMessage } from '../../../../shared/utils/api-error';
import { AuthBoundaryService } from '../../../user/services/auth-boundary.service';

/** Durable server history; all ownership checks use the authenticated tenant and uploader. */
@Component({
  selector: 'app-upload-history',
  standalone: true,
  imports: [RouterLink, ViewerDatePipe],
  template: `
    <header class="history-heading">
      <h1>My Uploads</h1>
      <a class="btn btn-outline-primary" routerLink="/">Back to Library</a>
    </header>
    <p>
      Your upload history remains available after you close this page or sign in again. Times use
      your current viewing timezone.
    </p>
    <div class="history-actions">
      <button class="btn btn-primary" type="button" [disabled]="loading()" (click)="refresh()">
        Refresh Status
      </button>
    </div>
    @if (error()) {
      <p class="auth-error" role="alert">{{ error() }}</p>
    }
    @if (loading()) {
      <p role="status">Loading your upload history…</p>
    }
    @if (selectedError() && !selectedId()) {
      <p class="auth-error" role="alert">{{ selectedError() }}</p>
    }
    @if (selectedId()) {
      <section class="selected-upload" aria-label="Selected upload status">
        <h2>Upload ID {{ selectedId() }}</h2>
        @if (selectedLoading()) {
          <p role="status">Refreshing upload status…</p>
        }
        @if (selectedError()) {
          <p class="auth-error" role="alert">{{ selectedError() }}</p>
        }
        @if (selected(); as item) {
          <dl>
            <dt>File / Book Name</dt>
            <dd>{{ item.bookName || item.fileName || 'Not available' }}</dd>
            <dt>Current Status</dt>
            <dd>{{ stateLabel(item) }}</dd>
            <dt>Uploaded By</dt>
            <dd>{{ uploader(item) }}</dd>
            <dt>Upload Date / Time</dt>
            <dd>{{ item.createdAt | viewerDate }}</dd>
            <dt>Last Updated</dt>
            <dd>{{ item.updatedAt | viewerDate }}</dd>
            <dt>Details</dt>
            <dd>{{ item.failureReason || item.message || 'Not available' }}</dd>
          </dl>
        }
        <button
          class="btn btn-outline-primary"
          type="button"
          [disabled]="selectedLoading()"
          (click)="refreshSelected()"
        >
          Refresh Status for Upload {{ selectedId() }}
        </button>
      </section>
    }
    <div class="history-table" tabindex="0" role="region" aria-label="My upload history">
      <table class="table">
        <thead>
          <tr>
            <th>File / Book Name</th>
            <th>Upload ID</th>
            <th>Uploaded By</th>
            <th>Upload Date / Time</th>
            <th>Current Status</th>
            <th>Failure / Processing Details</th>
            <th>Refresh Status</th>
          </tr>
        </thead>
        <tbody>
          @for (item of history()?.content || []; track item.book_upload_id) {
            <tr>
              <td>
                {{ item.bookName || item.fileName || 'Not available' }}
                @if (item.bookName && item.fileName && item.bookName !== item.fileName) {
                  <small>File: {{ item.fileName }}</small>
                }
              </td>
              <td>
                <a
                  [routerLink]="['/user/uploads']"
                  [queryParams]="{ uploadId: item.book_upload_id, page: pageIndex() }"
                  >{{ item.book_upload_id }}</a
                >
              </td>
              <td>{{ uploader(item) }}</td>
              <td>{{ item.createdAt | viewerDate }}</td>
              <td>
                <span
                  class="upload-state"
                  [class.failed]="state(item) === 'FAILED'"
                  [class.completed]="state(item) === 'COMPLETED'"
                  >{{ stateLabel(item) }}</span
                >
              </td>
              <td>{{ item.failureReason || item.message || 'Not available' }}</td>
              <td>
                <button
                  type="button"
                  class="btn btn-sm btn-outline-primary"
                  [disabled]="rowLoading() === item.book_upload_id"
                  (click)="refreshRow(item.book_upload_id)"
                >
                  Refresh Status
                </button>
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="7">
                {{ loading() ? 'Loading uploads…' : 'No uploads found for your account.' }}
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    @if (history(); as page) {
      <nav class="history-actions" aria-label="Upload history pages">
        <button
          class="btn btn-outline-primary"
          type="button"
          [disabled]="loading() || pageIndex() === 0"
          (click)="changePage(-1)"
        >
          Previous
        </button>
        <span
          >Page {{ pageIndex() + 1 }} of {{ page.totalPages || 1 }} ·
          {{ page.totalElements }} uploads</span
        >
        <button
          class="btn btn-outline-primary"
          type="button"
          [disabled]="loading() || pageIndex() + 1 >= page.totalPages"
          (click)="changePage(1)"
        >
          Next
        </button>
      </nav>
    }
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    .history-heading,
    .history-actions {
      display: flex;
      gap: 0.75rem;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      margin-bottom: 1rem;
    }
    .history-actions {
      justify-content: flex-start;
      margin-block: 1rem;
    }
    .history-heading h1 {
      margin: 0;
    }
    .history-table {
      max-width: 100%;
      overflow: auto;
      background: #fff;
      border: 1px solid var(--line);
      border-radius: 0.5rem;
    }
    .history-table table {
      min-width: 56rem;
      margin: 0;
    }
    td,
    th {
      vertical-align: top;
      overflow-wrap: anywhere;
    }
    small {
      display: block;
      color: var(--muted);
    }
    .selected-upload {
      padding: 1rem;
      border: 1px solid var(--line);
      border-radius: 0.5rem;
      background: #fff;
      margin-block: 1rem;
    }
    dl {
      display: grid;
      grid-template-columns: minmax(9rem, 1fr) 3fr;
      gap: 0.4rem;
    }
    dd {
      overflow-wrap: anywhere;
    }
    .upload-state {
      font-weight: 700;
    }
    .failed {
      color: #9a1c20;
    }
    .completed {
      color: #166534;
    }
    @media (max-width: 500px) {
      dl {
        grid-template-columns: 1fr;
      }
    }
  `,
})
export class UploadHistoryComponent {
  private readonly api = inject(BooksApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly boundary = inject(AuthBoundaryService);
  readonly history = signal<UploadHistoryPage | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly pageIndex = signal(0);
  readonly selectedId = signal<number | null>(null);
  readonly selected = signal<UploadStatus | null>(null);
  readonly selectedLoading = signal(false);
  readonly selectedError = signal<string | null>(null);
  readonly rowLoading = signal<number | null>(null);
  readonly state = uploadState;
  private generation = 0;
  private selectedGeneration = 0;
  constructor() {
    this.boundary.resets.pipe(takeUntilDestroyed()).subscribe(() => {
      this.generation++;
      this.selectedGeneration++;
      this.history.set(null);
      this.selected.set(null);
      this.error.set(null);
      this.selectedError.set(null);
      this.loading.set(false);
      this.selectedLoading.set(false);
      this.rowLoading.set(null);
    });
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.selectedGeneration++;
      this.pageIndex.set(Math.max(0, Number.parseInt(params.get('page') || '0', 10) || 0));
      const supplied = params.get('uploadId'),
        id = supplied === null ? null : Number(supplied);
      this.selectedId.set(id !== null && Number.isSafeInteger(id) && id > 0 ? id : null);
      this.selected.set(null);
      this.selectedError.set(
        supplied !== null && this.selectedId() === null ? 'Choose a valid Upload ID.' : null,
      );
      void this.refresh();
    });
  }
  stateLabel(item: UploadStatus) {
    return {
      PENDING: 'Pending',
      PROCESSING: 'Processing',
      COMPLETED: 'Completed',
      FAILED: 'Failed',
    }[uploadState(item)];
  }
  uploader(item: UploadStatus) {
    return item.uploadedBy == null ? 'Not available' : `User ${item.uploadedBy}`;
  }
  async refresh() {
    const generation = ++this.generation;
    this.loading.set(true);
    this.error.set(null);
    try {
      const page = await firstValueFrom(
        this.api
          .myUploads(this.pageIndex(), 20)
          .pipe(takeUntil(this.boundary.resets), takeUntilDestroyed(this.destroy)),
      );
      if (generation === this.generation) this.history.set(page);
    } catch (error) {
      if (generation === this.generation) this.error.set(await apiErrorMessage(error));
    } finally {
      if (generation === this.generation) this.loading.set(false);
    }
    if (generation === this.generation && this.selectedId()) await this.refreshSelected();
  }
  async refreshSelected() {
    const id = this.selectedId();
    if (id === null) return;
    const generation = ++this.selectedGeneration;
    this.selectedLoading.set(true);
    this.selectedError.set(null);
    try {
      const item = await firstValueFrom(
        this.api
          .uploadStatus(id)
          .pipe(takeUntil(this.boundary.resets), takeUntilDestroyed(this.destroy)),
      );
      if (generation === this.selectedGeneration) this.selected.set(item);
    } catch (error) {
      if (generation === this.selectedGeneration) {
        this.selected.set(null);
        this.selectedError.set(await apiErrorMessage(error));
      }
    } finally {
      if (generation === this.selectedGeneration) this.selectedLoading.set(false);
    }
  }
  async refreshRow(id: number) {
    if (this.rowLoading() !== null) return;
    const generation = this.generation;
    this.rowLoading.set(id);
    this.error.set(null);
    try {
      const item = await firstValueFrom(
        this.api
          .uploadStatus(id)
          .pipe(takeUntil(this.boundary.resets), takeUntilDestroyed(this.destroy)),
      );
      if (generation === this.generation) {
        this.history.update((page) =>
          page
            ? {
                ...page,
                content: page.content.map((old) => (old.book_upload_id === id ? item : old)),
              }
            : page,
        );
        if (this.selectedId() === id) this.selected.set(item);
      }
    } catch (error) {
      if (generation === this.generation) this.error.set(await apiErrorMessage(error));
    } finally {
      this.rowLoading.set(null);
    }
  }
  changePage(delta: number) {
    void this.router.navigate(['/user/uploads'], {
      queryParams: {
        page: Math.max(0, this.pageIndex() + delta),
        ...(this.selectedId() ? { uploadId: this.selectedId() } : {}),
      },
    });
  }
}
