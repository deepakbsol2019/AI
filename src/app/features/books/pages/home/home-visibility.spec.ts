import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DeferBlockBehavior, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { BookSearchComponent } from '../../components/book-search/book-search.component';
import type { Book, BookSearchResponse } from '../../models/book.models';
import { HomeComponent } from './home.component';

const storedBook = (id: number): Book => ({
  bookId: id,
  bookName: `Stored book ${id}`,
  bookType: 'Programming',
  authorName: 'Writer',
  bookDetails: 'An existing description.',
  totalPages: 12,
  bookUploadId: null,
  createdAt: null,
  updatedAt: null,
  authorDetails: null,
  topicCount: 0,
  topics: [],
});

const page = (index: number): BookSearchResponse => ({
  books:
    index === 0
      ? Array.from({ length: 5 }, (_, position) => storedBook(position + 1))
      : [storedBook(6)],
  page: index,
  size: 5,
  totalElements: 6,
  totalPages: 2,
});

describe('Home section visibility', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [HomeComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
      deferBlockBehavior: DeferBlockBehavior.Playthrough,
    });
    await TestBed.compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  for (const first of ['search', 'collection'] as const) {
    it(`preserves inputs, applied criteria and pagination when restoring ${first} first`, async () => {
      const fixture = TestBed.createComponent(HomeComponent);
      http.expectOne('/api/search/books?size=5').flush(page(0));
      fixture.detectChanges();
      await fixture.whenStable();
      const element = fixture.nativeElement as HTMLElement;
      const searchSection = element.querySelector('#library-search-section') as HTMLElement;
      const collectionSection = element.querySelector('#book-collection-section') as HTMLElement;
      const query = element.querySelector('#library-query') as HTMLInputElement;
      const author = element.querySelector('#author-name') as HTMLInputElement;
      const details = element.querySelector('app-book-search details') as HTMLDetailsElement;
      const searchComponent = fixture.debugElement.query(By.directive(BookSearchComponent))
        .componentInstance as BookSearchComponent;
      const restoreSearch = element.querySelector('.restore-search') as HTMLButtonElement;
      const restoreCollection = element.querySelector('.restore-collection') as HTMLButtonElement;
      const minimizeSearch = element.querySelector(
        '[aria-label="Minimize Search"]',
      ) as HTMLButtonElement;
      const minimizeCollection = element.querySelector(
        '[aria-label="Minimize Book Collection"]',
      ) as HTMLButtonElement;
      expect(searchSection.hidden).toBe(true);
      expect(collectionSection.hidden).toBe(false);
      expect(restoreSearch.disabled).toBe(false);
      expect(restoreCollection.disabled).toBe(true);
      const main = element.querySelector('main')!;
      expect(main.classList.contains('collection-only')).toBe(true);
      expect(main.classList.contains('search-only')).toBe(false);
      expect(element.querySelectorAll('app-book-table tbody tr')).toHaveLength(5);
      const pageSize = element.querySelector<HTMLSelectElement>('#page-size')!;
      expect(pageSize.value).toBe('5');
      expect(Array.from(pageSize.options, (option) => option.value)).toEqual([
        '5',
        '10',
        '20',
        '50',
        '100',
      ]);
      http.expectNone(() => true);
      restoreSearch.click();
      fixture.detectChanges();
      expect(searchSection.hidden).toBe(false);
      expect(restoreSearch.disabled).toBe(true);
      expect(main.classList.contains('collection-only')).toBe(false);

      query.value = '  Java  ';
      query.dispatchEvent(new Event('input', { bubbles: true }));
      author.value = 'Writer';
      author.dispatchEvent(new Event('input', { bubbles: true }));
      details.open = true;
      element
        .querySelector('app-book-search form')!
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      const applied = http.expectOne((request) => request.url === '/api/search/books');
      expect(applied.request.params.get('query')).toBe('Java');
      expect(applied.request.params.get('authorName')).toBe('Writer');
      applied.flush(page(0));
      fixture.detectChanges();
      (element.querySelector('[aria-label="Next page"]') as HTMLButtonElement).click();
      const next = http.expectOne((request) => request.url === '/api/search/books');
      expect(next.request.params.get('page')).toBe('1');
      next.flush(page(1));
      fixture.detectChanges();
      await fixture.whenStable();

      // Unsaved form edits are distinct from the applied search criteria and must also survive.
      query.value = 'Unsubmitted draft';
      query.dispatchEvent(new Event('input', { bubbles: true }));
      const formBefore = searchComponent.form.getRawValue();
      const resultsBefore = fixture.componentInstance.results();
      const tableBefore = element.querySelector('app-book-table');

      minimizeSearch.click();
      fixture.detectChanges();
      expect(searchSection.hidden).toBe(true);
      expect(collectionSection.hidden).toBe(false);
      expect(restoreSearch.disabled).toBe(false);
      expect(restoreCollection.disabled).toBe(true);
      expect(main.classList.contains('collection-only')).toBe(true);
      http.expectNone(() => true);

      restoreSearch.click();
      fixture.detectChanges();
      expect(searchSection.hidden).toBe(false);
      expect(restoreSearch.disabled).toBe(true);
      expect(main.classList.contains('collection-only')).toBe(false);
      expect(element.querySelector('#library-query')).toBe(query);

      minimizeCollection.click();
      fixture.detectChanges();
      expect(searchSection.hidden).toBe(false);
      expect(collectionSection.hidden).toBe(true);
      expect(restoreCollection.disabled).toBe(false);
      expect(main.classList.contains('search-only')).toBe(true);
      expect(main.classList.contains('collection-only')).toBe(false);
      minimizeSearch.click();
      fixture.detectChanges();
      expect(searchSection.hidden).toBe(true);
      expect(collectionSection.hidden).toBe(true);
      expect(restoreSearch.disabled).toBe(false);
      expect(restoreCollection.disabled).toBe(false);
      expect(main.classList.contains('search-only')).toBe(false);
      expect(main.classList.contains('collection-only')).toBe(false);
      expect(element.querySelectorAll('main > :not([hidden]):not(.visually-hidden)')).toHaveLength(
        0,
      );
      expect(element.querySelector('app-header')?.closest('[hidden]')).toBeNull();
      expect(element.querySelector('app-footer')?.closest('[hidden]')).toBeNull();

      const restoreFirst = first === 'search' ? restoreSearch : restoreCollection;
      const restoreSecond = first === 'search' ? restoreCollection : restoreSearch;
      restoreFirst.click();
      fixture.detectChanges();
      expect(restoreFirst.disabled).toBe(true);
      expect(restoreSecond.disabled).toBe(false);
      expect(main.classList.contains('search-only')).toBe(first === 'search');
      expect(main.classList.contains('collection-only')).toBe(first === 'collection');
      restoreSecond.click();
      fixture.detectChanges();
      expect(restoreSearch.disabled).toBe(true);
      expect(restoreCollection.disabled).toBe(true);
      expect(main.classList.contains('search-only')).toBe(false);
      expect(main.classList.contains('collection-only')).toBe(false);
      expect(searchSection.hidden).toBe(false);
      expect(collectionSection.hidden).toBe(false);
      expect(
        searchSection.compareDocumentPosition(collectionSection) & Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
      expect(element.querySelector('#library-query')).toBe(query);
      expect(element.querySelector('app-book-search details')).toBe(details);
      expect(details.open).toBe(true);
      expect(fixture.debugElement.query(By.directive(BookSearchComponent)).componentInstance).toBe(
        searchComponent,
      );
      expect(searchComponent.form.getRawValue()).toEqual(formBefore);
      expect(element.querySelector('app-book-table')).toBe(tableBefore);
      expect(fixture.componentInstance.results()).toBe(resultsBefore);
      expect(fixture.componentInstance.results()?.page).toBe(1);
      expect(element.querySelector('.page-label')?.textContent?.trim()).toBe('2 / 2');
      http.expectNone(() => true);

      // An explicit reload proves that visibility changes retained the applied criteria/page,
      // while leaving the unsubmitted input edit intact. Visibility itself made no requests.
      fixture.componentInstance.reload();
      const reload = http.expectOne((request) => request.url === '/api/search/books');
      expect(reload.request.params.get('query')).toBe('Java');
      expect(reload.request.params.get('authorName')).toBe('Writer');
      expect(reload.request.params.get('page')).toBe('1');
      reload.flush(page(1));
      expect(searchComponent.form.controls.query.value).toBe('Unsubmitted draft');
    }, 15_000);
  }
});
