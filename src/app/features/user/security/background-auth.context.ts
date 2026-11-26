import { HttpContextToken } from '@angular/common/http';

/** Automated work may use existing access, but cannot renew an unattended session. */
export const BACKGROUND_AUTH_REQUEST = new HttpContextToken<boolean>(() => false);

export class BackgroundAuthenticationPausedError extends Error {
  constructor() {
    super('Automatic updates are paused. Use Retry or Resume to verify your sign-in and continue.');
    this.name = 'BackgroundAuthenticationPausedError';
  }
}
