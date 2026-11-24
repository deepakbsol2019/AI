import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DeferBlockBehavior, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { MetadataFieldComponent } from '../../components/metadata-field/metadata-field.component';
import { BookTopicDescriptionComponent } from '../../components/book-topic-description/book-topic-description.component';
import type { Book, BookSearchResponse } from '../../models/book.models';
import { HomeComponent } from './home.component';

const description =
  'Memory data structures and complete descriptions.\n\n' + 'Unbroken'.repeat(100);
const book: Book = {
  bookId: 5,
  bookName: 'A complete title ' + 'x'.repeat(160),
  bookType: 'Computer Programming with Data Structures and Complete Examples',
  authorName: 'Ada',
  bookDetails: 'The complete book description.\n\nSecond paragraph.',
  totalPages: 20,
  bookUploadId: null,
  createdAt: null,
  updatedAt: null,
  authorDetails: null,
  topicCount: 2,
  topics: [
    { topicId: 21, topicName: 'First topic', topicDetails: description },
    { topicId: 45, topicName: 'Other topic', topicDetails: 'Concurrency' },
  ].map((topic, index) => ({
    ...topic,
    startPage: index * 10 + 1,
    endPage: (index + 1) * 10,
    printedStartPage: null,
    printedEndPage: null,
    createdAt: null,
    updatedAt: null,
  })),
};
const result = (stored = book, page = 0): BookSearchResponse => ({
  books: [stored],
  page,
  size: 5,
  totalElements: 6,
  totalPages: 2,
});

