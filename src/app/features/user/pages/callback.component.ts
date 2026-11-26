import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthSessionService } from '../services/auth-session.service';
import { apiErrorMessage } from '../../../shared/utils/api-error';

@Component({
  selector: 'app-auth-callback',
  standalone: true,
  imports: [RouterLink],
  template: `@if (error()) {
      <h1>Login could not be completed</h1>
      <p class="auth-error" role="alert">{{ error() }}</p>
      <a routerLink="/user/login">Back to login</a>
    } @else {
      <h1>Completing login</h1>
      <p role="status">Verifying your session…</p>
    }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CallbackComponent {
  private readonly session = inject(AuthSessionService);
  private readonly router = inject(Router);
  readonly error = signal<string | null>(null);
  constructor() {
    void this.complete();
  }
  private async complete(): Promise<void> {
    try {
      await this.session.token(true);
      await this.router.navigateByUrl('/');
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
    }
  }
}
