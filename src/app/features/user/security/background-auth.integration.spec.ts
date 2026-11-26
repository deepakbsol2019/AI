import {
  HttpClient,
  HttpEventType,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { EMPTY, firstValueFrom } from 'rxjs';
import { API_CONFIG } from '../../../core/configuration/api.config';
import { NotificationService } from '../../../shared/services/notification.service';
import { HomeComponent } from '../../books/pages/home/home.component';
import { BookUploadService } from '../../books/services/book-upload.service';
import { AuthResponse } from '../models/auth.models';
import { AuthSessionService } from '../services/auth-session.service';
import { AuthTabCoordinator } from '../services/auth-tab-coordinator.service';
import { authInterceptor } from './auth.interceptor';
import { BACKGROUND_AUTH_REQUEST } from './background-auth.context';

const start = Date.parse('2026-11-25T12:00:00Z');
const minute = 60_000;
const pending = { book_upload_id: 41, status: 'inprogress', message: 'Preparing topics' };

describe('Authenticated upload background work', () => {
  let http: HttpTestingController;
  let auth: AuthSessionService;
  let upload: BookUploadService;
  let router: {
    url: string;
    navigate: ReturnType<typeof vi.fn>;
    navigateByUrl: ReturnType<typeof vi.fn>;
  };

  const settle = () => vi.advanceTimersByTimeAsync(0);
  function response(at = Date.now(), id = 'session-1', user = 'first'): AuthResponse {
    return {
      accessToken: `${id}-access-${at}`,
      tokenType: 'Bearer',
      serverTime: new Date(at).toISOString(),
      accessExpiresAt: new Date(at + 5 * minute).toISOString(),
      refreshExpiresAt: new Date(at + 10 * minute).toISOString(),
      absoluteExpiresAt: null,
      sessionId: id,
      user: {
        userId: user,
        tenantId: `tenant-${user}`,
        membershipId: `member-${user}`,
        email: `${user}@example.com`,
        displayName: user,
        tenantName: `${user}'s library`,
      },
    };
  }
  async function signIn(id = 'session-1', user = 'first'): Promise<void> {
    const login = auth.login(`${user}@example.com`, 'a long password');
    await settle();
    http
      .expectOne('/api/user/auth/csrf')
      .flush({ token: 'csrf-login', headerName: 'X-XSRF-TOKEN' });
    http.expectOne('/api/user/auth/login').flush(response(Date.now(), id, user));
    await login;
  }
  async function beginUpload(): Promise<void> {
    upload.upload(new File(['%PDF-1.7'], 'private-first-user.pdf', { type: 'application/pdf' }));
    await settle();
    http
      .expectOne('/api/user/auth/csrf')
      .flush({ token: 'csrf-upload', headerName: 'X-XSRF-TOKEN' });
    const request = http.expectOne('/api/upload-file');
    request.event({ type: HttpEventType.UploadProgress, loaded: 4, total: 8 });
    expect(upload.progress()).toBe(50);
    request.flush(pending, { status: 202, statusText: 'Accepted' });
  }
  async function pauseAtAccessExpiry(): Promise<void> {
    for (let tick = 1; tick < 5; tick++) {
      await vi.advanceTimersByTimeAsync(minute);
      const status = http.expectOne('/api/upload-status/41');
      expect(status.request.context.get(BACKGROUND_AUTH_REQUEST)).toBe(tick > 1);
      status.flush(pending);
    }
    await vi.advanceTimersByTimeAsync(minute);
    http.expectNone('/api/upload-status/41');
    expect(upload.phase()).toBe('unknown');
    expect(upload.message()).toContain('Resume status check');
  }
  beforeEach(async () => {
    vi.useFakeTimers();
    vi.setSystemTime(start);
    router = {
      url: '/',
      navigate: vi.fn(() => Promise.resolve(true)),
      navigateByUrl: vi.fn(() => Promise.resolve(true)),
    };
    TestBed.configureTestingModule({
      imports: [HomeComponent],
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: router },
        {
          provide: API_CONFIG,
          useValue: {
            baseUrl: '/api',
            maxUploadBytes: 100 * 1024 * 1024,
            uploadPollIntervalMs: minute,
          },
        },
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
    TestBed.overrideComponent(HomeComponent, { set: { template: '', imports: [] } });
    await TestBed.compileComponents();
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthSessionService);
    upload = TestBed.inject(BookUploadService);
  });
  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
      vi.useRealTimers();
    }
  });

  it('pauses unattended polls at five minutes without refresh and expires at the known ten-minute deadline', async () => {
    await signIn();
    await beginUpload();
    await pauseAtAccessExpiry();
    expect(upload.receiptId()).toBe(41);
    expect(auth.authenticated()).toBe(true);
    await vi.advanceTimersByTimeAsync(5 * minute - 1);
    expect(auth.authenticated()).toBe(true);
    http.expectNone('/api/upload-status/41');
    http.expectNone('/api/user/auth/refresh');
    await vi.advanceTimersByTimeAsync(1);
    expect(auth.authenticated()).toBe(false);
    expect(upload.receiptId()).toBeNull();
    expect(upload.fileName()).toBe('');
    expect(upload.phase()).toBe('idle');
    expect(router.navigate).toHaveBeenCalledWith(['/user/login'], {
      queryParams: { reason: 'expired' },
    });
    http.expectNone('/api/user/auth/refresh');
  });
  it('lets explicit Resume between five and ten minutes rotate once, then returns to background polling', async () => {
    await signIn();
    await beginUpload();
    await pauseAtAccessExpiry();
    await vi.advanceTimersByTimeAsync(minute);
    upload.retryStatus();
    upload.retryStatus();
    await settle();
    http
      .expectOne('/api/user/auth/csrf')
      .flush({ token: 'csrf-refresh', headerName: 'X-XSRF-TOKEN' });
    http.expectOne('/api/user/auth/refresh').flush(response());
    await settle();
    const resumed = http.expectOne('/api/upload-status/41');
    expect(resumed.request.context.get(BACKGROUND_AUTH_REQUEST)).toBe(false);
    resumed.flush(pending);
    await vi.advanceTimersByTimeAsync(minute);
    const automatic = http.expectOne('/api/upload-status/41');
    expect(automatic.request.context.get(BACKGROUND_AUTH_REQUEST)).toBe(true);
    automatic.flush({ ...pending, status: 'Success', message: 'Ready' });
    expect(upload.phase()).toBe('success');
    http.expectNone('/api/user/auth/refresh');
  });
  it('still queues ordinary foreground requests behind one refresh after access expiry', async () => {
    await signIn();
    await vi.advanceTimersByTimeAsync(6 * minute);
    const client = TestBed.inject(HttpClient);
    const first = firstValueFrom(client.get('/api/search/books?query=one'));
    const second = firstValueFrom(client.get('/api/search/books?query=two'));
    await settle();
    http
      .expectOne('/api/user/auth/csrf')
      .flush({ token: 'csrf-refresh', headerName: 'X-XSRF-TOKEN' });
    const replacement = response();
    http.expectOne('/api/user/auth/refresh').flush(replacement);
    await settle();
    for (const query of ['one', 'two']) {
      const request = http.expectOne(`/api/search/books?query=${query}`);
      expect(request.request.headers.get('Authorization')).toBe(
        `Bearer ${replacement.accessToken}`,
      );
      request.flush({});
    }
    await Promise.all([first, second]);
    http.expectNone('/api/user/auth/refresh');
  });
  it('pauses a background 401 without retry, and a revoked explicit refresh still clears authentication', async () => {
    await signIn();
    await beginUpload();
    await vi.advanceTimersByTimeAsync(minute);
    http.expectOne('/api/upload-status/41').flush(pending);
    await vi.advanceTimersByTimeAsync(minute);
    http.expectOne('/api/upload-status/41').flush({}, { status: 401, statusText: 'Unauthorized' });
    await settle();
    expect(upload.phase()).toBe('unknown');
    expect(auth.authenticated()).toBe(true);
    await vi.advanceTimersByTimeAsync(minute);
    http.expectNone('/api/upload-status/41');
    http.expectNone('/api/user/auth/refresh');
    upload.retryStatus();
    await settle();
    http.expectOne('/api/upload-status/41').flush({}, { status: 401, statusText: 'Unauthorized' });
    await settle();
    http
      .expectOne('/api/user/auth/csrf')
      .flush({ token: 'csrf-refresh', headerName: 'X-XSRF-TOKEN' });
    http.expectOne('/api/user/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    await settle();
    expect(auth.authenticated()).toBe(false);
    expect(upload.receiptId()).toBeNull();
    expect(router.navigate).toHaveBeenCalled();
  });
  it('cancels and clears old-user polling, receipt, filename and notifications before a new user opens Home', async () => {
    await signIn();
    await beginUpload();
    await vi.advanceTimersByTimeAsync(minute);
    const oldStatus = http.expectOne('/api/upload-status/41');
    const notifications = TestBed.inject(NotificationService);
    notifications.show('Private first-user filename.pdf');
    const logout = auth.logout();
    expect(oldStatus.cancelled).toBe(true);
    expect(upload.receiptId()).toBeNull();
    expect(upload.fileName()).toBe('');
    expect(upload.busy()).toBe(false);
    expect(notifications.items()).toEqual([]);
    await settle();
    http
      .expectOne('/api/user/auth/csrf')
      .flush({ token: 'csrf-logout', headerName: 'X-XSRF-TOKEN' });
    http.expectOne('/api/user/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    await logout;
    await signIn('session-2', 'second');
    const fixture = TestBed.createComponent(HomeComponent);
    await settle();
    const search = http.expectOne('/api/search/books?size=5');
    expect(search.request.headers.get('Authorization')).toContain('session-2-access');
    search.flush({ books: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
    expect(fixture.componentInstance.upload.receiptId()).toBeNull();
    expect(fixture.componentInstance.upload.fileName()).toBe('');
    await vi.advanceTimersByTimeAsync(3 * minute);
    http.expectNone('/api/upload-status/41');
    http.expectNone('/api/user/auth/refresh');
    expect(notifications.items()).toEqual([]);
  });
  it('does not renew indirectly when a late background completion reloads the Home collection', async () => {
    await signIn();
    const fixture = TestBed.createComponent(HomeComponent);
    await settle();
    http
      .expectOne('/api/search/books?size=5')
      .flush({ books: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
    await beginUpload();
    for (let tick = 1; tick < 4; tick++) {
      await vi.advanceTimersByTimeAsync(minute);
      http.expectOne('/api/upload-status/41').flush(pending);
    }
    await vi.advanceTimersByTimeAsync(minute);
    const late = http.expectOne('/api/upload-status/41');
    await vi.advanceTimersByTimeAsync(minute + 1);
    late.flush({ ...pending, status: 'Success', message: 'Ready' });
    await settle();
    expect(upload.phase()).toBe('success');
    expect(fixture.componentInstance.error()).toContain('Automatic updates are paused');
    http.expectNone('/api/search/books?size=5');
    http.expectNone('/api/user/auth/refresh');
    fixture.componentInstance.reload();
    await settle();
    http
      .expectOne('/api/user/auth/csrf')
      .flush({ token: 'csrf-refresh', headerName: 'X-XSRF-TOKEN' });
    http.expectOne('/api/user/auth/refresh').flush(response());
    await settle();
    http
      .expectOne('/api/search/books?size=5')
      .flush({ books: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
  });
});
