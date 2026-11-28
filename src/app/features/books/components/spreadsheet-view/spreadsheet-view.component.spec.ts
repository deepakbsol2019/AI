import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, Subject } from 'rxjs';
import { AuthBoundaryService } from '../../../user/services/auth-boundary.service';
import { BookDownloadService } from '../../data-access/book-download.service';
import { SpreadsheetApiService } from '../../data-access/spreadsheet-api.service';
import { SpreadsheetDetails, SpreadsheetRowsPage } from '../../models/spreadsheet.models';
import { SpreadsheetViewComponent } from './spreadsheet-view.component';

const file = {
  fileId: 101,
  uploadId: 7,
  fileName: 'Payroll_November.xlsx',
  fileType: 'XLSX',
  sheetCount: 2,
  uploadedAt: '2026-11-26T12:00:00Z',
  createdBy: 42,
  createdByName: 'Deepak Kumar',
};
const first = {
  sheetId: 201,
  sheetIndex: 1,
  sheetName: 'Employees',
  columnCount: 3,
  dataRowCount: 1200,
  header: { column_1: 'Employee ID', column_2: 'Name', column_3: '' },
  uploadedAt: file.uploadedAt,
  createdBy: 42,
};
const second = { ...first, sheetId: 202, sheetIndex: 2, sheetName: 'Departments', dataRowCount: 1 };
const detail: SpreadsheetDetails = { ...file, sheets: [second, first] };
const rowPage: SpreadsheetRowsPage = {
  rows: [{ column_1: '0012', column_2: 'Deepak', column_3: '' }],
  rowNumbers: [2],
  number: 0,
  size: 100,
  totalElements: 1200,
  totalPages: 12,
};

