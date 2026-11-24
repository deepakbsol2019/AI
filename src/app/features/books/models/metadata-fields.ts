export type BookMetadataField = 'bookName' | 'bookType' | 'authorName' | 'bookDetails';
export type TopicMetadataField = 'topicName' | 'topicDetails';
export type MetadataField = BookMetadataField | TopicMetadataField;

/** Persisted column limits. LONGTEXT descriptions deliberately have no invented UI limit. */
export const METADATA_FIELD_LIMITS: Readonly<Partial<Record<MetadataField, number>>> = {
  bookName: 255,
  topicName: 255,
  bookType: 120,
  authorName: 200,
};

export function isTopicMetadataField(field: MetadataField): field is TopicMetadataField {
  return field === 'topicName' || field === 'topicDetails';
}

export function isDescriptionMetadataField(field: MetadataField): boolean {
  return field === 'bookDetails' || field === 'topicDetails';
}
