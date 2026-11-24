import { downloadFilename } from './download-filename';

describe('downloadFilename', () => {
  it('prefers UTF-8 filename* over filename', () => {
    expect(
      downloadFilename(
        "attachment; filename=book.pdf; filename*=UTF-8''Effective%20Java%20%E2%80%94%20notes.pdf",
        'fallback.pdf',
      ),
    ).toBe('Effective Java — notes.pdf');
  });

  it('handles quoted semicolons and falls back after malformed percent encoding', () => {
    expect(downloadFilename('attachment; filename="Book; volume 2.pdf"', 'fallback.pdf')).toBe(
      'Book; volume 2.pdf',
    );
    expect(
      downloadFilename("attachment; filename=book.pdf; filename*=UTF-8''bad%ZZ", 'fallback.pdf'),
    ).toBe('book.pdf');
  });

  it('removes path traversal, control characters, and unsafe punctuation', () => {
    expect(downloadFilename('attachment; filename="../../hidden.pdf"', 'fallback.pdf')).toBe(
      'hidden.pdf',
    );
    expect(downloadFilename('attachment; filename="book\r\n:?.pdf"', 'fallback.pdf')).toBe(
      'book.pdf',
    );
    expect(downloadFilename('attachment; filename=".."', 'fallback.pdf')).toBe('fallback.pdf');
  });

  it('uses safe fallback when the server header is unavailable', () => {
    expect(downloadFilename(null, 'A/B: Book.pdf')).toBe('B Book.pdf');
  });
});
