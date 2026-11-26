import type { Book, BookTopic } from './book.models';

export interface ReaderRequest {
  mode: 'book' | 'topic' | 'selected-topics';
  book: Book;
  topics: readonly BookTopic[];
}

export type PdfPageTheme = 'dark' | 'original' | 'inverted';

export interface PdfRenderHandle {
  readonly promise: Promise<void>;
  cancel(): void;
}

export interface PdfReaderPage {
  readonly width: number;
  readonly height?: number;
  render(canvas: HTMLCanvasElement, width: number, theme: PdfPageTheme): PdfRenderHandle;
  cleanup(): void;
}

export interface PdfReaderDocument {
  readonly pageCount: number;
  getPage(page: number): Promise<PdfReaderPage>;
}

export interface PdfLoadHandle {
  readonly promise: Promise<PdfReaderDocument>;
  destroy(): Promise<void>;
}