describe('Spreadsheet file and sheet views', () => {
  let metadata: Subject<SpreadsheetDetails>;
  let rows: Subject<SpreadsheetRowsPage>;
  const api = { details: vi.fn(), rows: vi.fn(), downloadFile: vi.fn(), downloadSheet: vi.fn() };
  const downloads = { isDocumentDownloading: () => false, downloadDocument: vi.fn() };
  beforeEach(() => {
    vi.clearAllMocks();
    metadata = new Subject();
    rows = new Subject();
    api.details.mockReturnValue(metadata);
    api.rows.mockReturnValue(rows);
    api.downloadFile.mockReturnValue(of({}));
    api.downloadSheet.mockReturnValue(of({}));
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value() {
        this.open = true;
      },
    });
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
      configurable: true,
      value() {
        this.open = false;
      },
    });
    TestBed.configureTestingModule({
      imports: [SpreadsheetViewComponent],
      providers: [
        provideRouter([]),
        { provide: SpreadsheetApiService, useValue: api },
        { provide: BookDownloadService, useValue: downloads },
      ],
    });
  });
  async function flush(
    fixture: ReturnType<typeof TestBed.createComponent<SpreadsheetViewComponent>>,
  ) {
    for (let step = 0; step < 15; step++) await Promise.resolve();
    fixture.detectChanges();
  }
  async function create(mode: 'file' | 'sheets') {
    const fixture = TestBed.createComponent(SpreadsheetViewComponent);
    fixture.componentRef.setInput('file', file);
    fixture.componentRef.setInput('mode', mode);
    fixture.detectChanges();
    await flush(fixture);
    return fixture;
  }

  it('opens View File at the first original-order sheet, renders string cells, and fetches only a bounded page', async () => {
    const fixture = await create('file');
    metadata.next(detail);
    await flush(fixture);
    expect(api.details).toHaveBeenCalledExactlyOnceWith(101);
    expect(api.rows).toHaveBeenCalledExactlyOnceWith(101, 201, 0, 100);
    expect(fixture.nativeElement.querySelector('dialog').classList.contains('file-preview')).toBe(
      true,
    );
    rows.next(rowPage);
    await flush(fixture);
    expect(fixture.nativeElement.textContent).toContain('0012');
    expect(fixture.nativeElement.textContent).toContain('Employee ID');
    expect(
      fixture.nativeElement
        .querySelector('[aria-label="Column 3, blank header"]')
        ?.textContent.trim(),
    ).toBe('');
    const selector = fixture.nativeElement.querySelector('select') as HTMLSelectElement;
    expect(Array.from(selector.options, (option) => option.textContent?.trim())).toEqual([
      '1. Employees',
      '2. Departments',
    ]);
    selector.value = '202';
    selector.dispatchEvent(new Event('change', { bubbles: true }));
    await flush(fixture);
    expect(api.rows).toHaveBeenLastCalledWith(101, 202, 0, 100);
    rows.next({ ...rowPage, totalElements: 1, totalPages: 1 });
    await flush(fixture);
    fixture.componentInstance.downloadFile();
    expect(api.downloadFile).toHaveBeenCalledExactlyOnceWith(101);
    expect(downloads.downloadDocument).toHaveBeenCalledWith(
      'file:101',
      expect.anything(),
      file.fileName,
    );
    fixture.destroy();
  });

  it('shows View Sheet as a file-scoped topic list with separate View/Download and returns from preview to the same list', async () => {
    const fixture = await create('sheets');
    metadata.next(detail);
    await flush(fixture);
    expect(api.rows).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelectorAll('[aria-label^="View sheet:"]')).toHaveLength(2);
    const view = fixture.nativeElement.querySelector(
      '[aria-label="View sheet: Departments"]',
    ) as HTMLButtonElement;
    view.click();
    await flush(fixture);
    expect(api.rows).toHaveBeenCalledExactlyOnceWith(101, 202, 0, 100);
    rows.next({ ...rowPage, totalElements: 1, totalPages: 1 });
    await flush(fixture);
    expect(fixture.componentInstance.activeSheetId()).toBe(202);
    fixture.componentInstance.back();
    await flush(fixture);
    expect(fixture.componentInstance.activeSheetId()).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('[aria-label^="View sheet:"]')).toHaveLength(2);
    (
      fixture.nativeElement.querySelector(
        '[aria-label="Download sheet: Departments"]',
      ) as HTMLButtonElement
    ).click();
    expect(api.downloadSheet).toHaveBeenCalledExactlyOnceWith(101, 202);
    expect(downloads.downloadDocument).toHaveBeenCalledWith(
      'sheet:101:202',
      expect.anything(),
      'Payroll_November_Departments.xlsx',
    );
    expect(fixture.nativeElement.textContent).not.toMatch(/JSON|merge|selected sheets/i);
    fixture.destroy();
  });

  it('paginates row previews and rejects a sheet that does not belong to the displayed file', async () => {
    const fixture = await create('file');
    metadata.next(detail);
    await flush(fixture);
    rows.next(rowPage);
    await flush(fixture);
    const next = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((button) => button.textContent?.trim() === 'Next rows')!;
    next.click();
    await flush(fixture);
    expect(api.rows).toHaveBeenLastCalledWith(101, 201, 1, 100);
    rows.next({ ...rowPage, number: 1, rowNumbers: [102] });
    await flush(fixture);
    expect(fixture.nativeElement.textContent).toContain('Page 2 / 12');
    const calls = api.rows.mock.calls.length;
    await fixture.componentInstance.viewSheet({ ...first, sheetId: 999 });
    fixture.componentInstance.downloadSheet({ ...first, sheetId: 999 });
    expect(api.rows).toHaveBeenCalledTimes(calls);
    expect(api.downloadSheet).not.toHaveBeenCalled();
    fixture.destroy();
  });

  it('shows no redundant selector for a CSV and supports an empty/header-only sheet', async () => {
    const fixture = await create('file');
    fixture.componentRef.setInput('file', {
      ...file,
      fileType: 'CSV',
      fileName: 'Empty.csv',
      sheetCount: 1,
    });
    await flush(fixture);
    metadata.next({
      ...file,
      fileType: 'CSV',
      fileName: 'Empty.csv',
      sheetCount: 1,
      sheets: [{ ...first, columnCount: 0, dataRowCount: 0, header: {} }],
    });
    await flush(fixture);
    rows.next({ ...rowPage, rows: [], rowNumbers: [], totalElements: 0, totalPages: 0 });
    await flush(fixture);
    expect(fixture.nativeElement.querySelector('select')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('This sheet has no data rows.');
    fixture.destroy();
  });

  it('closes and clears private metadata/rows on an identity change and ignores an earlier pending row result', async () => {
    const fixture = await create('file');
    const closed = vi.fn();
    fixture.componentInstance.closed.subscribe(closed);
    metadata.next(detail);
    await flush(fixture);
    expect(fixture.componentInstance.details()).not.toBeNull();
    TestBed.inject(AuthBoundaryService).reset();
    rows.next(rowPage);
    await flush(fixture);
    expect(closed).toHaveBeenCalledOnce();
    expect(fixture.componentInstance.details()).toBeNull();
    expect(fixture.componentInstance.rows()).toBeNull();
    fixture.destroy();
  });

  it.each(['CSV', 'XLSX'])(
    'offers a routed full-page view for %s and preserves selected sheet and row page',
    async (fileType) => {
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      const fixture = await create('file');
      metadata.next({ ...detail, fileType });
      await flush(fixture);
      rows.next({ ...rowPage, number: 3 });
      await flush(fixture);
      (fixture.nativeElement.querySelector('.full-page-spreadsheet') as HTMLButtonElement).click();
      expect(navigate).toHaveBeenCalledWith(['/spreadsheets', 101, 'view'], {
        queryParams: { sheet: 201, row: 3 },
      });
      fixture.destroy();
    },
  );

  it('uses the application page area without modal scroll locking and restores the requested sheet/page', async () => {
    const modal = vi.spyOn(HTMLDialogElement.prototype, 'showModal');
    const fixture = TestBed.createComponent(SpreadsheetViewComponent);
    fixture.componentRef.setInput('file', file);
    fixture.componentRef.setInput('pageView', true);
    fixture.componentRef.setInput('initialSheetId', 202);
    fixture.componentRef.setInput('initialRowPage', 2);
    fixture.detectChanges();
    await flush(fixture);
    metadata.next(detail);
    await flush(fixture);
    expect(api.rows).toHaveBeenCalledExactlyOnceWith(101, 202, 2, 100);
    expect(modal).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('dialog').classList.contains('route-view')).toBe(
      true,
    );
    expect(fixture.nativeElement.querySelector('.full-page-spreadsheet')).toBeNull();
    fixture.destroy();
  });
});
