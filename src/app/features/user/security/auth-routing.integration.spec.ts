import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { EMPTY } from 'rxjs';
import { routes } from '../../../app.routes';
import { AuthTabCoordinator } from '../services/auth-tab-coordinator.service';
import { AuthSessionService } from '../services/auth-session.service';
import { CurrentProfileService } from '../services/current-profile.service';
import { NavigationAccessService } from '../services/navigation-access.service';
import { authInterceptor } from './auth.interceptor';

describe('Actual authentication route integration', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        {
          provide: AuthTabCoordinator,
          useValue: {
            events: EMPTY,
            publish: vi.fn(),
            exclusive: <T>(work: () => Promise<T>) => Promise.resolve().then(work),
          },
        },
      ],
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('returns from emailed password setup to login only after success and shows confirmation', async () => {
    const http = TestBed.inject(HttpTestingController);
    const harness = await RouterTestingHarness.create();
    history.replaceState(null, '', '/user/password/setup#token=setup-secret');
    await harness.navigateByUrl('/user/password/setup');
    expect(location.hash).toBe('');
    http.expectNone('/api/user/auth/password/setup');
    const root = harness.routeNativeElement as HTMLElement;
    for (const id of ['new-password', 'confirm-password']) {
      const input = root.querySelector('#' + id) as HTMLInputElement;
      input.value = 'a long new password';
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
    root
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    http.expectOne('/api/user/auth/csrf').flush({ token: 'csrf', headerName: 'X-XSRF-TOKEN' });
    const setup = http.expectOne('/api/user/auth/password/setup');
    expect(setup.request.body).toEqual({ token: 'setup-secret', password: 'a long new password' });
    expect(TestBed.inject(Router).url).toBe('/user/password/setup');
    setup.flush(null);
    await vi.waitFor(() =>
      http.expectOne('/api/user/auth/features').flush({ googleEnabled: false, ssoEnabled: false }),
    );
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(TestBed.inject(Router).url).toBe('/user/login?reason=password-set');
    expect(harness.routeNativeElement?.textContent).toContain(
      'Your email is verified and your password is set.',
    );
    expect(harness.routeNativeElement?.querySelector('#login-email')).not.toBeNull();
    expect(TestBed.inject(AuthSessionService).user()).toBeNull();
    http.expectNone('/api/user/auth/login');
    http.expectNone('/api/user/auth/refresh');
  });
  it('completes recovery through actual routes and HTTP, removes the secret and returns to login', async () => {
    const http = TestBed.inject(HttpTestingController);
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/user/password/forgot');
    const root = harness.routeNativeElement as HTMLElement;
    expect(root.querySelector('label[for="forgot-email"]')?.textContent).toBe(
      'Username / Email address',
    );
    const email = root.querySelector('#forgot-email') as HTMLInputElement;
    email.value = 'reader@example.com';
    email.dispatchEvent(new Event('input', { bubbles: true }));
    root
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    http.expectOne('/api/user/auth/csrf').flush({ token: 'csrf', headerName: 'X-XSRF-TOKEN' });
    const forgot = http.expectOne('/api/user/auth/password/forgot');
    expect(forgot.request.headers.has('Authorization')).toBe(false);
    forgot.flush({
      message: 'If an eligible account exists, a password reset email will be sent.',
    });
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(root.textContent).toContain('If an eligible account exists');
    history.replaceState(null, '', '/user/password/reset#token=reset-secret');
    await harness.navigateByUrl('/user/password/reset');
    expect(location.hash).toBe('');
    http.expectNone('/api/user/auth/password/reset');
    const resetRoot = harness.routeNativeElement as HTMLElement;
    for (const id of ['new-password', 'confirm-password']) {
      const input = resetRoot.querySelector('#' + id) as HTMLInputElement;
      input.value = 'abcde';
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
    resetRoot
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    http.expectOne('/api/user/auth/csrf').flush({ token: 'csrf', headerName: 'X-XSRF-TOKEN' });
    const reset = http.expectOne('/api/user/auth/password/reset');
    expect(reset.request.body).toEqual({
      token: 'reset-secret',
      password: 'abcde',
      confirmation: 'abcde',
    });
    expect(reset.request.headers.has('Authorization')).toBe(false);
    reset.flush(null);
    await vi.waitFor(() =>
      http.expectOne('/api/user/auth/features').flush({ googleEnabled: false, ssoEnabled: false }),
    );
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(TestBed.inject(Router).url).toBe('/user/login?reason=password-reset');
    expect(harness.routeNativeElement?.textContent).toContain('Your password has been reset.');
    expect(TestBed.inject(AuthSessionService).user()).toBeNull();
    http.expectNone('/api/user/auth/refresh');
    http.expectNone('/api/user/auth/login');
  });
  it('bootstraps once from a cookie and shows Login instead of requesting the catalog when refresh is invalid', async () => {
    const http = TestBed.inject(HttpTestingController);
    const harness = await RouterTestingHarness.create();
    const pending = harness.navigateByUrl('/');
    await vi.waitFor(() =>
      http.expectOne('/api/user/auth/csrf').flush({ token: 'csrf', headerName: 'X-XSRF-TOKEN' }),
    );
    http
      .expectOne('/api/user/auth/refresh')
      .flush(
        { code: 'AUTHENTICATION_REQUIRED', detail: 'Please log in again.', status: 401 },
        { status: 401, statusText: 'Unauthorized' },
      );
    await vi.waitFor(() =>
      http.expectOne('/api/user/auth/features').flush({ googleEnabled: false, ssoEnabled: false }),
    );
    await pending;
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/user/login');
    expect(harness.routeNativeElement?.querySelector('#login-email')).not.toBeNull();
    expect(harness.routeNativeElement?.querySelector('.registration-link')?.textContent).toBe(
      'Registration',
    );
    http.expectNone('/api/search/books?size=5');
  });
  it('logs in through real HTTP, opens fresh Home with bearer and size five, and logs out without a refresh loop', async () => {
    const http = TestBed.inject(HttpTestingController);
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/user/login');
    await vi.waitFor(() =>
      http.expectOne('/api/user/auth/features').flush({ googleEnabled: false, ssoEnabled: false }),
    );
    await navigation;
    await harness.fixture.whenStable();
    const login = harness.routeNativeElement as HTMLElement;
    const email = login.querySelector('#login-email') as HTMLInputElement;
    email.value = 'reader@example.com';
    email.dispatchEvent(new Event('input', { bubbles: true }));
    const password = login.querySelector('#login-password') as HTMLInputElement;
    password.value = 'a long password';
    password.dispatchEvent(new Event('input', { bubbles: true }));
    (login.querySelector('form') as HTMLFormElement).dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
    await vi.waitFor(() =>
      http
        .expectOne('/api/user/auth/csrf')
        .flush({ token: 'csrf-login', headerName: 'X-XSRF-TOKEN' }),
    );
    const request = http.expectOne('/api/user/auth/login');
    expect(request.request.body).toEqual({
      email: 'reader@example.com',
      password: 'a long password',
    });
    const now = Date.now();
    request.flush({
      accessToken: 'real-flow-access',
      tokenType: 'Bearer',
      serverTime: new Date(now).toISOString(),
      accessExpiresAt: new Date(now + 300_000).toISOString(),
      refreshExpiresAt: new Date(now + 600_000).toISOString(),
      absoluteExpiresAt: null,
      sessionId: 7,
      user: {
        userId: 1,
        tenantId: 2,
        membershipId: 3,
        email: 'reader@example.com',
        displayName: 'Reader',
        tenantName: 'Library',
      },
    });
    await vi.waitFor(() => {
      const search = http.expectOne('/api/search/books?size=5');
      expect(search.request.headers.get('Authorization')).toBe('Bearer real-flow-access');
      search.flush({ books: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
    });
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/');
    // Signing in first updates the account shell, then navigates to the library shell.
    // Both load only the current profile; the identity-scoped navigation request is shared.
    const profiles = http.match('/api/user/profile');
    expect(profiles).toHaveLength(2);
    for (const profile of profiles) {
      expect(profile.request.headers.get('Authorization')).toBe('Bearer real-flow-access');
      profile.flush({
        username: 'reader@example.com',
        displayName: 'Reader',
        email: 'reader@example.com',
        tenantName: 'Library',
        tenantType: 'INDIVIDUAL',
        roles: ['Reader'],
        status: 'ACTIVE',
        createdAt: null,
        lastLogin: null,
      });
    }
    const navigationAccess = http.expectOne('/api/user/users/capabilities');
    expect(navigationAccess.request.headers.get('Authorization')).toBe('Bearer real-flow-access');
    navigationAccess.flush({
      superUser: false,
      canViewUsers: false,
      canCreateUsers: false,
      canManageRoles: false,
      tenantId: 2,
      tenantName: 'Library',
      tenantType: 'INDIVIDUAL',
    });
    await harness.fixture.whenStable();
    harness.detectChanges();
    const home = harness.routeNativeElement as HTMLElement;
    expect((home.querySelector('#library-search-section') as HTMLElement).hidden).toBe(true);
    expect((home.querySelector('#book-collection-section') as HTMLElement).hidden).toBe(false);
    expect(home.querySelector('app-sidebar')?.textContent).toContain('reader@example.com');
    expect(home.querySelectorAll('.logout-button')).toHaveLength(1);
    expect(home.querySelector('app-header .logout-button,app-header .profile-trigger')).toBeNull();
    (home.querySelector('.logout-button') as HTMLButtonElement).click();
    await vi.waitFor(() =>
      http
        .expectOne('/api/user/auth/csrf')
        .flush({ token: 'csrf-logout', headerName: 'X-XSRF-TOKEN' }),
    );
    http.expectOne('/api/user/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    await vi.waitFor(() =>
      http.expectOne('/api/user/auth/features').flush({ googleEnabled: false, ssoEnabled: false }),
    );
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/user/login');
    expect(TestBed.inject(CurrentProfileService).profile()).toBeNull();
    expect(TestBed.inject(NavigationAccessService).capabilities()).toBeNull();
    expect(harness.routeNativeElement?.querySelector('app-sidebar,.logout-button')).toBeNull();
    http.expectNone('/api/user/profile');
    http.expectNone('/api/user/users/capabilities');
    http.expectNone('/api/user/auth/refresh');
  }, 15_000);
  it('keeps a failed server logout signed out when a protected route is requested again', async () => {
    const http = TestBed.inject(HttpTestingController);
    const harness = await RouterTestingHarness.create();
    const session = TestBed.inject(AuthSessionService);
    const bootstrap = session.token();
    await vi.waitFor(() =>
      http
        .expectOne('/api/user/auth/csrf')
        .flush({ token: 'csrf-bootstrap', headerName: 'X-XSRF-TOKEN' }),
    );
    const now = Date.now();
    http.expectOne('/api/user/auth/refresh').flush({
      accessToken: 'old-access',
      tokenType: 'Bearer',
      serverTime: new Date(now).toISOString(),
      accessExpiresAt: new Date(now + 300_000).toISOString(),
      refreshExpiresAt: new Date(now + 600_000).toISOString(),
      absoluteExpiresAt: null,
      sessionId: 77,
      user: {
        userId: 1,
        tenantId: 2,
        membershipId: 3,
        email: 'reader@example.com',
        displayName: 'Reader',
        tenantName: 'Library',
      },
    });
    await bootstrap;
    const logout = session.logout();
    await vi.waitFor(() =>
      http
        .expectOne('/api/user/auth/csrf')
        .flush({ token: 'csrf-logout', headerName: 'X-XSRF-TOKEN' }),
    );
    http.expectOne('/api/user/auth/logout').error(new ProgressEvent('network-error'));
    await vi.waitFor(() =>
      http.expectOne('/api/user/auth/features').flush({ googleEnabled: false, ssoEnabled: false }),
    );
    await logout;
    await harness.fixture.whenStable();
    await harness.navigateByUrl('/');
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/user/login');
    expect(session.authenticated()).toBe(false);
    expect(harness.routeNativeElement?.querySelector('#login-email')).not.toBeNull();
    expect(TestBed.inject(CurrentProfileService).profile()).toBeNull();
    expect(TestBed.inject(NavigationAccessService).capabilities()).toBeNull();
    http.expectNone('/api/user/profile');
    http.expectNone('/api/user/users/capabilities');
    http.expectNone('/api/user/auth/refresh');
    http.expectNone('/api/user/auth/csrf');
    http.expectNone('/api/search/books?size=5');
  }, 15_000);
});
