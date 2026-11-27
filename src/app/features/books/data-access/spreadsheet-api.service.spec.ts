import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BACKGROUND_AUTH_REQUEST } from '../../user/security/background-auth.context';
import { SpreadsheetApiService } from './spreadsheet-api.service';

describe('SpreadsheetApiService', () => {
  let api: SpreadsheetApiService;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(SpreadsheetApiService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('requests only the spreadsheet metadata page and filename search, marking automatic refresh as background', () => {
    api.list(' Report ', 2, 20, true).subscribe();
    const request = http.expectOne('/api/spreadsheets?page=2&size=20&search=Report');
    expect(request.request.context.get(BACKGROUND_AUTH_REQUEST)).toBe(true);
    expect(request.request.params.has('bookType')).toBe(false);
    request.flush({ content: [], number: 2, size: 20, totalElements: 0, totalPages: 0 });
    http.expectNone('/api/search/books');
  });

  it('reads only one file and a bounded page from the selected sheet', () => {
    api.details(101).subscribe();
    http.expectOne('/api/spreadsheets/101').flush({ sheets: [] });
    api.rows(101, 203, 1, 100).subscribe();
    http
      .expectOne('/api/spreadsheets/101/sheets/203/rows?page=1&size=100')
      .flush({ rows: [], rowNumbers: [] });
  });

  it('uses distinct original-file and already-extracted-sheet binary routes with response headers', () => {
    const received = vi.fn();
    api.downloadFile(101).subscribe(received);
    const original = http.expectOne('/api/spreadsheets/101/download');
    expect(original.request.responseType).toBe('blob');
    expect(original.request.method).toBe('GET');
    original.flush(new Blob(['original']), {
      headers: new HttpHeaders({ 'Content-Disposition': 'attachment; filename="Report.xls"' }),
    });
    expect(received.mock.calls[0][0].headers.get('Content-Disposition')).toContain('Report.xls');
    api.downloadSheet(101, 203).subscribe();
    const sheet = http.expectOne('/api/spreadsheets/101/sheets/203/download');
    expect(sheet.request.responseType).toBe('blob');
    expect(sheet.request.body).toBeNull();
    sheet.flush(new Blob(['standalone']));
    http.expectNone((request) => /json|b2b/i.test(request.url));
  });
});
