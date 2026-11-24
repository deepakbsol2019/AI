import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { BookSearchResponse } from '../../models/book.models';
import { BookUploadService } from '../../services/book-upload.service';
import { HomeComponent } from './home.component';

describe('Home search orchestration', () => {
  const completed = new Subject<void>();
  let http: HttpTestingController;
  const response = (page = 0): BookSearchResponse => ({
    books: [],
    page,
    size: 20,
    totalElements: 42,
    totalPages: 3,
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

  it('requests the default page once without criteria and uses server pagination', () => {
    const fixture = TestBed.createComponent(HomeComponent);
    const request = http.expectOne('/api/search/books');
    expect(request.request.body).toBeNull();
    request.flush(response());
    fixture.componentInstance.changePage(1);
    http.expectOne('/api/search/books?page=1').flush(response(1));
    expect(fixture.componentInstance.results()?.page).toBe(1);
  });

  it('cancels stale searches and Reset reloads with no filters', () => {
    const home = TestBed.createComponent(HomeComponent).componentInstance;
    http.expectOne('/api/search/books').flush(response());
    home.search({ query: 'earlier' });
    const earlier = http.expectOne('/api/search/books?query=earlier');
    home.search({ query: 'current' });
    expect(earlier.cancelled).toBe(true);
    http.expectOne('/api/search/books?query=current').flush(response());
    home.reset();
    http.expectOne('/api/search/books').flush(response());
    expect(home.activeFilterCount()).toBe(0);
  });

  it('refreshes the existing filters and page after upload success', () => {
    const home = TestBed.createComponent(HomeComponent).componentInstance;
    http.expectOne('/api/search/books').flush(response());
    home.search({ authorName: 'Ada' });
    http.expectOne('/api/search/books?authorName=Ada').flush(response());
    home.changePage(1);
    http.expectOne('/api/search/books?authorName=Ada&page=1').flush(response(1));
    completed.next();
    http.expectOne('/api/search/books?authorName=Ada&page=1').flush(response(1));
    expect(home.results()?.page).toBe(1);
    expect(home.activeFilterCount()).toBe(1);
  });

  it('recovers from a search error with Retry', async () => {
    const fixture = TestBed.createComponent(HomeComponent);
    const home = fixture.componentInstance;
    http
      .expectOne('/api/search/books')
      .flush({ message: 'Try later' }, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect(home.error()).toBe('Try later');
    home.reload();
    http.expectOne('/api/search/books').flush(response());
    expect(home.error()).toBeNull();
  });
});
