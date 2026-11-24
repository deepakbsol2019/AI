import type { Book, BookTopic } from './book.models';
import { mergeMetadataSave } from './metadata-save';

const older = '2026-11-24T10:00:00.123100';
const newer = '2026-11-24T10:00:00.123900';
const topic = (topicId: number, topicName: string, topicDetails: string | null): BookTopic => ({
  topicId,
  topicName,
  topicDetails,
  startPage: 2,
  endPage: 10,
  printedStartPage: 1,
  printedEndPage: 9,
  createdAt: older,
  updatedAt: older,
});
function book(): Book {
  return {
    bookId: 7,
    bookName: 'Book',
    bookType: 'History',
    authorName: 'Author',
    bookDetails: 'Original book details',
    totalPages: 10,
    bookUploadId: 3,
    createdAt: older,
    updatedAt: older,
    authorDetails: { authorId: 8, authorName: 'Author', createdAt: older, updatedAt: older },
    topicCount: 3,
    topics: [
      topic(1, 'Chapter', 'Original chapter details'),
      topic(2, 'Next', 'Other details'),
      {
        ...topic(99, 'Book_offset', 'Book_offset detail'),
        startPage: 1,
        endPage: 1,
        printedStartPage: null,
        printedEndPage: null,
      },
    ],
  };
}

