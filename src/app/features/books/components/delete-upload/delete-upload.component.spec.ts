import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DeleteUploadComponent } from './delete-upload.component';
import { FileDeleteAccessService } from './file-delete-access.service';
import { AuthBoundaryService } from '../../../user/services/auth-boundary.service';

describe('Delete uploaded files', () => {
  const access = { ensure: vi.fn(), canDelete: vi.fn(() => true) };
  beforeEach(() => {
    vi.clearAllMocks();
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
    access.canDelete.mockReturnValue(true);
    TestBed.configureTestingModule({
      imports: [DeleteUploadComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: FileDeleteAccessService, useValue: access },
      ],
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('loads deletion access when a processing receipt becomes ready', () => {
    const fixture = TestBed.createComponent(DeleteUploadComponent);
    fixture.componentRef.setInput('kind', 'uploads');
    fixture.componentRef.setInput('fileId', 12);
    fixture.componentRef.setInput('disabled', true);
    fixture.detectChanges();
    expect(access.ensure).not.toHaveBeenCalled();
    fixture.componentRef.setInput('disabled', false);
    fixture.detectChanges();
    expect(access.ensure).toHaveBeenCalledWith('uploads', 12);
  });
  function create(kind: 'books' | 'spreadsheets' | 'uploads') {
    const fixture = TestBed.createComponent(DeleteUploadComponent);
    fixture.componentRef.setInput('kind', kind);
    fixture.componentRef.setInput('fileId', 12);
    fixture.componentRef.setInput('filename', 'same.csv');
    fixture.detectChanges();
    return fixture;
  }
  it.each(['books', 'spreadsheets', 'uploads'] as const)(
    'deletes %s by unique ID and refreshes only after success',
    (kind) => {
      const fixture = create(kind),
        deleted = vi.fn();
      fixture.componentInstance.deleted.subscribe(deleted);
      fixture.nativeElement.querySelector('button').click();
      fixture.detectChanges();
      TestBed.inject(HttpTestingController).expectNone((request) => request.method === 'DELETE');
      expect(fixture.nativeElement.querySelector('[role="alertdialog"]')).not.toBeNull();
      fixture.nativeElement.querySelector('.confirm-file-delete').click();
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.confirm-file-delete').disabled).toBe(true);
      expect(deleted).not.toHaveBeenCalled();
      const request = TestBed.inject(HttpTestingController).expectOne(`/api/${kind}/12`);
      expect(request.request.method).toBe('DELETE');
      request.flush(null, { status: 204, statusText: 'No Content' });
      expect(deleted).toHaveBeenCalledTimes(1);
    },
  );
  it('shows failed cleanup and leaves the same ID available for retry', async () => {
    const fixture = create('books'),
      deleted = vi.fn();
    fixture.componentInstance.deleted.subscribe(deleted);
    fixture.nativeElement.querySelector('button').click();
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.confirm-file-delete').click();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/books/12')
      .flush(
        { detail: 'Cleanup incomplete. Retry Delete.' },
        { status: 503, statusText: 'Unavailable' },
      );
    await fixture.whenStable();
    fixture.detectChanges();
    expect(deleted).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Cleanup incomplete',
    );
    expect(fixture.nativeElement.querySelector('button').disabled).toBe(false);
    fixture.nativeElement.querySelector('.confirm-file-delete').click();
    TestBed.inject(HttpTestingController).expectOne('/api/books/12').flush(null);
    expect(deleted).toHaveBeenCalledTimes(1);
  });
  it('cancel and insufficient permissions never send a deletion request', () => {
    const fixture = create('books');
    fixture.nativeElement.querySelector('button').click();
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.confirmation-actions button').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alertdialog"]')).toBeNull();
    fixture.destroy();
    access.canDelete.mockReturnValue(false);
    const denied = create('books');
    expect(denied.nativeElement.querySelector('button').disabled).toBe(true);
    denied.nativeElement.querySelector('button').click();
    denied.componentInstance.openConfirmation();
    TestBed.inject(HttpTestingController).expectNone((request) => request.method === 'DELETE');
  });

  it('starts on Cancel, traps keyboard focus and restores the triggering control after Escape', async () => {
    const fixture = create('books');
    const trigger = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    trigger.focus();
    trigger.click();
    fixture.detectChanges();
    await fixture.whenStable();
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    const cancel = dialog.querySelector('.cancel-file-delete') as HTMLButtonElement;
    const confirm = dialog.querySelector('.confirm-file-delete') as HTMLButtonElement;
    expect(dialog.open).toBe(true);
    expect(document.activeElement).toBe(cancel);
    cancel.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }),
    );
    expect(document.activeElement).toBe(confirm);
    confirm.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }),
    );
    expect(document.activeElement).toBe(cancel);
    cancel.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    );
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('dialog')).toBeNull();
    expect(document.activeElement).toBe(trigger);
    TestBed.inject(HttpTestingController).expectNone((request) => request.method === 'DELETE');
  });

  it('cancels pending work and closes confirmation on an authentication change', () => {
    const fixture = create('books'),
      deleted = vi.fn();
    fixture.componentInstance.deleted.subscribe(deleted);
    fixture.nativeElement.querySelector('button').click();
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.confirm-file-delete').click();
    fixture.componentInstance.remove();
    const request = TestBed.inject(HttpTestingController).expectOne('/api/books/12');
    TestBed.inject(AuthBoundaryService).reset();
    fixture.detectChanges();
    expect(request.cancelled).toBe(true);
    expect(fixture.nativeElement.querySelector('dialog')).toBeNull();
    expect(fixture.componentInstance.pending()).toBe(false);
    expect(deleted).not.toHaveBeenCalled();
  });

  it('closes a confirmation if the row identity changes and requires a new explicit confirmation', () => {
    const fixture = create('books');
    fixture.nativeElement.querySelector('button').click();
    fixture.detectChanges();
    fixture.componentRef.setInput('fileId', 99);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('dialog')).toBeNull();
    fixture.componentInstance.remove();
    TestBed.inject(HttpTestingController).expectNone((request) => request.method === 'DELETE');
    expect(access.ensure).toHaveBeenLastCalledWith('books', 99);
  });
});
