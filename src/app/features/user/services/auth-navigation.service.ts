import { inject, Injectable } from '@angular/core';
import { AuthApiService } from '../data-access/auth-api.service';

@Injectable({ providedIn: 'root' })
export class AuthNavigationService {
  private readonly api = inject(AuthApiService);
  google(): void {
    this.redirect(`${this.api.base}/oidc/google`);
  }
  redirect(authorizationUrl: string): void {
    const target = new URL(authorizationUrl, location.origin);
    // The backend owns the redirect to its configured, trusted external provider.
    const initiation =
      target.pathname.startsWith('/api/user/auth/') ||
      /^\/oauth2\/authorization\/[^/]+$/.test(target.pathname);
    if (target.origin !== location.origin || !initiation) {
      throw new Error('The sign-in service returned an untrusted provider redirect.');
    }
    location.assign(target.href);
  }
}
