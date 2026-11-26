import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';
import { apiErrorMessage } from '../../../shared/utils/api-error';
import { AuthApiService } from '../data-access/auth-api.service';
import { AuthSessionService } from '../services/auth-session.service';
import { takeCredentialFragment } from '../services/credential-fragment';

@Component({
  selector: 'app-password',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <h1>
      {{
        mode === 'forgot'
          ? 'Forgot password'
          : mode === 'setup'
            ? 'Set your password'
            : 'Reset your password'
      }}
    </h1>
    @if (error()) {
      <div role="alert" class="auth-error">{{ error() }}</div>
    }
    @if (success()) {
      <div role="status" class="auth-success">{{ success() }}</div>
    } @else {
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
        @if (mode === 'forgot') {
          <p>
            Enter your email address. If your account is eligible, we will email a password setup or
            reset link. For Google or company SSO, continue using your provider login.
          </p>
          <div class="field">
            <label for="forgot-email">Username / Email address</label
            ><input
              id="forgot-email"
              class="form-control"
              type="email"
              formControlName="email"
              autocomplete="email"
            />
          </div>
        } @else {
          <div class="field">
            <label for="new-password">New password</label
            ><input
              id="new-password"
              class="form-control"
              type="password"
              formControlName="password"
              autocomplete="new-password"
            />
          </div>
          <div class="field">
            <label for="confirm-password">Confirm password</label
            ><input
              id="confirm-password"
              class="form-control"
              type="password"
              formControlName="confirmation"
              autocomplete="new-password"
            />
          </div>
          <p class="form-note">
            {{
              mode === 'reset'
                ? 'Use 5–25 characters (UTF-16 code units). No composition rules.'
                : 'Use a unique password of 12–128 characters.'
            }}
            Your email link can be used only once.
          </p>
        }
        @if (validation()) {
          <p role="alert" class="field-error">{{ validation() }}</p>
        }
        <button
          class="btn btn-primary password-submit"
          type="submit"
          [disabled]="busy() || missingToken"
        >
          {{ busy() ? 'Submitting…' : mode === 'forgot' ? 'Send password link' : 'Save password' }}
        </button>
      </form>
    }
    <div class="form-links">
      <a routerLink="/user/login">Back to login</a>
      @if (mode !== 'forgot') {
        <a routerLink="/user/password/forgot">Request a new password link</a>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PasswordComponent {
  private readonly api = inject(AuthApiService);
  private readonly router = inject(Router);
  private readonly session = inject(AuthSessionService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(FormBuilder).nonNullable;
  readonly mode = inject(ActivatedRoute).snapshot.data['mode'] as 'forgot' | 'setup' | 'reset';
  private token = this.mode === 'forgot' ? '' : takeCredentialFragment().token;
  readonly missingToken = this.mode !== 'forgot' && !this.token;
  readonly form = this.fb.group({
    email: ['', Validators.email],
    password: [''],
    confirmation: [''],
  });
  readonly busy = signal(false);
  readonly error = signal<string | null>(
    this.missingToken ? 'This link is incomplete. Request a new password link.' : null,
  );
  readonly success = signal<string | null>(null);
  readonly validation = signal<string | null>(null);
  constructor() {
    this.destroyRef.onDestroy(() => {
      this.token = '';
      this.form.reset();
    });
  }
  async submit(): Promise<void> {
    if (this.busy() || this.missingToken) return;
    const value = this.form.getRawValue();
    this.validation.set(null);
    if (
      this.mode === 'forgot'
        ? !value.email.trim() || this.form.controls.email.invalid
        : (this.mode === 'setup' && !value.password.trim()) ||
          value.password.length < (this.mode === 'reset' ? 5 : 12) ||
          value.password.length > (this.mode === 'reset' ? 25 : 128) ||
          value.password !== value.confirmation
    ) {
      this.validation.set(
        this.mode === 'forgot'
          ? 'Enter your email address.'
          : value.password !== value.confirmation
            ? 'New Password and Confirm Password must match exactly.'
            : `Enter a password of ${this.mode === 'reset' ? '5–25' : '12–128'} characters (UTF-16 code units).`,
      );
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      if (this.mode === 'forgot') {
        const response = await firstValueFrom(
          this.api.forgot(value.email.trim()).pipe(takeUntilDestroyed(this.destroyRef)),
        );
        this.success.set(response.message);
      } else {
        await firstValueFrom(
          this.api
            .setPassword(
              this.mode,
              this.token,
              value.password,
              ...(this.mode === 'reset' ? [value.confirmation] : []),
            )
            .pipe(takeUntilDestroyed(this.destroyRef)),
        );
        this.token = '';
        this.form.reset();
        this.session.invalidate();
        this.success.set(
          this.mode === 'setup'
            ? 'Your email is verified and your password is set. You can now log in.'
            : 'Your password has been reset. Log in with your new password.',
        );
        await this.router.navigate(['/user/login'], {
          queryParams: { reason: this.mode === 'setup' ? 'password-set' : 'password-reset' },
          replaceUrl: true,
        });
      }
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}
