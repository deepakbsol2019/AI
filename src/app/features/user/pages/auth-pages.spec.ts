import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter, Router } from '@angular/router';
import { of, Subject } from 'rxjs';
import { AuthApiService } from '../data-access/auth-api.service';
import { AuthSessionService } from '../services/auth-session.service';
import { AuthNavigationService } from '../services/auth-navigation.service';
import { LoginComponent } from './login.component';
import { PasswordComponent } from './password.component';
import { RegisterComponent } from './register.component';

describe('Authentication pages', () => {
  let api: {
    features: ReturnType<typeof vi.fn>;
    register: ReturnType<typeof vi.fn>;
    forgot: ReturnType<typeof vi.fn>;
    setPassword: ReturnType<typeof vi.fn>;
    sso: ReturnType<typeof vi.fn>;
  };
  let session: { login: ReturnType<typeof vi.fn>; invalidate: ReturnType<typeof vi.fn> };
  let navigation: { google: ReturnType<typeof vi.fn>; redirect: ReturnType<typeof vi.fn> };
  beforeEach(() => {
    api = {
      features: vi.fn(() => of({ googleEnabled: false, ssoEnabled: false })),
      register: vi.fn(() => of({ message: 'Check your email.' })),
      forgot: vi.fn(() => of({ message: 'If available, instructions have been sent.' })),
      setPassword: vi.fn(() => of(undefined)),
      sso: vi.fn(),
    };
    session = { login: vi.fn(() => Promise.resolve()), invalidate: vi.fn() };
    navigation = { google: vi.fn(), redirect: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthApiService, useValue: api },
        { provide: AuthSessionService, useValue: session },
        { provide: AuthNavigationService, useValue: navigation },
      ],
    });
  });
  afterEach(() => history.replaceState(null, '', '/'));
  it.each(['abcd', 'a'.repeat(26), '😀😀', '😀'.repeat(13)])(
    'rejects reset length for %s without sending a request',
    async (password) => {
      history.replaceState(null, '', '/user/password/reset#token=reset-secret');
      TestBed.overrideProvider(ActivatedRoute, {
        useValue: { snapshot: { data: { mode: 'reset' } } },
      });
      const fixture = TestBed.createComponent(PasswordComponent);
      fixture.componentInstance.form.patchValue({ password, confirmation: password });
      await fixture.componentInstance.submit();
      expect(api.setPassword).not.toHaveBeenCalled();
      expect(fixture.componentInstance.validation()).toContain('5–25');
    },
  );
  it.each(['abcde', 'a'.repeat(25), '     ', '12345', '😀😀a', ' e\u0301 x'])(
    'accepts exact reset values without composition rules: %s',
    async (password) => {
      history.replaceState(null, '', '/user/password/reset#token=reset-secret');
      TestBed.overrideProvider(ActivatedRoute, {
        useValue: { snapshot: { data: { mode: 'reset' } } },
      });
      const fixture = TestBed.createComponent(PasswordComponent);
      fixture.componentInstance.form.patchValue({ password, confirmation: password });
      await fixture.componentInstance.submit();
      expect(api.setPassword).toHaveBeenCalledWith('reset', 'reset-secret', password, password);
      expect(session.invalidate).toHaveBeenCalledOnce();
    },
  );
  it('rejects mismatching reset values and disables duplicate submission while pending', async () => {
    history.replaceState(null, '', '/user/password/reset#token=reset-secret');
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { snapshot: { data: { mode: 'reset' } } },
    });
    const component = TestBed.createComponent(PasswordComponent).componentInstance;
    component.form.patchValue({ password: 'abcde', confirmation: 'ABCDE' });
    await component.submit();
    expect(component.validation()).toContain('match exactly');
    expect(api.setPassword).not.toHaveBeenCalled();
    component.form.controls.confirmation.setValue('abcde');
    const response = new Subject<void>();
    api.setPassword.mockReturnValue(response);
    const pending = component.submit();
    await component.submit();
    expect(component.busy()).toBe(true);
    expect(api.setPassword).toHaveBeenCalledOnce();
    response.error(
      new HttpErrorResponse({
        status: 400,
        error: { detail: 'This link is invalid or expired. Request a new link.' },
      }),
    );
    await pending;
    expect(component.error()).toContain('invalid or expired');
    expect(session.invalidate).not.toHaveBeenCalled();
  });
  it('renders required login fields and natively disabled providers without invoking their endpoints', async () => {
    const fixture = TestBed.createComponent(LoginComponent);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('label[for="login-email"]')?.textContent).toBe('Email/Username');
    expect((element.querySelector('.google-login') as HTMLButtonElement).disabled).toBe(true);
    expect((element.querySelector('.sso-login') as HTMLButtonElement).disabled).toBe(true);
    fixture.componentInstance.google();
    await fixture.componentInstance.sso();
    expect(navigation.google).not.toHaveBeenCalled();
    expect(api.sso).not.toHaveBeenCalled();
    expect(element.textContent).toContain('Google login is currently unavailable.');
  });
  it('validates local login and reveals MFA only on its structured challenge', async () => {
    const fixture = TestBed.createComponent(LoginComponent);
    const component = fixture.componentInstance;
    await component.login();
    expect(session.login).not.toHaveBeenCalled();
    component.form.patchValue({ email: 'reader@example.com', password: 'long password' });
    session.login.mockRejectedValue(
      new HttpErrorResponse({
        status: 401,
        error: { code: 'MFA_REQUIRED', detail: 'Enter a verification code.' },
      }),
    );
    await component.login();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#login-mfa')).not.toBeNull();
    component.form.controls.mfaCode.setValue('123456');
    session.login.mockResolvedValue(undefined);
    await component.login();
    expect(session.login).toHaveBeenLastCalledWith('reader@example.com', 'long password', '123456');
  });
  it('hides Individual Login Type, sends forced PASSWORD, and never asks for a registration password', async () => {
    const fixture = TestBed.createComponent(RegisterComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    expect(fixture.nativeElement.querySelector('#tenant-type')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('#login-type')).toBeNull();
    expect(fixture.nativeElement.querySelector('input[type=password]')).toBeNull();
    component.form.patchValue({
      displayName: ' Reader ',
      email: 'reader@example.com',
      loginType: 'SSO',
      tenantCode: 'ignored',
      organizationName: 'ignored',
    });
    await component.submit();
    expect(api.register).toHaveBeenCalledWith({
      tenantType: 'INDIVIDUAL',
      loginType: 'PASSWORD',
      displayName: 'Reader',
      email: 'reader@example.com',
    });
  });
  it('clears an old MFA challenge when the login identity changes', async () => {
    const fixture = TestBed.createComponent(LoginComponent);
    const component = fixture.componentInstance;
    component.form.patchValue({ email: 'first@example.com', password: 'a long password' });
    session.login.mockRejectedValueOnce(
      new HttpErrorResponse({
        status: 401,
        error: { code: 'MFA_REQUIRED', detail: 'Enter a code.' },
      }),
    );
    await component.login();
    expect(component.mfaRequired()).toBe(true);
    component.form.controls.email.setValue('second@example.com');
    expect(component.mfaRequired()).toBe(false);
    await component.login();
    expect(session.login).toHaveBeenLastCalledWith(
      'second@example.com',
      'a long password',
      undefined,
    );
  });
  it('requires organization details, submits SSO without password, and prevents duplicates while pending', async () => {
    const fixture = TestBed.createComponent(RegisterComponent);
    const component = fixture.componentInstance;
    component.form.patchValue({
      tenantType: 'ORGANIZATION',
      loginType: 'SSO',
      email: 'admin@example.com',
      displayName: 'Admin',
    });
    await component.submit();
    expect(api.register).not.toHaveBeenCalled();
    const response = new Subject<{ message: string }>();
    api.register.mockReturnValue(response);
    component.form.patchValue({ tenantCode: 'example-org', organizationName: 'Example' });
    const pending = component.submit();
    await component.submit();
    expect(api.register).toHaveBeenCalledTimes(1);
    expect(api.register).toHaveBeenCalledWith(
      expect.objectContaining({
        tenantType: 'ORGANIZATION',
        loginType: 'SSO',
        tenantCode: 'example-org',
      }),
    );
    response.next({ message: 'Provider setup pending.' });
    response.complete();
    await pending;
    expect(component.success()).toBe('Provider setup pending.');
  });
  it('reads setup token from fragment, clears URL immediately, validates matching passwords and posts only once', async () => {
    history.replaceState(null, '', '/user/password/setup#token=one-time-secret');
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { snapshot: { data: { mode: 'setup' } } },
    });
    const fixture = TestBed.createComponent(PasswordComponent);
    const redirect = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const component = fixture.componentInstance;
    expect(location.hash).toBe('');
    expect(component.missingToken).toBe(false);
    component.form.patchValue({ password: 'a long new password', confirmation: 'different' });
    await component.submit();
    expect(api.setPassword).not.toHaveBeenCalled();
    component.form.controls.confirmation.setValue('a long new password');
    await component.submit();
    expect(api.setPassword).toHaveBeenCalledWith('setup', 'one-time-secret', 'a long new password');
    expect(session.invalidate).toHaveBeenCalledTimes(1);
    expect(component.success()).toContain('email is verified');
    expect(redirect).toHaveBeenCalledWith(['/user/login'], {
      queryParams: { reason: 'password-set' },
      replaceUrl: true,
    });
  });
  it('shows the same forgot-password response without revealing account eligibility', async () => {
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { snapshot: { data: { mode: 'forgot' } } },
    });
    const fixture = TestBed.createComponent(PasswordComponent);
    const component = fixture.componentInstance;
    component.form.controls.email.setValue('unknown@example.com');
    await component.submit();
    expect(api.forgot).toHaveBeenCalledWith('unknown@example.com');
    expect(component.success()).toBe('If available, instructions have been sent.');
  });
});
