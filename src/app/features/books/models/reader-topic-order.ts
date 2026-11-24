import type { Book, BookTopic } from './book.models';

/** Order known source ranges; leave unavailable ranges unchanged for authoritative API validation. */
export function sourceOrderedTopics(book: Book, requested: readonly BookTopic[]): BookTopic[] {
  const ids = new Set(requested.map((topic) => topic.topicId));
  const selected = book.topics.filter((topic) => ids.has(topic.topicId));
  const offsetName = `${book.bookName}_offset`;
  if (
    selected.length < 2 ||
    selected.some((topic) => !hasSourceRange(topic, book.totalPages, offsetName))
  ) {
    return selected;
  }
  return selected.sort(
    (left, right) =>
      left.startPage! - right.startPage! ||
      left.endPage! - right.endPage! ||
      Number(right.topicName === offsetName) - Number(left.topicName === offsetName) ||
      left.topicId - right.topicId,
  );
}

function hasSourceRange(topic: BookTopic, totalPages: number, offsetName: string): boolean {
  const emptyOffset =
    topic.topicName === offsetName && topic.startPage === 1 && topic.endPage === 0;
  return (
    Number.isSafeInteger(topic.startPage) &&
    Number.isSafeInteger(topic.endPage) &&
    topic.startPage! >= 1 &&
    (topic.endPage! >= topic.startPage! || emptyOffset) &&
    topic.endPage! <= totalPages
  );
}
