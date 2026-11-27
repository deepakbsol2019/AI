import { InjectionToken } from '@angular/core';

export type DocumentType = 'pdf' | 'spreadsheet';

/** The user confirmed PDF as the initial tab. Search routing reads only this value. */
export const INITIAL_DOCUMENT_TYPE = new InjectionToken<DocumentType>('INITIAL_DOCUMENT_TYPE', {
  providedIn: 'root',
  factory: () => 'pdf',
});
