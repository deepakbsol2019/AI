import { InjectionToken } from '@angular/core';

export interface ApiConfig {
  readonly baseUrl: string;
  readonly maxUploadBytes: number;
  readonly uploadPollIntervalMs: number;
}

/** Same-origin in production; the development proxy targets the backend on port 9090. */
export const API_CONFIG = new InjectionToken<ApiConfig>('BOOK_API_CONFIG', {
  providedIn: 'root',
  factory: () => ({
    baseUrl: '/api',
    maxUploadBytes: 100 * 1024 * 1024,
    uploadPollIntervalMs: 2000,
  }),
});
