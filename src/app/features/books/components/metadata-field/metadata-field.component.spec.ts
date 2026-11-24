import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Component, signal } from '@angular/core';
import { NotificationService } from '../../../../shared/services/notification.service';
import type { Book, BookTopic } from '../../models/book.models';
import type { MetadataField } from '../../models/metadata-fields';
import { MetadataFieldComponent } from './metadata-field.component';

const topic: BookTopic = {
  topicId: 31,
  topicName: 'One topic',
  topicDetails: 'First paragraph.\n\nSecond paragraph.',
  startPage: 2,
  endPage: 3,
  printedStartPage: null,
  printedEndPage: null,
  createdAt: null,
  updatedAt: null,
};
const book: Book = {
  bookId: 17,
  bookName: 'Existing title',
  bookType: 'Physics',
  authorName: 'A writer',
  bookDetails: 'Book text.',
  totalPages: 3,
  bookUploadId: null,
  authorDetails: null,
  createdAt: null,
  updatedAt: null,
  topicCount: 1,
  topics: [topic],
};
const labels: Record<MetadataField, string> = {
  bookName: 'Book Name',
  bookType: 'Book Type',
  authorName: 'Author Name',
  bookDetails: 'Book Description',
  topicName: 'Topic Name',
  topicDetails: 'Topic Description',
};

@Component({
  imports: [MetadataFieldComponent],
  template: `<dialog open class="topics-dialog">
    <input id="topic-filter" aria-label="Filter topics" />
    @if (visible()) {
      <app-metadata-field
        [book]="currentBook"
        [topic]="currentTopic"
        field="topicDetails"
        label="Topic Description"
        (saved)="visible.set(false)"
      />
    }
  </dialog>`,
})
class MetadataFocusHost {
  readonly visible = signal(true);
  readonly currentBook = book;
  readonly currentTopic = topic;
}

