import { HttpErrorResponse } from '@angular/common/http';

function readableMessage(value: unknown): string | null {
  if (typeof value === 'string') return value.trim() || null;
  if (typeof value !== 'object' || value === null) return null;
  const body = value as Record<string, unknown>;
  for (const field of ['detail', 'message', 'error', 'title']) {
    const message = body[field];
    if (typeof message === 'string' && message.trim()) return message.trim();
  }
  return null;
}

/** Angular also delivers server JSON errors as Blobs when responseType is blob. */
export async function apiErrorMessage(error: unknown): Promise<string> {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'Could not reach the book service. Check that the backend is running and try again.';
    }
    let body: unknown = error.error;
    if (body instanceof Blob) {
      try {
        const text = await body.text();
        try {
          body = JSON.parse(text) as unknown;
        } catch {
          // Do not display an entire HTML error page or raw binary response.
          body =
            /^\s*</.test(text) || !/^[\x09\x0A\x0D\x20-\x7E\u00A0-\uFFFF]*$/.test(text)
              ? null
              : text.slice(0, 1000);
        }
      } catch {
        body = null;
      }
    }
    const message = readableMessage(body);
    if (message) return message;
    if (error.status === 413) return 'This PDF exceeds the server upload limit (100 MB).';
    if (error.status === 404)
      return 'The requested book, topic, or upload status could not be found.';
    return `The book service returned an error (${error.status}). Please try again.`;
  }
  if (error instanceof Error && error.message) return error.message;
  return 'Something went wrong. Please try again.';
}
