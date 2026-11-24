import { TestBed } from '@angular/core/testing';
import { BookDownloadService } from '../../data-access/book-download.service';
import type { Book } from '../../models/book.models';
import { BookTableComponent } from './book-table.component';

const selectedBook: Book = {
  bookId: 18,
  bookName: 'A selected book',
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
      providers: [{ provide: BookDownloadService, useValue: downloads }],
    });
  });

  it('keeps exactly five columns and passes the selected book to each action', () => {
    const fixture = TestBed.createComponent(BookTableComponent);
    fixture.componentRef.setInput('books', [selectedBook]);
    const viewed = vi.fn();
    fixture.componentInstance.viewBook.subscribe(viewed);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(Array.from(element.querySelectorAll('th'), (th) => th.textContent)).toEqual([
      'Book Name',
      'Author Name',
      'Book Description',
      'Download Book',
      'View Book',
    ]);
    (element.querySelector('tbody .btn-outline-primary') as HTMLButtonElement).click();
    expect(downloads.downloadBook).toHaveBeenCalledWith(selectedBook);
    (element.querySelector('tbody .view-button') as HTMLButtonElement).click();
    expect(viewed).toHaveBeenCalledWith(selectedBook);
  });

  it('distinguishes first loading from an empty result and keeps existing rows during refresh', () => {
    const fixture = TestBed.createComponent(BookTableComponent);
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('Finding books');
    expect(element.textContent).not.toContain('No matching books');
    fixture.componentRef.setInput('busy', false);
    fixture.detectChanges();
    expect(element.textContent).toContain('No matching books');
    fixture.componentRef.setInput('books', [selectedBook]);
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();
    expect(element.textContent).toContain('Updating your books');
    expect(element.textContent).toContain(selectedBook.bookName);
  });
});