describe('mergeMetadataSave', () => {
  it('merges reordered distinct book-field responses without reverting unrelated confirmed data', () => {
    const original = book();
    const newerResponse = {
      ...original,
      bookType: 'Physics',
      authorName: 'New author',
      updatedAt: newer,
      authorDetails: {
        ...original.authorDetails!,
        authorId: 18,
        authorName: 'New author',
        updatedAt: newer,
      },
    };
    const authorSaved = mergeMetadataSave(original, { field: 'authorName', book: newerResponse });
    const lateTypeResponse = { ...original, bookType: 'Physics' };
    const merged = mergeMetadataSave(authorSaved, { field: 'bookType', book: lateTypeResponse });
    expect(merged.bookType).toBe('Physics');
    expect(merged.authorName).toBe('New author');
    expect(merged.authorDetails?.authorId).toBe(18);
    expect(merged.updatedAt).toBe(newer);
    expect(merged.bookDetails).toBe(original.bookDetails);
    expect(merged.topics).toBe(original.topics);
  });

  it('merges different fields of one topic in either response order and keeps other IDs and boundaries', () => {
    const original = book();
    const newDetails = {
      ...original,
      topics: original.topics.map((entry) =>
        entry.topicId === 1
          ? {
              ...entry,
              topicName: 'Renamed chapter',
              topicDetails: 'New\n\ncomplete details',
              updatedAt: newer,
            }
          : entry,
      ),
    };
    const detailsSaved = mergeMetadataSave(original, {
      field: 'topicDetails',
      topicId: 1,
      book: newDetails,
    });
    const oldNameResponse = {
      ...original,
      topics: original.topics.map((entry) =>
        entry.topicId === 1 ? { ...entry, topicName: 'Renamed chapter' } : entry,
      ),
    };
    const merged = mergeMetadataSave(detailsSaved, {
      field: 'topicName',
      topicId: 1,
      book: oldNameResponse,
    });
    expect(merged.topics.map((entry) => entry.topicId)).toEqual([1, 2, 99]);
    expect(merged.topics[0].topicName).toBe('Renamed chapter');
    expect(merged.topics[0].topicDetails).toBe('New\n\ncomplete details');
    expect(merged.topics[0].updatedAt).toBe(newer);
    expect(merged.topics[0].startPage).toBe(2);
    expect(merged.topics[0].printedEndPage).toBe(9);
    expect(merged.topics[1]).toBe(original.topics[1]);
    const reversed = mergeMetadataSave(
      mergeMetadataSave(original, { field: 'topicName', topicId: 1, book: oldNameResponse }),
      { field: 'topicDetails', topicId: 1, book: newDetails },
    );
    expect(reversed.topics[0]).toEqual(merged.topics[0]);
  });

  it('synchronizes only canonical derived offset fields during a book rename', () => {
    const original = book();
    const current = {
      ...original,
      bookDetails: 'New book description',
      topics: original.topics.map((entry) =>
        entry.topicId === 1
          ? { ...entry, topicDetails: 'New chapter description', updatedAt: newer }
          : entry,
      ),
    };
    const oldResponse = {
      ...original,
      bookName: 'New title',
      topics: original.topics.map((entry) =>
        entry.topicId === 99
          ? { ...entry, topicName: 'New title_offset', topicDetails: 'New title_offset detail' }
          : entry,
      ),
    };
    const merged = mergeMetadataSave(current, { field: 'bookName', book: oldResponse });
    expect(merged.bookName).toBe('New title');
    expect(merged.bookDetails).toBe('New book description');
    expect(merged.topics[0].topicDetails).toBe('New chapter description');
    expect(merged.topics[2].topicName).toBe('New title_offset');
    expect(merged.topics[2].topicDetails).toBe('New title_offset detail');
    expect(merged.topics[2].endPage).toBe(1);
    expect(original.topics[2].topicName).toBe('Book_offset');
  });

  it('keeps legacy derived name and description independent when their responses arrive out of order', () => {
    const base = book();
    const original = {
      ...base,
      topicCount: 1,
      topics: [
        {
          ...topic(50, base.bookName, base.bookDetails),
          startPage: 1,
          endPage: base.totalPages,
          printedStartPage: null,
          printedEndPage: null,
        },
      ],
    };
    const rename = {
      ...original,
      bookName: 'Renamed legacy',
      topics: [{ ...original.topics[0], topicName: 'Renamed legacy' }],
    };
    const description = {
      ...rename,
      bookDetails: 'New legacy description',
      topics: [{ ...rename.topics[0], topicDetails: 'New legacy description' }],
    };
    const merged = mergeMetadataSave(
      mergeMetadataSave(original, { field: 'bookDetails', book: description }),
      { field: 'bookName', book: rename },
    );
    expect(merged.bookName).toBe('Renamed legacy');
    expect(merged.topics[0].topicName).toBe('Renamed legacy');
    expect(merged.bookDetails).toBe('New legacy description');
    expect(merged.topics[0].topicDetails).toBe('New legacy description');
    const lateDescription = {
      ...original,
      bookDetails: 'New legacy description',
      topics: [{ ...original.topics[0], topicDetails: 'New legacy description' }],
    };
    const reversed = mergeMetadataSave(
      mergeMetadataSave(original, { field: 'bookName', book: rename }),
      { field: 'bookDetails', book: lateDescription },
    );
    expect(reversed).toEqual(merged);
  });

  it('does not import extra IDs, page counts, names, or unrelated stale fields from a topic save', () => {
    const original = book();
    const response = {
      ...original,
      totalPages: 1,
      topicCount: 1,
      bookName: 'Stale title',
      topics: [
        { ...original.topics[0], topicName: 'Stale topic', topicDetails: null, startPage: 900 },
        topic(800, 'Unexpected', 'No'),
      ],
    };
    const merged = mergeMetadataSave(original, {
      field: 'topicDetails',
      topicId: 1,
      book: response,
    });
    expect(merged.bookName).toBe(original.bookName);
    expect(merged.totalPages).toBe(10);
    expect(merged.topicCount).toBe(3);
    expect(merged.topics.map((entry) => entry.topicId)).toEqual([1, 2, 99]);
    expect(merged.topics[0].topicName).toBe('Chapter');
    expect(merged.topics[0].startPage).toBe(2);
    expect(merged.topics[0].topicDetails).toBeNull();
  });

  it('returns current metadata unchanged for mismatched books or unknown/missing topic IDs', () => {
    const original = book();
    expect(
      mergeMetadataSave(original, { field: 'bookName', book: { ...original, bookId: 999 } }),
    ).toBe(original);
    expect(mergeMetadataSave(original, { field: 'topicName', topicId: 999, book: original })).toBe(
      original,
    );
    expect(mergeMetadataSave(original, { field: 'topicName', book: original })).toBe(original);
    expect(
      mergeMetadataSave(original, {
        field: 'topicDetails',
        topicId: 1,
        book: { ...original, topics: [] },
      }),
    ).toBe(original);
    expect(
      mergeMetadataSave(original, {
        field: 'bookName',
        book: { ...original, bookName: 'Unsafe incomplete response', topics: [] },
      }),
    ).toBe(original);
  });
});
