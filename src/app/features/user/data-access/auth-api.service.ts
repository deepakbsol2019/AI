import { HttpClient, HttpHeaders } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, switchMap } from 'rxjs';
import { API_CONFIG } from '../../../core/configuration/api.config';
import {
  AuthFeatures,
  AuthMessage,
  AuthResponse,
  RegistrationRequest,
} from '../models/auth.models';

@Injectable({ providedIn: 'root' })
export class AuthApiService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);
  readonly base = `${this.config.baseUrl.replace(/\/$/, '')}/user/auth`;

  features(): Observable<AuthFeatures> {
    return this.http.get<AuthFeatures>(`${this.base}/features`);
  }
  csrf(): Observable<{ token: string; headerName: string }> {
    return this.http.get<{ token: string; headerName: string }>(`${this.base}/csrf`, {
      withCredentials: true,
    });
  }
  login(email: string, password: string, mfaCode?: string): Observable<AuthResponse> {
    return this.post<AuthResponse>('/login', { email, password, ...(mfaCode ? { mfaCode } : {}) });
  }
  refresh(): Observable<AuthResponse> {
    return this.post<AuthResponse>('/refresh', {});
  }
  logout(): Observable<void> {
    return this.post<void>('/logout', {});
  }
  register(request: RegistrationRequest): Observable<AuthMessage> {
    return this.post<AuthMessage>('/register', request);
  }
  forgot(email: string): Observable<AuthMessage> {
    return this.post<AuthMessage>('/password/forgot', { email });
  }
  setPassword(
    mode: 'setup' | 'reset',
    token: string,
    password: string,
    confirmation?: string,
  ): Observable<void> {
    return this.post<void>(`/password/${mode}`, {
      token,
      password,
      ...(mode === 'reset' ? { confirmation } : {}),
    });
  }
  sso(tenantCode: string, enrollmentToken?: string): Observable<{ authorizationUrl: string }> {
    return this.post<{ authorizationUrl: string }>('/oidc/sso', {
      tenantCode,
      ...(enrollmentToken ? { enrollmentToken } : {}),
    });
  }
  private post<T>(path: string, body: object): Observable<T> {
    // Read a fresh CSRF value: login/logout and another tab may have replaced its cookie.
    return this.csrf().pipe(
      switchMap((csrf) =>
        this.http.post<T>(`${this.base}${path}`, body, {
          withCredentials: true,
          headers: new HttpHeaders({ [csrf.headerName]: csrf.token }),
        }),
      ),
    );
  }
}
