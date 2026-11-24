/** DTOs mirror the public Java records, including complete topics on every search result. */
export interface BookSearchCriteria {
  bookName?: string | null;
  topicName?: string | null;
  authorName?: string | null;
  bookDetailsContains?: string | null;
  topicDetailsContains?: string | null;
  query?: string | null;
  page?: number | null;
  size?: number | null;
}

export interface BookAuthor {
  authorId: number;
  authorName: string;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface BookTopic {
  topicId: number;
  topicName: string;
  topicDetails: string | null;
  startPage: number | null;
  endPage: number | null;
  printedStartPage: number | null;
  printedEndPage: number | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface Book {
  bookId: number;
  bookName: string;
  bookType: string | null;
  authorName: string | null;
  bookDetails: string | null;
  totalPages: number;
  bookUploadId: number | null;
  createdAt: string | null;
  updatedAt: string | null;
  authorDetails: BookAuthor | null;
  topicCount: number;
  topics: BookTopic[];
}

export interface BookSearchResponse {
  books: Book[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface UploadStatus {
  book_upload_id: number;
  status: 'inprogress' | 'Success' | 'failed';
  message: string;
}
