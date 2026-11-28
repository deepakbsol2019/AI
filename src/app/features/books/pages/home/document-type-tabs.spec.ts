import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DeferBlockBehavior, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthBoundaryService } from '../../../user/services/auth-boundary.service';
import { INITIAL_DOCUMENT_TYPE } from '../../models/document-type.config';
import { HomeComponent } from './home.component';
import { FileDeleteAccessService } from '../../components/delete-upload/file-delete-access.service';

const pdfPage = { books: [], page: 0, size: 5, totalElements: 12, totalPages: 3 };
const file = {
  fileId: 101,
  uploadId: 7,
  fileName: 'Payroll_November.xlsx',
  fileType: 'XLSX',
  sheetCount: 3,
  uploadedAt: '2026-11-26T12:00:00Z',
  createdBy: 42,
  createdByName: 'Deepak Kumar',
};
const spreadsheetPage = { content: [file], number: 0, size: 5, totalElements: 1, totalPages: 1 };

describe('Home document-type routing', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HomeComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: FileDeleteAccessService, useValue: { ensure: () => {}, canDelete: () => true } }],
      deferBlockBehavior: DeferBlockBehavior.Playthrough,
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('starts with the confirmed PDF setting and requests no spreadsheets until the tab changes', async () => {
    const fixture = TestBed.createComponent(HomeComponent);
    http.expectOne('/api/search/books?size=5').flush(pdfPage);
    http.expectNone((request) => request.url.startsWith('/api/spreadsheets'));
    fixture.detectChanges();
    await fixture.whenStable();
    const tabs = fixture.nativeElement.querySelectorAll(
      '[role="tab"]',
    ) as NodeListOf<HTMLButtonElement>;
    expect(Array.from(tabs, (tab) => tab.textContent?.trim())).toEqual(['PDF', 'CSV / XLSX']);
    expect(tabs[0].getAttribute('aria-selected')).toBe('true');
    expect(tabs[0].disabled).toBe(false);
    expect(getComputedStyle(tabs[0]).backgroundColor).toBe('rgb(100, 116, 139)');
    expect(getComputedStyle(tabs[1]).backgroundColor).toBe('rgb(34, 94, 180)');
    expect(
      Array.from(fixture.nativeElement.querySelectorAll('app-book-table thead th'), (item: any) =>
        item.textContent.trim(),
      ),
    ).toEqual([
      'Book Name',
      'Book Type',
      'Author Name',
      'Book Description',
      'Download Book',
      'View Book',
      'View Topic',
      'Delete',
    ]);
  }, 15_000);

  it('keeps the initial-tab setting independent of search and requests only that configured type', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [HomeComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: INITIAL_DOCUMENT_TYPE, useValue: 'spreadsheet' },
      ],
      deferBlockBehavior: DeferBlockBehavior.Playthrough,
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(HomeComponent);
    http.expectNone((request) => request.url.startsWith('/api/search/books'));
    http.expectOne('/api/spreadsheets?page=0&size=5').flush(spreadsheetPage);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.documentType()).toBe('spreadsheet');
    expect(fixture.nativeElement.querySelector('app-book-table')).toBeNull();
  });

  it('preserves search text, resets paging and refreshes the spreadsheet list after confirmed deletion', async () => {
    const fixture = TestBed.createComponent(HomeComponent);
    http.expectOne('/api/search/books?size=5').flush(pdfPage);
    const home = fixture.componentInstance;
    home.search({ query: 'Quarterly', authorName: 'PDF author' });
    http
      .expectOne('/api/search/books?query=Quarterly&authorName=PDF%20author&size=5')
      .flush(pdfPage);
    home.changePage(2);
    http
      .expectOne('/api/search/books?query=Quarterly&authorName=PDF%20author&page=2&size=5')
      .flush({ ...pdfPage, page: 2 });
    home.setDocumentType('spreadsheet');
    const request = http.expectOne('/api/spreadsheets?page=0&size=5&search=Quarterly');
    expect(request.request.params.has('authorName')).toBe(false);
    request.flush(spreadsheetPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const table = fixture.nativeElement.querySelector('app-spreadsheet-table') as HTMLElement;
    expect(
      Array.from(table.querySelectorAll('thead th'), (item) => item.textContent?.trim()),
    ).toEqual(['File Name', 'Uploaded By', 'Uploaded At', 'Actions']);
    expect(
      Array.from(table.querySelectorAll('tbody button'), (item) => item.textContent?.trim()),
    ).toEqual(['Download File', 'View File', 'View Sheet', 'Delete']);
    expect(table.textContent).toContain(file.fileName);
    expect(table.textContent).toContain('Deepak Kumar');
    expect(table.textContent).not.toContain('Book Type');
    (table.querySelector('app-delete-upload button') as HTMLButtonElement).click();
    fixture.detectChanges();
    http.expectNone(request=>request.method==='DELETE');
    (table.querySelector('.confirm-file-delete') as HTMLButtonElement).click();
    const deletion = http.expectOne('/api/spreadsheets/101');
    expect(deletion.request.method).toBe('DELETE');
    http.expectNone(request => request.method === 'GET');
    deletion.flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/spreadsheets?page=0&size=5&search=Quarterly').flush({ ...spreadsheetPage, content: [], totalElements: 0, totalPages: 0 });
    home.setDocumentType('pdf');
    http
      .expectOne('/api/search/books?query=Quarterly&authorName=PDF%20author&size=5')
      .flush(pdfPage);
  });

  it('cancels a slow PDF request on switching types and clears tenant-specific data on identity reset', async () => {
    const fixture = TestBed.createComponent(HomeComponent);
    const initial = http.expectOne('/api/search/books?size=5');
    fixture.componentInstance.setDocumentType('spreadsheet');
    expect(initial.cancelled).toBe(true);
    http.expectOne('/api/spreadsheets?page=0&size=5').flush(spreadsheetPage);
    expect(fixture.componentInstance.results()).toBeNull();
    expect(fixture.componentInstance.spreadsheetResults()?.content).toEqual([file]);
    fixture.componentInstance.reload();
    const pending = http.expectOne('/api/spreadsheets?page=0&size=5');
    TestBed.inject(AuthBoundaryService).reset();
    expect(pending.cancelled).toBe(true);
    expect(fixture.componentInstance.spreadsheetResults()).toBeNull();
    expect(fixture.componentInstance.selectedSpreadsheet()).toBeNull();
  });

  it('keeps the current typed search text when a tab is switched before pressing Search', async () => {
    const fixture = TestBed.createComponent(HomeComponent);
    http.expectOne('/api/search/books?size=5').flush(pdfPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const query = fixture.nativeElement.querySelector('#library-query') as HTMLInputElement;
    query.value = 'Draft sales';
    query.dispatchEvent(new Event('input', { bubbles: true }));
    (
      fixture.nativeElement.querySelector('[data-document-type="spreadsheet"]') as HTMLButtonElement
    ).click();
    http.expectOne('/api/spreadsheets?page=0&size=5&search=Draft%20sales').flush(spreadsheetPage);
    fixture.detectChanges();
    expect(query.value).toBe('Draft sales');
    expect(fixture.nativeElement.querySelector('#library-query')).toBe(query);
  });

  it('uses keyboard tab navigation and starts a matching query for each switch', async () => {
    const fixture = TestBed.createComponent(HomeComponent);
    http.expectOne('/api/search/books?size=5').flush(pdfPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const tabs = fixture.nativeElement.querySelectorAll(
      '[role="tab"]',
    ) as NodeListOf<HTMLButtonElement>;
    tabs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    http.expectOne('/api/spreadsheets?page=0&size=5').flush(spreadsheetPage);
    fixture.detectChanges();
    expect(tabs[1].getAttribute('aria-selected')).toBe('true');
    expect(tabs[1].disabled).toBe(false);
    tabs[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    http.expectOne('/api/search/books?size=5').flush(pdfPage);
  });
});
