import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { apiErrorMessage } from '../../../shared/utils/api-error';
import { AuthApiService } from '../data-access/auth-api.service';

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <h1>Registration</h1>
    <p>Create your personal library or register a new organization.</p>
    @if (error()) {
      <div class="auth-error" role="alert">{{ error() }}</div>
    }
    @if (success()) {
      <div class="auth-success" role="status">{{ success() }}</div>
      <div class="form-links"><a routerLink="/user/login">Back to login</a></div>
    } @else {
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="field">
          <label for="tenant-type">Tenant Type</label
          ><select id="tenant-type" class="form-select" formControlName="tenantType">
            <option value="INDIVIDUAL">Individual</option>
            <option value="ORGANIZATION">Organization</option>
          </select>
        </div>
        @if (form.controls.tenantType.value === 'ORGANIZATION') {
          <div class="field">
            <label for="login-type">Login Type</label
            ><select id="login-type" class="form-select" formControlName="loginType">
              <option value="PASSWORD">Password</option>
              <option value="SSO">SSO</option>
            </select>
          </div>
          <div class="field">
            <label for="organization-name">Organization name</label
            ><input
              id="organization-name"
              class="form-control"
              formControlName="organizationName"
              autocomplete="organization"
              maxlength="180"
            />
          </div>
          <div class="field">
            <label for="tenant-code">Organization code</label
            ><input
              id="tenant-code"
              class="form-control"
              formControlName="tenantCode"
              autocapitalize="none"
              maxlength="80"
            /><span class="form-note"
              >3–80 letters, numbers, underscores or hyphens; start with a letter or number. Choose
              a unique code for this new organization.</span
            >
          </div>
        }
        <div class="field">
          <label for="register-name">{{
            form.controls.tenantType.value === 'ORGANIZATION'
              ? 'First administrator name'
              : 'Your name'
          }}</label><input
            id="register-name"
            class="form-control"
            formControlName="displayName"
            autocomplete="name"
            maxlength="150"
          />
        </div>
        <div class="field">
          <label for="register-email">{{
            form.controls.tenantType.value === 'ORGANIZATION'
              ? 'First administrator email'
              : 'Email'
          }}</label><input
            id="register-email"
            type="email"
            class="form-control"
            formControlName="email"
            autocomplete="email"
            maxlength="320"
          />
        </div>
        @if (invalid()) {
          <p class="field-error" role="alert">
            Enter a valid email and name{{
              form.controls.tenantType.value === 'ORGANIZATION'
                ? ', an organization name of up to 180 characters, and a valid organization code'
                : ''
            }}.
          </p>
        }
        <p class="form-note">
          {{
            form.controls.tenantType.value === 'ORGANIZATION' &&
            form.controls.loginType.value === 'SSO'
              ? 'SSO registration stays pending until a trusted company provider is configured and your identity is verified. No password email is sent.'
              : 'We will email a Set Password link to verify your email and activate your account. Do not enter a password here.'
          }}
        </p>
        <button type="submit" class="btn btn-primary register-button" [disabled]="busy()">
          {{ busy() ? 'Registering…' : 'Register' }}
        </button>
      </form>
      <div class="form-links"><a routerLink="/user/login">Already registered? Login</a></div>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegisterComponent {
  private readonly api = inject(AuthApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(FormBuilder).nonNullable;
  readonly form = this.fb.group({
    tenantType: this.fb.control<'INDIVIDUAL' | 'ORGANIZATION'>('INDIVIDUAL'),
    loginType: this.fb.control<'PASSWORD' | 'SSO'>('PASSWORD'),
    displayName: ['', [Validators.required, Validators.maxLength(150)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
    organizationName: [''],
    tenantCode: [''],
  });
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly success = signal<string | null>(null);
  readonly invalid = signal(false);
  async submit(): Promise<void> {
    if (this.busy()) return;
    const value = this.form.getRawValue();
    const organization = value.tenantType === 'ORGANIZATION';
    this.form.markAllAsTouched();
    this.invalid.set(
      this.form.invalid ||
        !value.displayName.trim() ||
        (organization &&
          (!value.organizationName.trim() ||
            value.organizationName.trim().length > 180 ||
            !/^[a-zA-Z0-9][a-zA-Z0-9_-]{2,79}$/.test(value.tenantCode.trim()))),
    );
    if (this.invalid()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const result = await firstValueFrom(
        this.api
          .register({
            tenantType: value.tenantType,
            loginType: organization ? value.loginType : 'PASSWORD',
            email: value.email.trim(),
            displayName: value.displayName.trim(),
            ...(organization
              ? {
                  organizationName: value.organizationName.trim(),
                  tenantCode: value.tenantCode.trim(),
                }
              : {}),
          })
          .pipe(takeUntilDestroyed(this.destroyRef)),
      );
      this.success.set(result.message);
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}
