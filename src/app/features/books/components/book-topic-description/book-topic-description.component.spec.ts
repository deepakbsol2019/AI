import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BookDownloadService } from '../../data-access/book-download.service';
import type { Book } from '../../models/book.models';
import { BookTopicDescriptionComponent } from './book-topic-description.component';

const firstTopic = {
  topicId: 12,
  topicName: 'Alpha topic',
  topicDetails: 'Memory collections',
  startPage: 1,
  endPage: 4,
  printedStartPage: null,
  printedEndPage: null,
  createdAt: null,
  updatedAt: null,
};
const selectedBook: Book = {
  bookId: 7,
  bookName: 'Selected book',
  bookType: null,
  authorName: 'Selected author',
  bookDetails: 'A book',
  totalPages: 10,
  bookUploadId: 1,
  createdAt: null,
  updatedAt: null,
  authorDetails: null,
  topicCount: 3,
  topics: [
    firstTopic,
    { ...firstTopic, topicId: 27, topicName: 'Beta topic', topicDetails: null },
    { ...firstTopic, topicId: 41, topicName: 'Gamma topic', topicDetails: 'Concurrency basics' },
  ],
};

describe('BookTopicDescriptionComponent', () => {
  const bulkBusy = signal(false);
  const individualBusy = signal<ReadonlySet<number>>(new Set());
  const downloads = {
    downloadBook: vi.fn(),
    downloadTopic: vi.fn(),
    downloadSelectedTopics: vi.fn(),
    isBookDownloading: () => false,
    isTopicDownloading: (_bookId: number, topicId: number) => individualBusy().has(topicId),
    isSelectedTopicsDownloading: () => bulkBusy(),
    isAnyTopicDownloading: () => bulkBusy() || individualBusy().size > 0,
    error: signal<string | null>(null),
    message: signal<string | null>(null),
  };

  beforeEach(() => {
    vi.clearAllMocks();
    bulkBusy.set(false);
    individualBusy.set(new Set());
    downloads.error.set(null);
    downloads.message.set(null);
    downloads.downloadSelectedTopics.mockImplementation(() => undefined);
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
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: BookDownloadService, useValue: downloads },
      ],
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  function create(book = selectedBook): ComponentFixture<BookTopicDescriptionComponent> {
    const fixture = TestBed.createComponent(BookTopicDescriptionComponent);
    fixture.componentRef.setInput('book', book);
    fixture.detectChanges();
    return fixture;
  }

  function select(fixture: ComponentFixture<BookTopicDescriptionComponent>, id: number): void {
    (fixture.nativeElement.querySelector(`[data-topic-id="${id}"]`) as HTMLInputElement).click();
    fixture.detectChanges();
  }

  function filter(fixture: ComponentFixture<BookTopicDescriptionComponent>, query: string): void {
    const input = fixture.nativeElement.querySelector('#topic-filter') as HTMLInputElement;
    input.value = query;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
  }

  it('renders four topic columns and preserves individual and complete-book actions', async () => {
    const fixture = create();
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('h2')?.textContent).toContain('Selected book');
    expect(Array.from(element.querySelectorAll('th'), (th) => th.textContent?.trim())).toEqual([
      'Select topic',
      'Topic Name',
      'Topic Description',
      'View / Download Topic',
    ]);
    (element.querySelector('.topic-action button:not(.topic-view)') as HTMLButtonElement).click();
    expect(downloads.downloadTopic).toHaveBeenCalledWith(selectedBook, selectedBook.topics[0]);
    (element.querySelector('.book-download') as HTMLButtonElement).click();
    expect(downloads.downloadBook).toHaveBeenCalledWith(selectedBook);
  });

  it('applies selection rules immediately through zero, one, two, one, and zero', () => {
    const fixture = create();
    const element = fixture.nativeElement as HTMLElement;
    const bulk = element.querySelector('.selected-download') as HTMLButtonElement;
    const bulkView = element.querySelector('.selected-view') as HTMLButtonElement;
    const individualViews = Array.from(element.querySelectorAll<HTMLButtonElement>('.topic-view'));
    const individuals = Array.from(
      element.querySelectorAll<HTMLButtonElement>('.topic-action button:not(.topic-view)'),
    );
    const state = (count: number) => {
      expect(fixture.componentInstance.selectedCount()).toBe(count);
      expect(bulk.disabled).toBe(count < 2);
      expect(bulkView.disabled).toBe(count < 2);
      expect(individuals).toHaveLength(3);
      expect(individuals.every((button) => button.disabled === count >= 2)).toBe(true);
      expect(individualViews.every((button) => button.disabled === count >= 2)).toBe(true);
    };
    state(0);
    select(fixture, 12);
    state(1);
    select(fixture, 27);
    state(2);
    select(fixture, 12);
    state(1);
    select(fixture, 27);
    state(0);
  });

  it('filters live by trimmed case-insensitive name or description without requests or mutating topics', () => {
    const fixture = create();
    const original = selectedBook.topics;
    filter(fixture, '  MEMORY  ');
    expect(fixture.componentInstance.filteredTopics().map((topic) => topic.topicId)).toEqual([12]);
    filter(fixture, '  beTA  ');
    expect(fixture.componentInstance.filteredTopics().map((topic) => topic.topicId)).toEqual([27]);
    (fixture.nativeElement.querySelector('.topic-filter') as HTMLFormElement).dispatchEvent(
      new Event('submit', { cancelable: true }),
    );
    fixture.detectChanges();
    expect(fixture.componentInstance.filterText()).toBe('beTA');
    filter(fixture, 'unmatched phrase');
    expect(fixture.nativeElement.textContent).toContain('No matching topics');
    filter(fixture, '  ');
    expect(fixture.componentInstance.filteredTopics()).toHaveLength(3);
    expect(selectedBook.topics).toBe(original);
    expect(original[1].topicDetails).toBeNull();
    expect(downloads.downloadBook).not.toHaveBeenCalled();
    expect(downloads.downloadTopic).not.toHaveBeenCalled();
    expect(downloads.downloadSelectedTopics).not.toHaveBeenCalled();
  });

  it('counts hidden selections and submits selected topics in original book order', () => {
    const fixture = create();
    select(fixture, 41);
    select(fixture, 12);
    filter(fixture, 'Beta');
    expect(fixture.componentInstance.filteredTopics().map((topic) => topic.topicId)).toEqual([27]);
    expect(fixture.nativeElement.querySelector('#selection-count')?.textContent).toContain(
      '2 selected',
    );
    expect(
      (fixture.nativeElement.querySelector('.topic-action button') as HTMLButtonElement).disabled,
    ).toBe(true);
    (fixture.nativeElement.querySelector('.selected-download') as HTMLButtonElement).click();
    expect(downloads.downloadSelectedTopics).toHaveBeenCalledWith(selectedBook, [
      selectedBook.topics[0],
      selectedBook.topics[2],
    ]);
    filter(fixture, '');
    expect(
      (fixture.nativeElement.querySelector('[data-topic-id="12"]') as HTMLInputElement).checked,
    ).toBe(true);
    expect(
      (fixture.nativeElement.querySelector('[data-topic-id="41"]') as HTMLInputElement).checked,
    ).toBe(true);
  });

  it('locks selection during a batch, prevents repeat submissions, and preserves selection after failure', () => {
    downloads.downloadSelectedTopics.mockImplementation(() => {
      bulkBusy.set(true);
    });
    const fixture = create();
    select(fixture, 12);
    select(fixture, 27);
    const element = fixture.nativeElement as HTMLElement;
    const bulk = element.querySelector('.selected-download') as HTMLButtonElement;
    bulk.click();
    fixture.detectChanges();
    expect(bulk.disabled).toBe(true);
    expect(
      Array.from(element.querySelectorAll<HTMLInputElement>('tbody input')).every(
        (input) => input.disabled,
      ),
    ).toBe(true);
    expect((element.querySelector('.book-download') as HTMLButtonElement).disabled).toBe(false);
    fixture.componentInstance.downloadSelected();
    expect(downloads.downloadSelectedTopics).toHaveBeenCalledTimes(1);
    bulkBusy.set(false);
    downloads.error.set('Please retry this download.');
    fixture.detectChanges();
    expect(fixture.componentInstance.selectedCount()).toBe(2);
    expect(downloads.error()).toContain('Please retry');
    expect(bulk.disabled).toBe(false);
    bulk.click();
    expect(downloads.downloadSelectedTopics).toHaveBeenCalledTimes(2);
  });

  it('respects an active individual download while keeping the complete-book action independent', () => {
    individualBusy.set(new Set([12]));
    const fixture = create();
    const element = fixture.nativeElement as HTMLElement;
    const individuals = element.querySelectorAll<HTMLButtonElement>(
      '.topic-action button:not(.topic-view)',
    );
    expect(individuals[0].disabled).toBe(true);
    expect(individuals[1].disabled).toBe(false);
    select(fixture, 12);
    select(fixture, 27);
    expect((element.querySelector('.selected-download') as HTMLButtonElement).disabled).toBe(true);
    expect((element.querySelector('.book-download') as HTMLButtonElement).disabled).toBe(false);
    individualBusy.set(new Set());
    fixture.detectChanges();
    expect((element.querySelector('.selected-download') as HTMLButtonElement).disabled).toBe(false);
  });

  it('clears filter and selection when the book changes and when the native modal closes', async () => {
    const fixture = create();
    await fixture.whenStable();
    select(fixture, 12);
    filter(fixture, 'Alpha');
    fixture.componentRef.setInput('book', { ...selectedBook, bookId: 88 });
    fixture.detectChanges();
    expect(fixture.componentInstance.selectedCount()).toBe(0);
    expect(fixture.componentInstance.filterText()).toBe('');
    select(fixture, 12);
    filter(fixture, 'Alpha');
    const closed = vi.fn();
    fixture.componentInstance.closed.subscribe(closed);
    fixture.componentInstance.close();
    expect(fixture.componentInstance.selectedCount()).toBe(0);
    expect(fixture.componentInstance.filterText()).toBe('');
    expect(closed).toHaveBeenCalledTimes(1);
    fixture.destroy();
    expect(closed).toHaveBeenCalledTimes(1);
    const reopened = create();
    expect(reopened.componentInstance.selectedCount()).toBe(0);
    expect(reopened.componentInstance.filterText()).toBe('');
  });

  it('keeps filter and stable selected IDs when confirmed metadata changes on the same book', () => {
    const fixture = create();
    select(fixture, 12);
    filter(fixture, 'Memory');
    fixture.componentRef.setInput('book', {
      ...selectedBook,
      topics: selectedBook.topics.map((topic) =>
        topic.topicId === 12
          ? { ...topic, topicName: 'Renamed topic', topicDetails: 'A changed full description.' }
          : topic,
      ),
    });
    fixture.detectChanges();
    expect(fixture.componentInstance.filterText()).toBe('Memory');
    expect(fixture.componentInstance.selectedIds().has(12)).toBe(true);
    expect(fixture.componentInstance.selectedCount()).toBe(1);
    expect(fixture.componentInstance.filteredTopics()).toHaveLength(0);
    expect(fixture.nativeElement.textContent).toContain('No matching topics');
    TestBed.inject(HttpTestingController).expectNone(() => true);
  });

  it('requests complete topic reading in original order without clearing popup state or downloading', () => {
    const fixture = create();
    const read = vi.fn();
    fixture.componentInstance.readRequested.subscribe(read);
    const element = fixture.nativeElement as HTMLElement;
    (element.querySelector('.selected-view') as HTMLButtonElement).click();
    expect(read).not.toHaveBeenCalled();
    (element.querySelector('.topic-view') as HTMLButtonElement).click();
    expect(read).toHaveBeenLastCalledWith({
      mode: 'topic',
      book: selectedBook,
      topics: [firstTopic],
    });
    select(fixture, 41);
    select(fixture, 12);
    filter(fixture, 'Beta');
    (element.querySelector('.topic-view') as HTMLButtonElement).click();
    expect(read).toHaveBeenCalledTimes(1);
    (element.querySelector('.selected-view') as HTMLButtonElement).click();
    expect(read).toHaveBeenLastCalledWith({
      mode: 'selected-topics',
      book: selectedBook,
      topics: [selectedBook.topics[0], selectedBook.topics[2]],
    });
    expect(fixture.componentInstance.filterText()).toBe('Beta');
    expect(fixture.componentInstance.selectedCount()).toBe(2);
    expect(downloads.downloadTopic).not.toHaveBeenCalled();
    expect(downloads.downloadSelectedTopics).not.toHaveBeenCalled();
  });

  it('shows a safe empty state with a visible disabled selected-download action', () => {
    const fixture = create({ ...selectedBook, topics: [], topicCount: 0 });
    filter(fixture, 'anything');
    expect(fixture.nativeElement.textContent).toContain('No topics available');
    expect(
      (fixture.nativeElement.querySelector('.selected-download') as HTMLButtonElement).disabled,
    ).toBe(true);
    expect(fixture.nativeElement.querySelector('tbody td')?.getAttribute('colspan')).toBe('4');
  });

  it('releases background scrolling on native Escape-style close and direct destruction', async () => {
    const region = document.createElement('div');
    region.dataset['libraryScrollRegion'] = '';
    region.style.setProperty('overflow-y', 'auto', 'important');
    region.scrollTop = 137;
    document.body.appendChild(region);
    const fixture = create();
    await fixture.whenStable();
    expect(region.style.getPropertyValue('overflow')).toBe('hidden');
    expect(document.body.style.getPropertyValue('overflow')).toBe('hidden');
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    dialog.close();
    expect(region.style.getPropertyValue('overflow-y')).toBe('auto');
    expect(region.style.getPropertyPriority('overflow-y')).toBe('important');
    expect(region.scrollTop).toBe(137);
    const second = create();
    await second.whenStable();
    expect(region.style.getPropertyValue('overflow')).toBe('hidden');
    second.destroy();
    expect(region.style.getPropertyValue('overflow-y')).toBe('auto');
    expect(region.scrollTop).toBe(137);
    region.remove();
  });
});