describe('MetadataFieldComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value() {
        this.open = true;
      },
    });
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
      configurable: true,
      value() {
        if (this.open) {
          this.open = false;
          this.dispatchEvent(new Event('close'));
        }
      },
    });
    TestBed.configureTestingModule({
      imports: [MetadataFieldComponent, MetadataFocusHost],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  async function settle(fixture: ComponentFixture<MetadataFieldComponent>): Promise<void> {
    for (let index = 0; index < 5; index++) await Promise.resolve();
    fixture.detectChanges();
    await fixture.whenStable();
  }
  async function create(
    field: MetadataField = 'bookName',
    value = book,
    selectedTopic: BookTopic | null = null,
  ): Promise<ComponentFixture<MetadataFieldComponent>> {
    const fixture = TestBed.createComponent(MetadataFieldComponent);
    fixture.componentRef.setInput('book', value);
    fixture.componentRef.setInput('topic', selectedTopic);
    fixture.componentRef.setInput('field', field);
    fixture.componentRef.setInput('label', labels[field]);
    fixture.detectChanges();
    await settle(fixture);
    return fixture;
  }
  async function begin(
    fixture: ComponentFixture<MetadataFieldComponent>,
  ): Promise<HTMLButtonElement> {
    const pencil = fixture.nativeElement.querySelector('.metadata-pencil') as HTMLButtonElement;
    pencil.focus();
    pencil.click();
    await settle(fixture);
    return pencil;
  }
  function draft(
    fixture: ComponentFixture<MetadataFieldComponent>,
    value: string,
  ): HTMLInputElement | HTMLTextAreaElement {
    const input = fixture.nativeElement.querySelector('.metadata-input,.metadata-textarea') as
      HTMLInputElement | HTMLTextAreaElement;
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    return input;
  }

  it('initializes the full raw value only on pencil and never saves on blur or Cancel', async () => {
    const stored = { ...book, authorName: `  ${'Writer '.repeat(28)}  ` };
    const fixture = await create('authorName', stored);
    await begin(fixture);
    expect(fixture.nativeElement.querySelector('.metadata-pencil')).toBeNull();
    expect(fixture.nativeElement.querySelector('.metadata-save')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.metadata-cancel')).not.toBeNull();
    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    expect(input.value).toBe(stored.authorName);
    draft(fixture, 'Uncommitted author');
    input.dispatchEvent(new Event('blur'));
    fixture.componentRef.setInput('book', { ...stored, bookType: 'Chemistry' });
    await settle(fixture);
    expect(fixture.componentInstance.draft()).toBe('Uncommitted author');
    (fixture.nativeElement.querySelector('.metadata-cancel') as HTMLButtonElement).click();
    await settle(fixture);
    expect(fixture.componentInstance.editing()).toBe(false);
    expect(fixture.componentInstance.rawValue()).toBe(stored.authorName);
    http.expectNone(() => true);
  });

  it('validates required titles, schema length, and the reserved topic suffix before any request', async () => {
    const fixture = await create();
    await begin(fixture);
    draft(fixture, '  ');
    fixture.componentInstance.save();
    expect(fixture.componentInstance.error()).toContain('required');
    draft(fixture, 'x'.repeat(256));
    fixture.componentInstance.save();
    expect(fixture.componentInstance.error()).toContain('255');
    fixture.componentInstance.cancel();
    const offset = { ...topic, topicName: `${book.bookName}_offset` };
    fixture.componentRef.setInput('book', { ...book, topics: [offset] });
    await settle(fixture);
    await begin(fixture);
    draft(fixture, 'x'.repeat(249));
    fixture.componentInstance.save();
    expect(fixture.componentInstance.error()).toContain('248');
    const topicFixture = await create('topicName', book, topic);
    await begin(topicFixture);
    draft(topicFixture, 'Reserved_offset');
    topicFixture.componentInstance.save();
    expect(topicFixture.componentInstance.error()).toContain('reserved');
    http.expectNone(() => true);
  });

  it('sends only the edited field once and emits the confirmed Book after closing the editor', async () => {
    const fixture = await create();
    await begin(fixture);
    draft(fixture, '  Submitted title  ');
    const saved = vi.fn((confirmed: Book) => {
      expect(fixture.componentInstance.editing()).toBe(false);
      expect(confirmed.bookName).toBe('Confirmed title');
    });
    fixture.componentInstance.saved.subscribe(saved);
    fixture.componentInstance.save();
    fixture.componentInstance.save();
    fixture.detectChanges();
    const request = http.expectOne('/api/books/17/metadata');
    expect(request.request.body).toEqual({ bookName: 'Submitted title' });
    expect(fixture.componentInstance.pending()).toBe(true);
    expect(
      (fixture.nativeElement.querySelector('.metadata-save') as HTMLButtonElement).disabled,
    ).toBe(true);
    http.expectNone(() => true);
    const confirmed = { ...book, bookName: 'Confirmed title' };
    request.flush(confirmed);
    await settle(fixture);
    expect(saved).toHaveBeenCalledExactlyOnceWith(confirmed);
    expect(TestBed.inject(NotificationService).items()).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ severity: 'success', message: 'Book Name updated.' }),
      ]),
    );
  });

  it('normalizes an empty book type and clears a nullable author with one-key patches', async () => {
    const type = await create('bookType');
    await begin(type);
    draft(type, '   ');
    type.componentInstance.save();
    const typeRequest = http.expectOne('/api/books/17/metadata');
    expect(typeRequest.request.body).toEqual({ bookType: 'Unknown' });
    typeRequest.flush({ ...book, bookType: 'Unknown' });
    const author = await create('authorName');
    await begin(author);
    draft(author, '');
    author.componentInstance.save();
    const authorRequest = http.expectOne('/api/books/17/metadata');
    expect(authorRequest.request.body).toEqual({ authorName: null });
    authorRequest.flush({ ...book, authorName: null });
  });

  it('retains the complete draft and edit state after a server error and supports retry', async () => {
    const fixture = await create('bookType');
    await begin(fixture);
    draft(fixture, 'Interdisciplinary Science');
    const saved = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    fixture.componentInstance.save();
    http
      .expectOne('/api/books/17/metadata')
      .flush({ detail: 'Database unavailable' }, { status: 503, statusText: 'Unavailable' });
    await settle(fixture);
    expect(fixture.componentInstance.editing()).toBe(true);
    expect(fixture.componentInstance.pending()).toBe(false);
    expect(fixture.componentInstance.draft()).toBe('Interdisciplinary Science');
    expect(fixture.nativeElement.textContent).toContain('Database unavailable');
    expect(saved).not.toHaveBeenCalled();
    expect(TestBed.inject(NotificationService).items()).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ severity: 'error', message: 'Database unavailable' }),
      ]),
    );
    fixture.componentInstance.save();
    http
      .expectOne('/api/books/17/metadata')
      .flush({ ...book, bookType: 'Interdisciplinary Science' });
    await settle(fixture);
    expect(saved).toHaveBeenCalledTimes(1);
  });

  it('edits full paragraph descriptions in a nested native dialog, blocks pending Escape, and restores focus', async () => {
    const value = `${'A complete paragraph. '.repeat(350)}\n\n  Second paragraph remains spaced.  `;
    const fullTopic = { ...topic, topicDetails: value };
    const stored = { ...book, topics: [fullTopic] };
    const fixture = await create('topicDetails', stored, fullTopic);
    const bodyOverflow = document.body.style.getPropertyValue('overflow');
    expect(fixture.nativeElement.querySelector('dialog')).toBeNull();
    await begin(fixture);
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    const input = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    expect(dialog.open).toBe(true);
    expect(input.value).toBe(value);
    expect(input.hasAttribute('maxlength')).toBe(false);
    expect(document.body.style.getPropertyValue('overflow')).toBe('hidden');
    const next = `${value}\n\nThird paragraph.`;
    draft(fixture, next);
    fixture.componentInstance.save();
    fixture.detectChanges();
    const request = http.expectOne('/api/books/17/topics/31/metadata');
    expect(request.request.body).toEqual({ topicDetails: next });
    const escape = new Event('cancel', { cancelable: true });
    dialog.dispatchEvent(escape);
    expect(escape.defaultPrevented).toBe(true);
    expect(dialog.open).toBe(true);
    request.flush({ ...stored, topics: [{ ...fullTopic, topicDetails: next }] });
    await settle(fixture);
    expect(dialog.open).toBe(false);
    expect(document.body.style.getPropertyValue('overflow')).toBe(bodyOverflow);
    expect(fixture.nativeElement.querySelector('dialog')).toBeNull();
    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('.metadata-pencil'));
  });

  it('cancels a description with native close without patching or changing the stored value', async () => {
    const fixture = await create('bookDetails');
    expect(fixture.nativeElement.querySelector('dialog')).toBeNull();
    await begin(fixture);
    draft(fixture, 'Unsaved replacement');
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    dialog.close();
    await settle(fixture);
    expect(fixture.componentInstance.editing()).toBe(false);
    expect(fixture.componentInstance.rawValue()).toBe(book.bookDetails);
    expect(fixture.nativeElement.querySelector('dialog')).toBeNull();
    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('.metadata-pencil'));
    http.expectNone(() => true);
  });

  it('keeps technical source values visible with an explained disabled pencil', async () => {
    const offset = { ...topic, topicName: `${book.bookName}_offset` };
    const legacy = {
      ...topic,
      topicName: book.bookName,
      topicDetails: book.bookDetails,
      startPage: 1,
      endPage: book.totalPages,
    };
    for (const managed of [offset, legacy]) {
      const fixture = await create('topicName', { ...book, topics: [managed] }, managed);
      const pencil = fixture.nativeElement.querySelector('.metadata-pencil') as HTMLButtonElement;
      expect(pencil.disabled).toBe(true);
      expect(pencil.title).toBe('Managed PDF storage entry cannot be edited.');
      expect(fixture.nativeElement.textContent).toContain(managed.topicName);
      pencil.click();
      expect(fixture.componentInstance.editing()).toBe(false);
    }
    http.expectNone(() => true);
  });

  it('returns focus to the captured topic filter when a confirmed edit removes its origin row', async () => {
    const fixture = TestBed.createComponent(MetadataFocusHost);
    fixture.detectChanges();
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const pencil = element.querySelector('.metadata-pencil') as HTMLButtonElement;
    pencil.focus();
    pencil.click();
    fixture.detectChanges();
    await fixture.whenStable();
    const textarea = element.querySelector('textarea') as HTMLTextAreaElement;
    textarea.value = 'The saved description no longer matches the active topic filter.';
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    (element.querySelector('.metadata-description-save') as HTMLButtonElement).click();
    http
      .expectOne('/api/books/17/topics/31/metadata')
      .flush({ ...book, topics: [{ ...topic, topicDetails: textarea.value }] });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(element.querySelector('app-metadata-field')).toBeNull();
    expect(document.activeElement).toBe(element.querySelector('#topic-filter'));
  });

  it('does not steal focus if the user explicitly moves to another control before restoration', async () => {
    const fixture = await create();
    await begin(fixture);
    const other = document.createElement('button');
    other.textContent = 'Another action';
    document.body.appendChild(other);
    try {
      fixture.componentInstance.cancel();
      other.focus();
      await settle(fixture);
      expect(document.activeElement).toBe(other);
      http.expectNone(() => true);
    } finally {
      other.remove();
    }
  });
});
