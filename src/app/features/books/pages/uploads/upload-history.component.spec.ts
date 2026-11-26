import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, Subject, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { UploadHistoryComponent } from './upload-history.component';
import { BooksApiService } from '../../data-access/books-api.service';
import { UploadStatus, UploadHistoryPage } from '../../models/book.models';
import { AuthBoundaryService } from '../../../user/services/auth-boundary.service';

describe('Persistent My Uploads history', () => {
  const item: UploadStatus = {
    book_upload_id: 41,
    status: 'inprogress',
    message: 'Queued for processing',
    state: 'PENDING',
    fileName: 'document.pdf',
    bookName: null,
    uploadedBy: 7,
    createdAt: '2026-11-26T08:00:00Z',
    updatedAt: '2026-11-26T08:00:00Z',
    failureReason: null,
  };
  const history: UploadHistoryPage = {
    content: [item],
    totalElements: 1,
    totalPages: 1,
    number: 0,
    size: 20,
  };
  let api: { myUploads: ReturnType<typeof vi.fn>; uploadStatus: ReturnType<typeof vi.fn> };
  beforeEach(() => {
    api = { myUploads: vi.fn(() => of(history)), uploadStatus: vi.fn(() => of(item)) };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'user/uploads', component: UploadHistoryComponent }]),
        { provide: BooksApiService, useValue: api },
      ],
    });
  });
  async function page(url = '/user/uploads') {
    const harness = await RouterTestingHarness.create(url);
    await harness.fixture.whenStable();
    harness.detectChanges();
    return {
      harness,
      component: harness.routeDebugElement!.componentInstance as UploadHistoryComponent,
      element: harness.routeNativeElement!,
    };
  }
  it('loads server history without needing a remembered receipt and displays actual uploader, date and state', async () => {
    const { element } = await page();
    expect(api.myUploads).toHaveBeenCalledWith(0, 20);
    expect(api.uploadStatus).not.toHaveBeenCalled();
    const text = element.textContent;
    expect(text).toContain('document.pdf');
    expect(text).toContain('User 7');
    expect(text).toContain('Pending');
    expect(text).toContain(Intl.DateTimeFormat().resolvedOptions().timeZone);
    expect([...element.querySelectorAll('th')].map((th) => th.textContent?.trim())).toEqual([
      'File / Book Name',
      'Upload ID',
      'Uploaded By',
      'Upload Date / Time',
      'Current Status',
      'Failure / Processing Details',
      'Refresh Status',
    ]);
  });
  it('opens a notification-linked Upload ID and refreshes its actual failure without leaving the page', async () => {
    const { component, harness, element } = await page('/user/uploads?uploadId=41');
    expect(api.uploadStatus).toHaveBeenCalledWith(41);
    api.uploadStatus.mockReturnValue(
      of({
        ...item,
        status: 'failed',
        state: 'FAILED',
        failureReason: 'The PDF could not be parsed.',
      }),
    );
    await component.refreshSelected();
    harness.detectChanges();
    expect(element.querySelector('.selected-upload')?.textContent).toContain('Failed');
    expect(element.querySelector('.selected-upload')?.textContent).toContain(
      'The PDF could not be parsed.',
    );
    expect(TestBed.inject(Router).url).toBe('/user/uploads?uploadId=41');
  });
  it('refreshes an individual row and preserves current pagination', async () => {
    const { component, harness, element } = await page('/user/uploads?page=2');
    expect(api.myUploads).toHaveBeenCalledWith(2, 20);
    api.uploadStatus.mockReturnValue(
      of({
        ...item,
        status: 'Success',
        state: 'COMPLETED',
        bookName: 'Stored book title',
        message: 'Processing completed',
      }),
    );
    await component.refreshRow(41);
    harness.detectChanges();
    expect(element.textContent).toContain('Stored book title');
    expect(element.textContent).toContain('Completed');
    expect(component.pageIndex()).toBe(2);
  });
  it('does not retain a previous selected result when another Upload ID is denied', async () => {
    const { component, harness } = await page('/user/uploads?uploadId=41');
    api.uploadStatus.mockReturnValue(
      throwError(
        () => new HttpErrorResponse({ status: 404, error: { detail: 'Upload not found.' } }),
      ),
    );
    await harness.navigateByUrl('/user/uploads?uploadId=99');
    await harness.fixture.whenStable();
    harness.detectChanges();
    await vi.waitFor(() => expect(component.selectedError()).toContain('Upload not found'));
    expect(component.selected()).toBeNull();
    expect(component.selectedId()).toBe(99);
  });
  it('clears history and ignores late private responses at an authentication boundary', async () => {
    const pending = new Subject<UploadHistoryPage>();
    api.myUploads.mockReturnValue(pending);
    const { component, harness } = await page();
    expect(component.loading()).toBe(true);
    TestBed.inject(AuthBoundaryService).reset();
    pending.next(history);
    pending.complete();
    await Promise.resolve();
    harness.detectChanges();
    expect(component.history()).toBeNull();
    expect(component.selected()).toBeNull();
    expect(component.loading()).toBe(false);
  });
  it('rejects an invalid Upload ID visibly without issuing an ownership lookup', async () => {
    const { element } = await page('/user/uploads?uploadId=not-an-id');
    expect(element.textContent).toContain('Choose a valid Upload ID.');
    expect(api.uploadStatus).not.toHaveBeenCalled();
  });
  it('shows useful history errors and can retry without manufacturing upload records', async () => {
    api.myUploads.mockReturnValue(
      throwError(
        () => new HttpErrorResponse({ status: 503, error: { detail: 'History is unavailable.' } }),
      ),
    );
    const { component, harness } = await page();
    await vi.waitFor(() => expect(component.error()).toContain('History is unavailable'));
    expect(component.history()).toBeNull();
    api.myUploads.mockReturnValue(of({ ...history, content: [], totalElements: 0, totalPages: 0 }));
    await component.refresh();
    harness.detectChanges();
    expect(harness.routeNativeElement?.textContent).toContain('No uploads found for your account.');
  });
});
