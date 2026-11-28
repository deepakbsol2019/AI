import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BookDownloadService } from '../../data-access/book-download.service';
import type { Book } from '../../models/book.models';
import { BookTableComponent } from './book-table.component';

const selectedBook: Book = {
  bookId: 18,
  bookName: 'A selected book',
  bookType: '  Reference  ',
  authorName: null,
  bookDetails: 'Book description',
  totalPages: 10,
  bookUploadId: null,
  createdAt: null,
  updatedAt: null,
  authorDetails: null,
  topicCount: 0,
  topics: [],
};

describe('BookTableComponent', () => {
  const downloads = { downloadBook: vi.fn(), isBookDownloading: () => false };

  beforeEach(() => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      imports: [BookTableComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: BookDownloadService, useValue: downloads },
      ],
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('places Book Type after Book Name and keeps the existing book actions', () => {
    const fixture = TestBed.createComponent(BookTableComponent);
    fixture.componentRef.setInput('books', [selectedBook]);
    const viewed = vi.fn();
    const topicsViewed = vi.fn();
    fixture.componentInstance.viewBook.subscribe(viewed);
    fixture.componentInstance.viewTopics.subscribe(topicsViewed);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(Array.from(element.querySelectorAll('th'), (th) => th.textContent)).toEqual([
      'Book Name',
      'Book Type',
      'Author Name',
      'Book Description',
      'Download Book',
      'View Book',
      'View Topic',
      'Delete',
    ]);
    expect(element.querySelector('tbody .type-cell .truncated-value')?.textContent?.trim()).toBe(
      'Reference',
    );
    expect(element.querySelectorAll('tbody td')).toHaveLength(8);
    (element.querySelector('tbody .btn-outline-primary') as HTMLButtonElement).click();
    expect(downloads.downloadBook).toHaveBeenCalledWith(selectedBook);
    (element.querySelector('tbody .view-button') as HTMLButtonElement).click();
    expect(viewed).toHaveBeenCalledWith(selectedBook);
    (element.querySelector('tbody .view-topics-button') as HTMLButtonElement).click();
    expect(topicsViewed).toHaveBeenCalledWith(selectedBook);
    expect(viewed).toHaveBeenCalledTimes(1);
  });

  it('displays Unknown for null, empty, whitespace, and legacy omitted book types', () => {
    const fixture = TestBed.createComponent(BookTableComponent);
    const { bookType: _bookType, ...legacyBook } = selectedBook;
    const books = [
      { ...selectedBook, bookId: 1, bookType: null },
      { ...selectedBook, bookId: 2, bookType: '' },
      { ...selectedBook, bookId: 3, bookType: '   ' },
      { ...legacyBook, bookId: 4 },
      { ...selectedBook, bookId: 5, bookType: '  Science & Technology  ' },
    ];
    fixture.componentRef.setInput('books', books);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(
      Array.from(element.querySelectorAll('.type-cell .truncated-value'), (cell) =>
        cell.textContent?.trim(),
      ),
    ).toEqual(['Unknown', 'Unknown', 'Unknown', 'Unknown', 'Science & Technology']);
  });

  it('distinguishes first loading from an empty result and keeps existing rows during refresh', () => {
    const fixture = TestBed.createComponent(BookTableComponent);
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('Finding books');
    expect(element.querySelector('.book-scroll-area')?.classList.contains('no-books')).toBe(true);
    expect(element.querySelector('tbody td')?.getAttribute('colspan')).toBe('8');
    expect(element.textContent).not.toContain('No matching books');
    fixture.componentRef.setInput('busy', false);
    fixture.detectChanges();
    expect(element.textContent).toContain('No matching books');
    expect(element.querySelector('.book-scroll-area')?.classList.contains('no-books')).toBe(true);
    fixture.componentRef.setInput('books', [selectedBook]);
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();
    expect(element.textContent).toContain('Updating your books');
    expect(element.textContent).toContain(selectedBook.bookName);
    expect(element.querySelector('.book-scroll-area')?.classList.contains('no-books')).toBe(false);
  });
});
