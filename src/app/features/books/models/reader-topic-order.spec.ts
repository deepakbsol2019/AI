import type { Book, BookTopic } from './book.models';
import { sourceOrderedTopics } from './reader-topic-order';

const topic = (
  topicId: number,
  startPage: number | null,
  endPage: number | null,
  topicName = `Chapter ${topicId}`,
): BookTopic => ({
  topicId,
  topicName,
  topicDetails: null,
  startPage,
  endPage,
  printedStartPage: null,
  printedEndPage: null,
  createdAt: null,
  updatedAt: null,
});
const sourceBook = (topics?: BookTopic[]): Book => ({
  bookId: 8,
  bookName: 'Source book',
  bookType: 'Physics',
  authorName: null,
  bookDetails: null,
  totalPages: 100,
  bookUploadId: null,
  authorDetails: null,
  createdAt: null,
  updatedAt: null,
  topicCount: topics?.length ?? 3,
  topics: topics ?? [topic(1, 23, 54), topic(3, 55, 100), topic(90, 1, 22, 'Source book_offset')],
});

describe('sourceOrderedTopics', () => {
  it('puts front matter first even when its offset row was persisted after chapter IDs', () => {
    const book = sourceBook();
    Object.freeze(book.topics);
    const requested = Object.freeze([book.topics[1], book.topics[2]]);
    expect(sourceOrderedTopics(book, requested).map((value) => value.topicId)).toEqual([90, 3]);
    expect(book.topics.map((value) => value.topicId)).toEqual([1, 3, 90]);
    expect(requested.map((value) => value.topicId)).toEqual([3, 90]);
  });

  it('orders by physical start, end, reserved offset on a tie, then persistent ID', () => {
    const book = sourceBook([
      topic(7, 23, 40),
      topic(2, 1, 22),
      topic(90, 1, 22, 'Source book_offset'),
      topic(1, 1, 22),
      topic(9, 23, 30),
    ]);
    expect(
      sourceOrderedTopics(book, [...book.topics].reverse()).map((value) => value.topicId),
    ).toEqual([90, 1, 2, 9, 7]);
  });

  it('honors the persisted empty technical offset range without accepting an ordinary reversed range', () => {
    const book = sourceBook([topic(7, 1, 40), topic(90, 1, 0, 'Source book_offset')]);
    expect(sourceOrderedTopics(book, book.topics).map((value) => value.topicId)).toEqual([90, 7]);
    book.topics[1].topicName = 'Ordinary chapter';
    expect(sourceOrderedTopics(book, book.topics).map((value) => value.topicId)).toEqual([7, 90]);
  });

  it.each<Partial<BookTopic>>([
    { startPage: null },
    { endPage: null },
    { startPage: 0 },
    { startPage: -1 },
    { startPage: 60, endPage: 59 },
    { endPage: 101 },
    { startPage: 2.5 },
    { endPage: Number.POSITIVE_INFINITY },
  ])(
    'preserves book order when selected source bounds are unavailable or invalid: %j',
    (invalid) => {
      const book = sourceBook();
      book.topics[1] = { ...book.topics[1], ...invalid };
      expect(
        sourceOrderedTopics(book, [book.topics[2], book.topics[1]]).map((value) => value.topicId),
      ).toEqual([3, 90]);
      expect(book.topics[1]).toMatchObject(invalid);
    },
  );

  it('keeps a single scope intact and de-duplicates selection using persisted topic membership', () => {
    const book = sourceBook();
    book.topics[1].startPage = null;
    expect(sourceOrderedTopics(book, [book.topics[1]])).toEqual([book.topics[1]]);
    expect(sourceOrderedTopics(book, [book.topics[2], book.topics[2]])).toEqual([book.topics[2]]);
    expect(sourceOrderedTopics(book, [topic(999, 1, 2)])).toEqual([]);
  });
});
