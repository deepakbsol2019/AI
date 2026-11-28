import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { ViewerDatePipe } from '../../../../shared/pipes/viewer-date.pipe';
import { BookDownloadService } from '../../data-access/book-download.service';
import { SpreadsheetApiService } from '../../data-access/spreadsheet-api.service';
import { SpreadsheetFile } from '../../models/spreadsheet.models';
import { DeleteUploadComponent } from '../delete-upload/delete-upload.component';

@Component({
  selector: 'app-spreadsheet-table',
  imports: [ViewerDatePipe, DeleteUploadComponent],
  template: `<table class="table align-middle mb-0">
    <caption class="visually-hidden">
      Completed CSV and Excel files. Download the original file or view its worksheets.
    </caption>
    <thead>
      <tr>
        <th scope="col">File Name</th>
        <th scope="col">Uploaded By</th>
        <th scope="col">Uploaded At</th>
        <th scope="col">Actions</th>
      </tr>
    </thead>
    <tbody>
      @for (file of files(); track file.fileId) {
        <tr>
          <td>
            <strong>{{ file.fileName }}</strong
            ><small>{{ file.sheetCount }} {{ file.sheetCount === 1 ? 'sheet' : 'sheets' }}</small>
          </td>
          <td>{{ file.createdByName || 'User ' + file.createdBy }}</td>
          <td>{{ file.uploadedAt | viewerDate }}</td>
          <td>
            <div class="file-actions">
              <button
                type="button"
                class="btn btn-outline-primary btn-sm"
                [disabled]="downloads.isDocumentDownloading('file:' + file.fileId)"
                (click)="downloadFile(file)"
              >
                Download File
              </button>
              <button type="button" class="btn btn-link btn-sm" (click)="viewFile.emit(file)">
                View File
              </button>
              <button type="button" class="btn btn-primary btn-sm" (click)="viewSheets.emit(file)">
                View Sheet
              </button>
              <app-delete-upload kind="spreadsheets" [fileId]="file.fileId" [filename]="file.fileName" (deleted)="deleted.emit()" />
            </div>
          </td>
        </tr>
      } @empty {
        <tr>
          <td colspan="4">
            <div class="empty-state">
              <p role="status">
                {{ busy() ? 'Finding spreadsheets…' : 'No matching CSV or Excel files.' }}
              </p>
            </div>
          </td>
        </tr>
      }
    </tbody>
  </table>`,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    table {
      min-width: 780px;
    }
    th,
    td {
      padding: 0.9rem 1rem;
      vertical-align: middle;
      overflow-wrap: anywhere;
    }
    th {
      white-space: nowrap;
    }
    small {
      display: block;
      color: var(--muted);
    }
    .file-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SpreadsheetTableComponent {
  readonly files = input<readonly SpreadsheetFile[]>([]);
  readonly busy = input(false);
  readonly viewFile = output<SpreadsheetFile>();
  readonly viewSheets = output<SpreadsheetFile>();
  readonly deleted = output<void>();
  readonly downloads = inject(BookDownloadService);
  private readonly api = inject(SpreadsheetApiService);
  downloadFile(file: SpreadsheetFile): void {
    this.downloads.downloadDocument(
      `file:${file.fileId}`,
      this.api.downloadFile(file.fileId),
      file.fileName,
    );
  }
}
