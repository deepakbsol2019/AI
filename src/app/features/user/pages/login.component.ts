import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';
import { apiErrorMessage } from '../../../shared/utils/api-error';
import { AuthApiService } from '../data-access/auth-api.service';
import { AuthFeatures } from '../models/auth.models';
import { AuthSessionService } from '../services/auth-session.service';
import { AuthNavigationService } from '../services/auth-navigation.service';
import { takeCredentialFragment } from '../services/credential-fragment';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <h1>{{ enrollment ? 'Complete SSO enrollment' : 'Welcome to Bookhaven' }}</h1>
    <p>
      {{
        enrollment
          ? 'Use the organization code provided with your enrollment invitation.'
          : 'Log in to search, read and manage your books.'
      }}
    </p>
    @if (expired) {
      <p role="status">Your session expired. Please log in again.</p>
    }
    @if (passwordReset) {
      <p role="status">Your password has been reset. Log in with your new password.</p>
    }
    @if (passwordSet) {
      <p role="status">
        Your email is verified and your password is set. Log in with your new password.
      </p>
    }
    @if (roleSaved) {
      <p role="status">Role saved. Sign in again to use current permissions.</p>
    }
    @if (error()) {
      <div class="auth-error" role="alert">{{ error() }}</div>
    }
    @if (!enrollment) {
      <form [formGroup]="form" (ngSubmit)="login()" novalidate>
        <div class="field">
          <label for="login-email">Email/Username</label
          ><input
            id="login-email"
            class="form-control"
            type="email"
            formControlName="email"
            autocomplete="username"
            [readOnly]="busy()"
            [attr.aria-invalid]="form.controls.email.touched && form.controls.email.invalid"
          />
          @if (form.controls.email.touched && form.controls.email.invalid) {
            <span class="field-error">Enter your email address.</span>
          }
        </div>
        <div class="field">
          <label for="login-password">Password</label
          ><input
            id="login-password"
            class="form-control"
            type="password"
            formControlName="password"
            autocomplete="current-password"
            [readOnly]="busy()"
          />
          @if (form.controls.password.touched && form.controls.password.invalid) {
            <span class="field-error">Enter your password.</span>
          }
        </div>
        @if (mfaRequired()) {
          <div class="field">
            <label for="login-mfa">Authenticator or recovery code</label
            ><input
              id="login-mfa"
              class="form-control"
              formControlName="mfaCode"
              autocomplete="one-time-code"
              [readOnly]="busy()"
            /><span class="form-note">Your account requires an additional verification code.</span>
          </div>
        }
        <button type="submit" class="btn btn-primary login-button" [disabled]="busy()">
          {{ busy() ? 'Logging in…' : 'Login' }}
        </button>
      </form>
      <div class="form-links">
        <a routerLink="/user/password/forgot">Forgot password?</a
        ><a routerLink="/user/register">Create an account</a>
      </div>
    }
    <section class="providers" aria-label="Other login methods">
      <div class="field">
        <label for="sso-tenant">Organization code (SSO)</label
        ><input
          id="sso-tenant"
          class="form-control"
          [formControl]="tenantCode"
          autocomplete="organization"
          [readOnly]="busy()"
        />
      </div>
      <button
        type="button"
        class="btn btn-primary sso-login"
        [disabled]="busy() || !features().ssoEnabled"
        aria-describedby="sso-availability"
        (click)="sso()"
      >
        Login by SSO
      </button>
      <p id="sso-availability" class="provider-note">
        {{
          features().ssoEnabled
            ? 'For organizations with a configured identity provider.'
            : 'SSO login is currently unavailable.'
        }}
      </p>
      @if (!enrollment) {
        <button
          type="button"
          class="btn btn-primary google-login"
          [disabled]="busy() || !features().googleEnabled"
          aria-describedby="google-availability"
          (click)="google()"
        >
          Login by Google
        </button>
        <p id="google-availability" class="provider-note">
          {{
            features().googleEnabled
              ? 'Continue with your verified Google account.'
              : 'Google login is currently unavailable.'
          }}
        </p>
      }
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoginComponent {
  private readonly api = inject(AuthApiService);
  private readonly session = inject(AuthSessionService);
  private readonly navigation = inject(AuthNavigationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder).nonNullable;
  readonly enrollment = this.route.snapshot.data['enrollment'] === true;
  readonly expired = this.route.snapshot.queryParamMap.get('reason') === 'expired';
  readonly passwordReset = this.route.snapshot.queryParamMap.get('reason') === 'password-reset';
  readonly passwordSet = this.route.snapshot.queryParamMap.get('reason') === 'password-set';
  readonly roleSaved = this.route.snapshot.queryParamMap.get('reason') === 'role-saved';
  private enrollmentToken = '';
  readonly form = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
    mfaCode: [''],
  });
  readonly tenantCode = this.fb.control('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly mfaRequired = signal(false);
  readonly features = signal<AuthFeatures>({ googleEnabled: false, ssoEnabled: false });

  constructor() {
    for (const control of [this.form.controls.email, this.form.controls.password]) {
      control.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => {
        this.mfaRequired.set(false);
        this.form.controls.mfaCode.setValue('');
      });
    }
    if (this.enrollment) {
      const fragment = takeCredentialFragment();
      this.enrollmentToken = fragment.token;
      this.tenantCode.setValue(fragment.tenantCode);
      if (!fragment.token)
        this.error.set(
          'This enrollment link is missing its verification token. Ask your administrator for a new link.',
        );
    }
    this.api
      .features()
      .pipe(takeUntilDestroyed())
      .subscribe({
        next: (value) => this.features.set(value),
        error: () => this.features.set({ googleEnabled: false, ssoEnabled: false }),
      });
    this.destroyRef.onDestroy(() => {
      this.enrollmentToken = '';
      this.form.controls.password.setValue('');
    });
  }
  async login(): Promise<void> {
    if (this.busy()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    if (this.mfaRequired() && !this.form.controls.mfaCode.value.trim()) {
      this.error.set('Enter your authenticator or recovery code.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      const value = this.form.getRawValue();
      await this.session.login(
        value.email.trim(),
        value.password,
        value.mfaCode.trim() || undefined,
      );
    } catch (error) {
      if (
        error instanceof HttpErrorResponse &&
        typeof error.error === 'object' &&
        error.error?.code === 'MFA_REQUIRED'
      )
        this.mfaRequired.set(true);
      this.error.set(await apiErrorMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
  google(): void {
    if (!this.features().googleEnabled || this.busy()) return;
    try {
      this.navigation.google();
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : 'Google login is unavailable.');
    }
  }
  async sso(): Promise<void> {
    if (!this.features().ssoEnabled || this.busy()) return;
    if (!this.tenantCode.value.trim()) {
      this.error.set('Enter your organization code to continue with SSO.');
      return;
    }
    if (this.enrollment && !this.enrollmentToken) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const response = await firstValueFrom(
        this.api
          .sso(this.tenantCode.value.trim(), this.enrollmentToken || undefined)
          .pipe(takeUntilDestroyed(this.destroyRef)),
      );
      this.navigation.redirect(response.authorizationUrl);
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
      this.busy.set(false);
    }
  }
}
