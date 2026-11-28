import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_CONFIG } from '../../core/configuration/api.config';

export interface ChatPage {
  pageId: number;
  name: string;
  description: string | null;
  createdAt: string;
  updatedAt: string;
  revision: number;
}
export interface ChatMessage {
  messageId: number;
  pageId: number;
  senderRole: 'USER' | 'ASSISTANT';
  content: string;
  createdAt: string;
  messageOrder: number;
}
export interface ChatPages {
  pages: ChatPage[];
  lastSelectedPageId: number | null;
}
export interface ChatMessages {
  pageId: number;
  revision: number;
  messages: ChatMessage[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasOlder: boolean;
}
export interface ChatSubmission {
  pageId: number;
  message: ChatMessage;
  acknowledgment: string;
  assistantStatus: string;
  revision: number;
}

@Injectable({ providedIn: 'root' })
export class AiHelpApiService {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(API_CONFIG).baseUrl.replace(/\/$/, '')}/ai-help`;

  list(): Observable<ChatPages> {
    return this.http.get<ChatPages>(`${this.base}/pages`);
  }
  create(): Observable<ChatPage> {
    return this.http.post<ChatPage>(`${this.base}/pages`, {});
  }
  select(pageId: number): Observable<ChatPage> {
    return this.http.put<ChatPage>(`${this.base}/selection`, { pageId });
  }
  rename(pageId: number, name: string): Observable<ChatPage> {
    return this.http.patch<ChatPage>(`${this.base}/pages/${pageId}`, { name });
  }
  messages(pageId: number, page = 0): Observable<ChatMessages> {
    return this.http.get<ChatMessages>(`${this.base}/pages/${pageId}/messages`, {
      params: new HttpParams().set('page', page).set('size', 50),
    });
  }
  send(
    pageId: number,
    revision: number,
    text: string,
    requestId: string,
  ): Observable<ChatSubmission> {
    return this.http.post<ChatSubmission>(`${this.base}/chat`, text, {
      params: new HttpParams().set('pageId', pageId).set('revision', revision),
      headers: { 'Content-Type': 'text/plain; charset=UTF-8', 'X-Message-Id': requestId },
    });
  }
  clean(pageId: number, revision: number): Observable<ChatPage> {
    return this.http.delete<ChatPage>(`${this.base}/pages/${pageId}/messages`, {
      params: new HttpParams().set('revision', revision),
    });
  }
  delete(pageId: number, revision: number): Observable<{ lastSelectedPageId: number | null }> {
    return this.http.delete<{ lastSelectedPageId: number | null }>(`${this.base}/pages/${pageId}`, {
      params: new HttpParams().set('revision', revision),
    });
  }
  pdf(pageId: number): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.base}/pages/${pageId}/pdf`, {
      responseType: 'blob',
      observe: 'response',
    });
  }
}
