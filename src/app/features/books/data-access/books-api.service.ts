import { HttpClient, HttpContext, HttpEvent, HttpParams, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_CONFIG } from '../../../core/configuration/api.config';
import { BookSearchCriteria, BookSearchResponse, UploadStatus } from '../models/book.models';
import { BACKGROUND_AUTH_REQUEST } from '../../user/security/background-auth.context';

@Injectable({ providedIn: 'root' })
export class BooksApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_CONFIG).baseUrl.replace(/\/$/, '');

  search(
    criteria: BookSearchCriteria = {},
    options: { background?: boolean } = {},
  ): Observable<BookSearchResponse> {
    let params = new HttpParams();
    for (const [name, input] of Object.entries(criteria)) {
      const value = typeof input === 'string' ? input.trim() : input;
      if (value !== undefined && value !== null && value !== '') {
        params = params.set(name, value);
      }
    }
    return this.http.get<BookSearchResponse>(`${this.baseUrl}/search/books`, {
      params,
      context: new HttpContext().set(BACKGROUND_AUTH_REQUEST, options.background === true),
    });
  }

  upload(file: File): Observable<HttpEvent<UploadStatus>> {
    const body = new FormData();
    body.append('file', file, file.name);
    // The browser supplies the multipart boundary; never set Content-Type here.
    return this.http.post<UploadStatus>(`${this.baseUrl}/upload-file`, body, {
      observe: 'events',
      reportProgress: true,
    });
  }

  uploadStatus(id: number, options: { background?: boolean } = {}): Observable<UploadStatus> {
    return this.http.get<UploadStatus>(`${this.baseUrl}/upload-status/${id}`, {
      context: new HttpContext().set(BACKGROUND_AUTH_REQUEST, options.background === true),
    });
  }

  downloadBook(bookId: number): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.baseUrl}/download/book-download`, {
      params: { bookId },
      responseType: 'blob',
      observe: 'response',
    });
  }

  readBook(bookId: number): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.baseUrl}/read/book`, {
      params: { bookId },
      responseType: 'blob',
      observe: 'response',
    });
  }

  readTopics(bookId: number, topicIds: readonly number[]): Observable<HttpResponse<Blob>> {
    let params = new HttpParams().set('bookId', bookId);
    for (const topicId of topicIds) params = params.append('topicIds', topicId);
    return this.http.get(`${this.baseUrl}/read/topics`, {
      params,
      responseType: 'blob',
      observe: 'response',
    });
  }

  downloadTopic(bookId: number, topicId: number): Observable<HttpResponse<Blob>> {
    return this.downloadTopics(bookId, [topicId]);
  }

  downloadTopics(bookId: number, topicIds: readonly number[]): Observable<HttpResponse<Blob>> {
    let params = new HttpParams().set('bookId', bookId);
    // The existing Spring controller binds repeated parameters to an ordered List<Long>.
    for (const topicId of topicIds) params = params.append('topicIds', topicId);
    return this.http.get(`${this.baseUrl}/download/book-topic-download`, {
      params,
      responseType: 'blob',
      observe: 'response',
    });
  }
}
