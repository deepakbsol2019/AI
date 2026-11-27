import { HttpClient, HttpContext, HttpParams, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_CONFIG } from '../../../core/configuration/api.config';
import { BACKGROUND_AUTH_REQUEST } from '../../user/security/background-auth.context';
import {
  SpreadsheetDetails,
  SpreadsheetPage,
  SpreadsheetRowsPage,
} from '../models/spreadsheet.models';

@Injectable({ providedIn: 'root' })
export class SpreadsheetApiService {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(API_CONFIG).baseUrl.replace(/\/$/, '')}/spreadsheets`;

  list(search = '', page = 0, size = 5, background = false): Observable<SpreadsheetPage> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (search.trim()) params = params.set('search', search.trim());
    return this.http.get<SpreadsheetPage>(this.base, {
      params,
      context: new HttpContext().set(BACKGROUND_AUTH_REQUEST, background),
    });
  }

  details(fileId: number): Observable<SpreadsheetDetails> {
    return this.http.get<SpreadsheetDetails>(`${this.base}/${fileId}`);
  }

  rows(fileId: number, sheetId: number, page = 0, size = 100): Observable<SpreadsheetRowsPage> {
    return this.http.get<SpreadsheetRowsPage>(`${this.base}/${fileId}/sheets/${sheetId}/rows`, {
      params: new HttpParams().set('page', page).set('size', size),
    });
  }

  downloadFile(fileId: number): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.base}/${fileId}/download`, {
      responseType: 'blob',
      observe: 'response',
    });
  }

  downloadSheet(fileId: number, sheetId: number): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.base}/${fileId}/sheets/${sheetId}/download`, {
      responseType: 'blob',
      observe: 'response',
    });
  }
}
