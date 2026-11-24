import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_CONFIG } from '../../../core/configuration/api.config';
import type { Book } from '../models/book.models';
import type { BookMetadataField, TopicMetadataField } from '../models/metadata-fields';

@Injectable({ providedIn: 'root' })
export class MetadataApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_CONFIG).baseUrl.replace(/\/$/, '');

  patchBook(bookId: number, field: BookMetadataField, value: string | null): Observable<Book> {
    return this.http.patch<Book>(`${this.baseUrl}/books/${bookId}/metadata`, { [field]: value });
  }

  patchTopic(
    bookId: number,
    topicId: number,
    field: TopicMetadataField,
    value: string | null,
  ): Observable<Book> {
    return this.http.patch<Book>(`${this.baseUrl}/books/${bookId}/topics/${topicId}/metadata`, {
      [field]: value,
    });
  }
}
