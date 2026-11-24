import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { BookDownloadService } from '../../data-access/book-download.service';
import type { Book } from '../../models/book.models';
import { BookTopicDescriptionComponent } from './book-topic-description.component';

const selectedBook: Book = {
  bookId: 7,
  bookName: 'Selected book',
  authorName: 'Selected author',
  bookDetails: 'A book',
  totalPages: 10,
  bookUploadId: 1,
  createdAt: null,
  updatedAt: null,
  authorDetails: null,
  topicCount: 1,
  topics: [
    {
      topicId: 12,
      topicName: 'Only this topic',
      topicDetails: 'Topic text',
      startPage: 1,
      endPage: 10,
      printedStartPage: null,
      printedEndPage: null,
      createdAt: null,
      updatedAt: null,
    },
  ],
};

describe('BookTopicDescriptionComponent', () => {
  const downloads = {
    downloadBook: vi.fn(),
    downloadTopic: vi.fn(),
    isBookDownloading: () => false,
    isTopicDownloading: () => false,
    error: signal<string | null>(null),
  };

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
        if (this.open) {
          this.open = false;
          this.dispatchEvent(new Event('close'));
        }
      },
    });
    TestBed.configureTestingModule({
      imports: [BookTopicDescriptionComponent],
      providers: [{ provide: BookDownloadService, useValue: downloads }],
    });
  });

  it('renders the selected book and exactly three topic columns, and downloads only the chosen topic', async () => {
    const fixture = TestBed.createComponent(BookTopicDescriptionComponent);
    fixture.componentRef.setInput('book', selectedBook);
    fixture.detectChanges();
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('h2')?.textContent).toContain('Selected book');
    expect(Array.from(element.querySelectorAll('th'), (th) => th.textContent)).toEqual([
      'Topic Name',
      'Topic Description',
      'Download Topic',
    ]);
    (element.querySelector('.topic-action button') as HTMLButtonElement).click();
    expect(downloads.downloadTopic).toHaveBeenCalledWith(selectedBook, selectedBook.topics[0]);
    (element.querySelector('.header-actions .btn') as HTMLButtonElement).click();
    expect(downloads.downloadBook).toHaveBeenCalledWith(selectedBook);
    fixture.destroy();
  });

  it('emits close once when the native modal closes', async () => {
    const fixture = TestBed.createComponent(BookTopicDescriptionComponent);
    fixture.componentRef.setInput('book', selectedBook);
    const closed = vi.fn();
    fixture.componentInstance.closed.subscribe(closed);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.componentInstance.close();
    expect(closed).toHaveBeenCalledTimes(1);
    fixture.destroy();
    expect(closed).toHaveBeenCalledTimes(1);
  });
});
