import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { ApplicationLayoutComponent } from '../../../../layout/application-layout/application-layout.component';
import { SpreadsheetFile } from '../../models/spreadsheet.models';
import { SpreadsheetViewComponent } from './spreadsheet-view.component';

/** A routable application page; all file/sheet authorization remains in the existing read API. */
@Component({
  selector: 'app-spreadsheet-page',
  imports: [ApplicationLayoutComponent, SpreadsheetViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-application-layout [libraryControls]="false">
    <main id="main-content" class="spreadsheet-page">
      @if (file(); as selected) {
        <app-spreadsheet-view
          [file]="selected"
          [pageView]="true"
          [initialSheetId]="sheetId()"
          [initialRowPage]="rowPage()"
          (closed)="back()"
        />
      } @else {
        <p role="alert">Choose a valid spreadsheet file.</p>
        <button type="button" class="btn btn-primary" (click)="back()">Back to library</button>
      }
    </main>
  </app-application-layout>`,
  styles: `
    :host {
      display: block;
      height: 100%;
      min-height: 0;
    }
    .spreadsheet-page {
      display: flex;
      flex-direction: column;
      min-height: 0;
      height: 100%;
      padding: 0.5rem;
    }
  `,
})
export class SpreadsheetPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly params = toSignal(this.route.paramMap, {
    initialValue: this.route.snapshot.paramMap,
  });
  private readonly query = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });
  readonly file = computed<SpreadsheetFile | null>(() => {
    const fileId = Number(this.params().get('fileId'));
    return Number.isSafeInteger(fileId) && fileId > 0
      ? {
          fileId,
          uploadId: 0,
          fileName: 'Opening spreadsheet…',
          fileType: '',
          sheetCount: 0,
          uploadedAt: '',
          createdBy: 0,
          createdByName: null,
        }
      : null;
  });
  readonly sheetId = computed(() => {
    const value = Number(this.query().get('sheet'));
    return Number.isSafeInteger(value) && value > 0 ? value : null;
  });
  readonly rowPage = computed(() => {
    const value = Number(this.query().get('row'));
    return Number.isSafeInteger(value) && value >= 0 ? value : 0;
  });
  back(): void {
    void this.router.navigateByUrl('/');
  }
}
