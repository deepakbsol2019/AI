import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { API_CONFIG } from '../../../core/configuration/api.config';
import { AuthApiService } from '../data-access/auth-api.service';
import { AuthSessionService } from '../services/auth-session.service';
import {
  BACKGROUND_AUTH_REQUEST,
  BackgroundAuthenticationPausedError,
} from './background-auth.context';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const config = inject(API_CONFIG);
  const base = new URL(config.baseUrl.replace(/\/$/, '') + '/', location.origin);
  const url = new URL(request.url, location.origin);
  // Strict origin + path boundary: access tokens never reach assets, CDNs or PDF workers.
  if (url.origin !== base.origin || !url.pathname.startsWith(base.pathname)) return next(request);
  if (
    url.pathname.startsWith(`${base.pathname}user/auth/`) &&
    url.pathname !== `${base.pathname}user/auth/me`
  )
    return next(request);
  const session = inject(AuthSessionService);
  const api = inject(AuthApiService);
  const unsafe = !['GET', 'HEAD', 'OPTIONS'].includes(request.method.toUpperCase());
  const send = (token: string) => {
    const authorized = request.clone({
      setHeaders: { Authorization: `Bearer ${token}` },
      withCredentials: true,
    });
    if (!unsafe) return next(authorized);
    return api
      .csrf()
      .pipe(
        switchMap((csrf) =>
          next(authorized.clone({ setHeaders: { [csrf.headerName]: csrf.token } })),
        ),
      );
  };
  if (request.context.get(BACKGROUND_AUTH_REQUEST)) {
    const token = session.tokenForBackgroundRequest();
    if (!token) return throwError(() => new BackgroundAuthenticationPausedError());
    return send(token).pipe(
      catchError((error: unknown) =>
        throwError(() =>
          error instanceof HttpErrorResponse && error.status === 401
            ? new BackgroundAuthenticationPausedError()
            : error,
        ),
      ),
    );
  }
  return from(session.token()).pipe(
    switchMap((token) =>
      send(token).pipe(
        catchError((error: unknown) => {
          if (!(error instanceof HttpErrorResponse) || error.status !== 401)
            return throwError(() => error);
          return from(session.token(true, token)).pipe(
            switchMap((replacement) => send(replacement)),
            catchError((retryError: unknown) => {
              if (retryError instanceof HttpErrorResponse && retryError.status === 401)
                session.invalidate();
              return throwError(() => retryError);
            }),
          );
        }),
      ),
    ),
  );
};
