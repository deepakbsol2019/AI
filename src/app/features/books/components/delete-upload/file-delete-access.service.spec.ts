import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { AuthBoundaryService } from '../../../user/services/auth-boundary.service';
import { AuthSessionService } from '../../../user/services/auth-session.service';
import { FileDeleteAccessService } from './file-delete-access.service';

describe('Batched file delete capabilities', () => {
  const user = signal<{ tenantId: number; userId: number } | null>(null);
  let access: FileDeleteAccessService;
  let http: HttpTestingController;
  beforeEach(() => {
    user.set({ tenantId: 7, userId: 11 });
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthSessionService, useValue: { user } },
      ],
    });
    access = TestBed.inject(FileDeleteAccessService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  const queued = async () => {
    await Promise.resolve();
  };

  it('deduplicates table IDs and splits each kind into batches of at most 100', async () => {
    for (let id = 1; id <= 205; id++) {
      access.ensure('books', id);
      access.ensure('books', id);
    }
    access.ensure('spreadsheets', 1);
    access.ensure('books', 0);
    access.ensure('books', -1);
    access.ensure('books', NaN);
    await queued();
    const requests = http.match((request) => request.url === '/api/files/delete-capabilities');
    expect(requests).toHaveLength(4);
    const books = requests.filter((request) => request.request.params.get('kind') === 'books');
    expect(books.map((request) => request.request.params.getAll('ids')!.length)).toEqual([
      100, 100, 5,
    ]);
    expect(books.flatMap((request) => request.request.params.getAll('ids')!)).toEqual(
      Array.from({ length: 205 }, (_, index) => `${index + 1}`),
    );
    for (const request of requests) request.flush({ 1: true, 2: false });
    expect(access.canDelete('books', 1)).toBe(true);
    expect(access.canDelete('books', 2)).toBe(false);
    expect(access.canDelete('spreadsheets', 1)).toBe(true);
    access.ensure('books', 1);
    await queued();
    http.expectNone((request) => request.url === '/api/files/delete-capabilities');
  });

  it('denies missing, false and failed capabilities without affecting download operations', async () => {
    access.ensure('uploads', 3);
    access.ensure('uploads', 4);
    await queued();
    http.expectOne((request) => request.params.get('kind') === 'uploads').flush({ 3: false });
    expect(access.canDelete('uploads', 3)).toBe(false);
    expect(access.canDelete('uploads', 4)).toBe(false);
    access.ensure('books', 8);
    await queued();
    http
      .expectOne((request) => request.params.get('kind') === 'books')
      .flush(null, { status: 503, statusText: 'Unavailable' });
    expect(access.canDelete('books', 8)).toBe(false);
    access.ensure('books', 8);
    await queued();
    http.expectOne((request) => request.params.get('kind') === 'books').flush({ 8: true });
    expect(access.canDelete('books', 8)).toBe(true);
    http.expectNone((request) => request.url.includes('download'));
  });

  it('does not reuse earlier permissions when identity changes, including before effects run', async () => {
    access.ensure('books', 12);
    await queued();
    http.expectOne((request) => request.params.get('kind') === 'books').flush({ 12: true });
    expect(access.canDelete('books', 12)).toBe(true);
    user.set({ tenantId: 7, userId: 22 });
    expect(access.canDelete('books', 12)).toBe(false);
    access.ensure('books', 12);
    await queued();
    http.expectOne((request) => request.params.get('kind') === 'books').flush({ 12: false });
    expect(access.canDelete('books', 12)).toBe(false);
    user.set(null);
    access.ensure('books', 12);
    await queued();
    expect(access.canDelete('books', 12)).toBe(false);
    http.expectNone((request) => request.url === '/api/files/delete-capabilities');
  });

  it('cancels stale identity requests on an authentication reset', async () => {
    access.ensure('books', 12);
    await queued();
    const stale = http.expectOne((request) => request.params.get('kind') === 'books');
    TestBed.inject(AuthBoundaryService).reset();
    expect(stale.cancelled).toBe(true);
    expect(access.canDelete('books', 12)).toBe(false);
    user.set({ tenantId: 8, userId: 33 });
    access.ensure('books', 12);
    await queued();
    http.expectOne((request) => request.params.get('kind') === 'books').flush({ 12: false });
    expect(access.canDelete('books', 12)).toBe(false);
  });
});
