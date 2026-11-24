import type { Book, BookAuthor, BookTopic } from './book.models';
import type { MetadataField } from './metadata-fields';

export interface MetadataSave {
  readonly book: Book;
  readonly field: MetadataField;
  readonly topicId?: number;
}

function newest(current: string | null, confirmed: string | null): string | null {
  if (!current) return confirmed;
  if (!confirmed) return current;
  const before = Date.parse(current);
  const after = Date.parse(confirmed);
  if (!Number.isFinite(before) || !Number.isFinite(after)) return current;
  if (before !== after) return after > before ? confirmed : current;
  // Preserve sub-millisecond precision from the backend's LocalDateTime values.
  const fraction = (value: string) =>
    (value.match(/\.(\d+)(?:Z|[+-]\d\d:\d\d)?$/)?.[1] ?? '').padEnd(9, '0');
  return fraction(confirmed) > fraction(current) ? confirmed : current;
}

function topicAudit(
  current: BookTopic,
  confirmed: BookTopic,
): Pick<BookTopic, 'createdAt' | 'updatedAt'> {
  return {
    createdAt: current.createdAt ?? confirmed.createdAt,
    updatedAt: newest(current.updatedAt, confirmed.updatedAt),
  };
}

function legacyWholeBook(book: Book, topic: BookTopic): boolean {
  return (
    book.topics.length === 1 &&
    topic.topicName === book.bookName &&
    topic.topicDetails === book.bookDetails &&
    topic.startPage === 1 &&
    topic.endPage === book.totalPages &&
    topic.printedStartPage === null &&
    topic.printedEndPage === null
  );
}

function confirmedAuthor(
  current: BookAuthor | null,
  confirmed: BookAuthor | null,
): BookAuthor | null {
  if (!confirmed) return null;
  if (!current || current.authorId !== confirmed.authorId) return { ...confirmed };
  return {
    ...confirmed,
    createdAt: current.createdAt ?? confirmed.createdAt,
    updatedAt: newest(current.updatedAt, confirmed.updatedAt),
  };
}

/** Full PATCH responses are snapshots: merge only the field that this request actually saved. */
export function mergeMetadataSave(current: Book, save: MetadataSave): Book {
  const confirmed = save.book;
  if (current.bookId !== confirmed.bookId) return current;
  const audit = {
    createdAt: current.createdAt ?? confirmed.createdAt,
    updatedAt: newest(current.updatedAt, confirmed.updatedAt),
  };

  switch (save.field) {
    case 'bookType':
      return { ...current, ...audit, bookType: confirmed.bookType };
    case 'authorName':
      return {
        ...current,
        ...audit,
        authorName: confirmed.authorName,
        authorDetails: confirmedAuthor(current.authorDetails, confirmed.authorDetails),
      };
    case 'bookName': {
      const derived = current.topics.filter(
        (topic) =>
          topic.topicName === `${current.bookName}_offset` || legacyWholeBook(current, topic),
      );
      if (
        derived.some((topic) => !confirmed.topics.some((entry) => entry.topicId === topic.topicId))
      )
        return current;
      const topics = current.topics.map((topic) => {
        const canonical = topic.topicName === `${current.bookName}_offset`;
        const legacy = legacyWholeBook(current, topic);
        if (!canonical && !legacy) return topic;
        const saved = confirmed.topics.find((entry) => entry.topicId === topic.topicId)!;
        // A legacy whole-book row's description belongs to the independent bookDetails save.
        const details =
          canonical && topic.topicDetails === `${current.bookName}_offset detail`
            ? saved.topicDetails
            : topic.topicDetails;
        if (topic.topicName === saved.topicName && topic.topicDetails === details) return topic;
        return {
          ...topic,
          ...topicAudit(topic, saved),
          topicName: saved.topicName,
          topicDetails: details,
        };
      });
      return { ...current, ...audit, bookName: confirmed.bookName, topics };
    }
    case 'bookDetails': {
      const legacy = current.topics.find((topic) => legacyWholeBook(current, topic));
      const saved = legacy
        ? confirmed.topics.find((topic) => topic.topicId === legacy.topicId)
        : null;
      if (legacy && !saved) return current;
      const topics =
        legacy && saved
          ? current.topics.map((topic) =>
              topic.topicId === legacy.topicId
                ? { ...topic, ...topicAudit(topic, saved), topicDetails: saved.topicDetails }
                : topic,
            )
          : current.topics;
      return { ...current, ...audit, bookDetails: confirmed.bookDetails, topics };
    }
    case 'topicName':
    case 'topicDetails': {
      const field = save.field;
      const topic = current.topics.find((entry) => entry.topicId === save.topicId);
      const saved = confirmed.topics.find((entry) => entry.topicId === save.topicId);
      if (!topic || !saved) return current;
      return {
        ...current,
        ...audit,
        topics: current.topics.map((entry) =>
          entry.topicId === topic.topicId
            ? { ...entry, ...topicAudit(entry, saved), [field]: saved[field] }
            : entry,
        ),
      };
    }
    default:
      return current;
  }
}