describe('Per-field edits in the existing Home and topic popup', () => {
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
      imports: [HomeComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
      deferBlockBehavior: DeferBlockBehavior.Playthrough,
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('saves one field only, reconciles the existing search and preserves popup filter/selection', async () => {
    const fixture = TestBed.createComponent(HomeComponent);
    const home = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/search/books?size=5').flush(result());
    fixture.detectChanges();
    await fixture.whenStable();
    home.search({ authorName: 'Ada' });
    http.expectOne('/api/search/books?authorName=Ada&size=5').flush(result());
    home.changePage(1);
    http.expectOne('/api/search/books?authorName=Ada&page=1&size=5').flush(result(book, 1));
    fixture.detectChanges();
    await fixture.whenStable();
    const fieldElement = (field: string, topicId?: number) =>
      fixture.debugElement.queryAll(By.directive(MetadataFieldComponent)).find((node) => {
        const component = node.componentInstance as MetadataFieldComponent;
        return component.field() === field && component.topic()?.topicId === topicId;
      })!;
    const typeField = fieldElement('bookType');
    (typeField.nativeElement.querySelector('.metadata-pencil') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    let input = typeField.nativeElement.querySelector('.metadata-input') as HTMLInputElement;
    expect(input.value).toBe(book.bookType);
    input.value = 'A canceled edit';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('blur'));
    http.expectNone(() => true);
    (typeField.nativeElement.querySelector('.metadata-cancel') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectNone(() => true);
    expect(home.results()?.books[0].bookType).toBe(book.bookType);

    (typeField.nativeElement.querySelector('.metadata-pencil') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    input = typeField.nativeElement.querySelector('.metadata-input') as HTMLInputElement;
    expect(input.value).toBe(book.bookType);
    input.value = ' Physics ';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    const save = typeField.nativeElement.querySelector('.metadata-save') as HTMLButtonElement;
    save.click();
    fixture.detectChanges();
    expect(save.disabled).toBe(true);
    save.click();
    const patch = http.expectOne('/api/books/5/metadata');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({ bookType: 'Physics' });
    expect(home.results()?.books[0].bookType).toBe(book.bookType);
    http.expectNone((request) => request.method === 'PATCH');
    const updatedBook = { ...book, bookType: 'Physics' };
    patch.flush(updatedBook);
    http.expectOne('/api/search/books?authorName=Ada&page=1&size=5').flush(result(updatedBook, 1));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(home.results()?.books[0].bookType).toBe('Physics');
    expect(home.results()?.page).toBe(1);
    expect(home.results()?.books[0].bookDetails).toBe(book.bookDetails);

    await home.viewTopics(updatedBook);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const popup = element.querySelector<HTMLDialogElement>('.topics-dialog')!;
    popup.querySelector<HTMLInputElement>('[data-topic-id="21"]')!.click();
    const filter = popup.querySelector<HTMLInputElement>('#topic-filter')!;
    filter.value = ' Memory ';
    filter.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    const detailsField = fieldElement('topicDetails', 21);
    (detailsField.nativeElement.querySelector('.metadata-pencil') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    const textarea = popup.querySelector<HTMLTextAreaElement>('.metadata-textarea')!;
    expect(textarea.value).toBe(description);
    expect(textarea.hasAttribute('maxlength')).toBe(false);
    const changedDescription = 'Updated paragraph.\n\n' + 'Full text remains stored. '.repeat(40);
    textarea.value = changedDescription;
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    popup.querySelector<HTMLButtonElement>('.metadata-description-save')!.click();
    const topicPatch = http.expectOne('/api/books/5/topics/21/metadata');
    expect(topicPatch.request.body).toEqual({ topicDetails: changedDescription });
    const updatedTopics = {
      ...updatedBook,
      topics: updatedBook.topics.map((topic) =>
        topic.topicId === 21 ? { ...topic, topicDetails: changedDescription } : topic,
      ),
    };
    topicPatch.flush(updatedTopics);
    http
      .expectOne('/api/search/books?authorName=Ada&page=1&size=5')
      .flush(result(updatedTopics, 1));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(element.querySelector('.topics-dialog')).toBe(popup);
    expect(popup.open).toBe(true);
    expect(popup.querySelector('.description-editor')).toBeNull();
    expect(filter.value).toBe(' Memory ');
    expect(popup.textContent).toContain('No matching topics');
    const topicsComponent = fixture.debugElement.query(By.directive(BookTopicDescriptionComponent))
      .componentInstance as BookTopicDescriptionComponent;
    expect(topicsComponent.selectedIds().has(21)).toBe(true);
    expect(topicsComponent.selectedCount()).toBe(1);
    expect(home.results()?.page).toBe(1);
    expect(home.results()?.books[0].topics[0].topicDetails).toBe(changedDescription);
    http.expectNone(() => true);
  }, 15_000);

  it('does not roll back unrelated popup values when older field responses arrive last and search excludes the book', async () => {
    const fixture = TestBed.createComponent(HomeComponent);
    const home = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/search/books?size=5').flush(result());
    fixture.detectChanges();
    await fixture.whenStable();
    home.search({ topicDetailsContains: 'Memory' });
    http.expectOne('/api/search/books?topicDetailsContains=Memory&size=5').flush(result());
    await home.viewTopics(book);
    await fixture.whenStable();
    const popup = fixture.debugElement.query(By.directive(BookTopicDescriptionComponent))
      .componentInstance as BookTopicDescriptionComponent;
    popup.selectedIds.set(new Set([21]));
    popup.filterText.set('Memory');
    fixture.detectChanges();
    const olderNameResponse = {
      ...book,
      topics: book.topics.map((topic) =>
        topic.topicId === 21
          ? { ...topic, topicName: 'Changed name from the earlier save' }
          : topic,
      ),
    };
    const laterDescriptionResponse = {
      ...olderNameResponse,
      topics: olderNameResponse.topics.map((topic) =>
        topic.topicId === 21
          ? { ...topic, topicDetails: 'The newer confirmed description.' }
          : topic,
      ),
    };
    const empty: BookSearchResponse = {
      books: [],
      page: 0,
      size: 5,
      totalElements: 0,
      totalPages: 0,
    };

    home.onMetadataSaved({ book: laterDescriptionResponse, field: 'topicDetails', topicId: 21 });
    http.expectOne('/api/search/books?topicDetailsContains=Memory&size=5').flush(empty);
    fixture.detectChanges();
    await fixture.whenStable();
    home.onMetadataSaved({ book: olderNameResponse, field: 'topicName', topicId: 21 });
    http.expectOne('/api/search/books?topicDetailsContains=Memory&size=5').flush(empty);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(popup.book().topics[0].topicName).toBe('Changed name from the earlier save');
    expect(popup.book().topics[0].topicDetails).toBe('The newer confirmed description.');
    expect(popup.filterText()).toBe('Memory');
    expect(popup.selectedIds().has(21)).toBe(true);
    expect(popup.filteredTopics()).toEqual([]);
    expect(home.results()?.books).toEqual([]);
    http.expectNone(() => true);
  }, 15_000);
});
