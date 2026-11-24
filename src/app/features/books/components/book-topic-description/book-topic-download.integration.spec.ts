import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Book } from '../../models/book.models';
import { BookTopicDescriptionComponent } from './book-topic-description.component';

const book: Book = {
  bookId: 17,
  bookName: 'Programming and science',
  bookType: 'Computer Programming',
  authorName: null,
  bookDetails: null,
  totalPages: 30,
  bookUploadId: null,
  createdAt: null,
  updatedAt: null,
  authorDetails: null,
  topicCount: 3,
  topics: [
    { topicId: 31, topicName: 'Java programming basics', topicDetails: null },
    { topicId: 84, topicName: 'Runtime', topicDetails: 'Hands-on JAVA programming patterns' },
    { topicId: 99, topicName: 'Physics', topicDetails: 'Motion and energy' },
  ].map((topic) => ({
    ...topic,
    startPage: null,
    endPage: null,
    printedStartPage: null,
    printedEndPage: null,
    createdAt: null,
    updatedAt: null,
  })),
};

describe('Topic popup with the real download and HTTP services', () => {
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
      imports: [BookTopicDescriptionComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    vi.stubGlobal(
      'URL',
      class extends URL {
        static override createObjectURL = vi.fn(() => 'blob:selected-topics');
        static override revokeObjectURL = vi.fn();
      },
    );
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
  });

  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('filters without any requests and downloads only selected IDs even when hidden', async () => {
    const fixture = TestBed.createComponent(BookTopicDescriptionComponent);
    fixture.componentRef.setInput('book', book);
    fixture.detectChanges();
    await fixture.whenStable();
    vi.useFakeTimers();
    const http = TestBed.inject(HttpTestingController);
    const element = fixture.nativeElement as HTMLElement;
    const filter = element.querySelector<HTMLInputElement>('#topic-filter')!;
    const setFilter = (query: string) => {
      filter.value = query;
      filter.dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();
    };
    const select = (id: number) => {
      element.querySelector<HTMLInputElement>(`[data-topic-id="${id}"]`)!.click();
      fixture.detectChanges();
    };

    setFilter('  jAvA programming  ');
    expect(element.querySelectorAll('tbody input[type="checkbox"]')).toHaveLength(2);
    element
      .querySelector<HTMLFormElement>('.topic-filter')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    fixture.detectChanges();
    http.expectNone(() => true);
    expect(book.topics).toHaveLength(3);
    select(31);
    select(84);
    setFilter('Physics');
    expect(element.querySelectorAll('tbody input[type="checkbox"]')).toHaveLength(1);
    expect(fixture.componentInstance.selectedCount()).toBe(2);
    const selectedDownload = element.querySelector<HTMLButtonElement>('.selected-download')!;
    expect(selectedDownload.disabled).toBe(false);
    expect(element.querySelector<HTMLButtonElement>('.topic-action button')!.disabled).toBe(true);
    http.expectNone(() => true);

    selectedDownload.click();
    fixture.detectChanges();
    expect(selectedDownload.disabled).toBe(true);
    selectedDownload.click();
    const request = http.expectOne(
      '/api/download/book-topic-download?bookId=17&topicIds=31&topicIds=84',
    );
    expect(request.request.params.getAll('topicIds')).toEqual(['31', '84']);
    expect(request.request.responseType).toBe('blob');
    http.expectNone(() => true);
    request.flush(new Blob(['%PDF-selected'], { type: 'application/pdf' }), {
      headers: { 'Content-Disposition': 'attachment; filename="selected.pdf"' },
    });
    await Promise.resolve();
    await Promise.resolve();
    fixture.detectChanges();
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1);
    expect(selectedDownload.disabled).toBe(false);

    setFilter('');
    expect(element.querySelectorAll('tbody input[type="checkbox"]')).toHaveLength(3);
    expect(element.querySelector<HTMLInputElement>('[data-topic-id="31"]')!.checked).toBe(true);
    expect(element.querySelector<HTMLInputElement>('[data-topic-id="84"]')!.checked).toBe(true);
    http.expectNone(() => true);
    fixture.destroy();
  });
});
