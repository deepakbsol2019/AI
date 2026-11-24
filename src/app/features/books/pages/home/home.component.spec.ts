import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { Book, BookSearchResponse } from '../../models/book.models';
import { BookUploadService } from '../../services/book-upload.service';
import { HomeComponent } from './home.component';

describe('Home search orchestration', () => {
  const completed = new Subject<void>();
  let http: HttpTestingController;
  const response = (page = 0, size = 5): BookSearchResponse => ({
    books: [],
    page,
    size,
    totalElements: 12,
    totalPages: Math.ceil(12 / size),
  });

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [HomeComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: BookUploadService,
          useValue: { completed$: completed.asObservable() },
        },
      ],
    });
    TestBed.overrideComponent(HomeComponent, { set: { template: '', imports: [] } });
    await TestBed.compileComponents();
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('starts with Search minimized, requests five items once and uses server pagination', () => {
    const fixture = TestBed.createComponent(HomeComponent);
    const request = http.expectOne('/api/search/books?size=5');
    expect(request.request.body).toBeNull();
    expect(fixture.componentInstance.searchMinimized()).toBe(true);
    expect(fixture.componentInstance.collectionMinimized()).toBe(false);
    expect(fixture.componentInstance.pageSize()).toBe(5);
    request.flush(response());
    fixture.componentInstance.changePage(1);
    http.expectOne('/api/search/books?page=1&size=5').flush(response(1));
    expect(fixture.componentInstance.results()?.page).toBe(1);
    expect(fixture.componentInstance.searchMinimized()).toBe(true);
  });

  it('retains selected larger sizes through search, refresh and Reset, resetting page on size changes', () => {
    const home = TestBed.createComponent(HomeComponent).componentInstance;
    http.expectOne('/api/search/books?size=5').flush(response());
    home.searchMinimized.set(false);
    home.search({ bookName: 'Java' });
    http.expectOne('/api/search/books?bookName=Java&size=5').flush(response());
    home.changePage(1);
    http.expectOne('/api/search/books?bookName=Java&page=1&size=5').flush(response(1));
    home.changeSize({ target: { value: '10' } } as unknown as Event);
    http.expectOne('/api/search/books?bookName=Java&size=10').flush(response(0, 10));
    expect(home.pageSize()).toBe(10);
    expect(home.results()?.page).toBe(0);
    expect(home.results()?.totalElements).toBe(12);
    expect(home.results()?.totalPages).toBe(2);
    home.changePage(1);
    http.expectOne('/api/search/books?bookName=Java&page=1&size=10').flush(response(1, 10));
    completed.next();
    http.expectOne('/api/search/books?bookName=Java&page=1&size=10').flush(response(1, 10));
    home.search({ query: '  physics  ' });
    http.expectOne('/api/search/books?query=physics&size=10').flush(response(0, 10));
    home.reset();
    http.expectOne('/api/search/books?size=10').flush(response(0, 10));
    expect(home.pageSize()).toBe(10);
    expect(home.searchMinimized()).toBe(false);
    expect(home.activeFilterCount()).toBe(0);
  });

  it('cancels stale searches and Reset reloads with no filters', () => {
    const home = TestBed.createComponent(HomeComponent).componentInstance;
    http.expectOne('/api/search/books?size=5').flush(response());
    home.search({ query: 'earlier' });
    const earlier = http.expectOne('/api/search/books?query=earlier&size=5');
    home.search({ query: 'current' });
    expect(earlier.cancelled).toBe(true);
    http.expectOne('/api/search/books?query=current&size=5').flush(response());
    home.reset();
    http.expectOne('/api/search/books?size=5').flush(response());
    expect(home.activeFilterCount()).toBe(0);
  });

  it('refreshes the existing filters and page after upload success', () => {
    const home = TestBed.createComponent(HomeComponent).componentInstance;
    http.expectOne('/api/search/books?size=5').flush(response());
    home.search({ authorName: 'Ada' });
    http.expectOne('/api/search/books?authorName=Ada&size=5').flush(response());
    home.changePage(1);
    http.expectOne('/api/search/books?authorName=Ada&page=1&size=5').flush(response(1));
    completed.next();
    http.expectOne('/api/search/books?authorName=Ada&page=1&size=5').flush(response(1));
    expect(home.results()?.page).toBe(1);
    expect(home.activeFilterCount()).toBe(1);
  });

  it('recovers from a search error with Retry', async () => {
    const fixture = TestBed.createComponent(HomeComponent);
    const home = fixture.componentInstance;
    http
      .expectOne('/api/search/books?size=5')
      .flush({ message: 'Try later' }, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect(home.error()).toBe('Try later');
    home.reload();
    http.expectOne('/api/search/books?size=5').flush(response());
    expect(home.error()).toBeNull();
  });

  it('reconciles a confirmed edit against existing filters and returns from an emptied page', () => {
    const home = TestBed.createComponent(HomeComponent).componentInstance;
    http.expectOne('/api/search/books?size=5').flush(response());
    home.search({ bookName: 'Original' });
    http.expectOne('/api/search/books?bookName=Original&size=5').flush(response());
    home.changePage(2);
    http.expectOne('/api/search/books?bookName=Original&page=2&size=5').flush(response(2));
    const edited: Book = {
      bookId: 1,
      bookName: 'Renamed',
      bookType: 'Unknown',
      authorName: null,
      bookDetails: null,
      totalPages: 1,
      bookUploadId: null,
      createdAt: null,
      updatedAt: null,
      authorDetails: null,
      topicCount: 0,
      topics: [],
    };
    home.onMetadataSaved({ book: edited, field: 'bookName' });
    http
      .expectOne('/api/search/books?bookName=Original&page=2&size=5')
      .flush({ ...response(2), totalElements: 0, totalPages: 0 });
    http
      .expectOne('/api/search/books?bookName=Original&size=5')
      .flush({ ...response(), totalElements: 0, totalPages: 0 });
    expect(home.results()?.page).toBe(0);
    expect(home.results()?.books).toEqual([]);
    expect(home.activeFilterCount()).toBe(1);
  });
});
