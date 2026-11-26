import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Observable, of, Subject } from 'rxjs';
import { AuthApiService } from '../data-access/auth-api.service';
import { AuthResponse, SessionEvent } from '../models/auth.models';
import { AuthTabCoordinator } from './auth-tab-coordinator.service';
import { AuthSessionService } from './auth-session.service';
import { NotificationService } from '../../../shared/services/notification.service';

const start = Date.parse('2026-11-25T12:00:00Z');
function session(at = start, token = 'access-1', id = 'session-1'): AuthResponse {
  return {
    accessToken: token,
    tokenType: 'Bearer',
    serverTime: new Date(at).toISOString(),
    accessExpiresAt: new Date(at + 300_000).toISOString(),
    refreshExpiresAt: new Date(at + 600_000).toISOString(),
    absoluteExpiresAt: null,
    sessionId: id,
    user: {
      userId: 'u1',
      tenantId: 't1',
      membershipId: 'm1',
      email: 'reader@example.com',
      displayName: 'Reader',
      tenantName: 'Library',
    },
  };
}

describe('AuthSessionService rotating session coordination', () => {
  let api: {
    refresh: ReturnType<typeof vi.fn>;
    login: ReturnType<typeof vi.fn>;
    logout: ReturnType<typeof vi.fn>;
  };
  let router: {
    url: string;
    navigate: ReturnType<typeof vi.fn>;
    navigateByUrl: ReturnType<typeof vi.fn>;
  };
  let events: Subject<SessionEvent>;
  let publish: ReturnType<typeof vi.fn>;
  let service: AuthSessionService;
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(start);
    api = {
      refresh: vi.fn(() => of(session())),
      login: vi.fn(() => of(session())),
      logout: vi.fn(() => of(undefined)),
    };
    router = {
      url: '/',
      navigate: vi.fn(() => Promise.resolve(true)),
      navigateByUrl: vi.fn(() => Promise.resolve(true)),
    };
    events = new Subject<SessionEvent>();
    publish = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthApiService, useValue: api },
        { provide: Router, useValue: router },
        {
          provide: AuthTabCoordinator,
          useValue: {
            events,
            publish,
            exclusive: <T>(work: () => Promise<T>) => Promise.resolve().then(work),
          },
        },
      ],
    });
    service = TestBed.inject(AuthSessionService);
  });
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });

  it('queues concurrent protected calls behind one refresh and keeps only access state in memory', async () => {
    const response = new Subject<AuthResponse>();
    api.refresh.mockReturnValue(response);
    const first = service.token();
    const second = service.token();
    await Promise.resolve();
    expect(api.refresh).toHaveBeenCalledTimes(1);
    response.next(session());
    response.complete();
    expect(await first).toBe('access-1');
    expect(await second).toBe('access-1');
    expect(service.user()?.email).toBe('reader@example.com');
    expect(await service.token()).toBe('access-1');
    expect(api.refresh).toHaveBeenCalledTimes(1);
    expect(publish).toHaveBeenCalledWith(expect.objectContaining({ kind: 'session' }));
  });
  it('does not refresh unattended; authenticated usage after access expiry rotates the deadline', async () => {
    await service.token();
    await vi.advanceTimersByTimeAsync(300_001);
    expect(api.refresh).toHaveBeenCalledTimes(1);
    expect(service.authenticated()).toBe(true);
    api.refresh.mockReturnValue(of(session(start + 300_001, 'access-2')));
    expect(await service.token()).toBe('access-2');
    await vi.advanceTimersByTimeAsync(299_999);
    expect(service.authenticated()).toBe(true);
    await vi.advanceTimersByTimeAsync(300_001);
    expect(service.authenticated()).toBe(false);
    expect(api.refresh).toHaveBeenCalledTimes(2);
    expect(router.navigate).toHaveBeenCalledWith(['/user/login'], {
      queryParams: { reason: 'expired' },
    });
  });
  it('expires at exact refresh deadline and rechecks a suspended page on pageshow', async () => {
    await service.token();
    vi.setSystemTime(start + 600_000);
    window.dispatchEvent(new Event('pageshow'));
    expect(service.authenticated()).toBe(false);
    expect(api.refresh).toHaveBeenCalledTimes(1);
  });
  it('honors an earlier absolute deadline', async () => {
    api.refresh.mockReturnValue(
      of({ ...session(), absoluteExpiresAt: new Date(start + 20_000).toISOString() }),
    );
    await service.token();
    await vi.advanceTimersByTimeAsync(20_000);
    expect(service.authenticated()).toBe(false);
  });
  it('adopts another tab rotation using its clock offset without extending expiry for delivery delay', async () => {
    vi.setSystemTime(start + 590_000);
    events.next({ kind: 'session', response: session(), clockOffset: 0 });
    expect(service.authenticated()).toBe(true);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(service.authenticated()).toBe(false);
    expect(api.refresh).not.toHaveBeenCalled();
  });
  it('clears and redirects on a revoked refresh response', async () => {
    api.refresh.mockReturnValue(
      new Observable((subscriber) =>
        subscriber.error(
          new HttpErrorResponse({ status: 401, error: { code: 'SESSION_REVOKED' } }),
        ),
      ),
    );
    await expect(service.token()).rejects.toBeInstanceOf(HttpErrorResponse);
    expect(service.authenticated()).toBe(false);
    expect(router.navigate).toHaveBeenCalled();
  });
  it('cancels pending refresh on logout and rejects late broadcasts from the logged-out session', async () => {
    await service.token();
    const pending = new Subject<AuthResponse>();
    api.refresh.mockReturnValue(pending);
    const refresh = service.token(true, 'access-1').catch((error) => error as unknown);
    await Promise.resolve();
    const logout = service.logout();
    pending.next(session(start + 1, 'too-late'));
    pending.complete();
    await logout;
    await refresh;
    events.next({ kind: 'session', response: session(start + 1, 'too-late'), clockOffset: 0 });
    expect(service.authenticated()).toBe(false);
    expect(api.logout).toHaveBeenCalledTimes(1);
    expect(publish).toHaveBeenCalledWith({ kind: 'clear', sessionId: 'session-1' });
  });
  it('discards old Home identity when a different session logs in in another tab', async () => {
    await service.token();
    events.next({
      kind: 'session',
      response: session(start + 1, 'other', 'other-session'),
      clockOffset: 0,
    });
    expect(service.authenticated()).toBe(false);
    expect(router.navigate).toHaveBeenCalled();
  });
  it('stays signed out after failed server logout and does not bootstrap the old cookie', async () => {
    await service.token();
    api.logout.mockReturnValue(
      new Observable((subscriber) => subscriber.error(new HttpErrorResponse({ status: 0 }))),
    );
    await service.logout();
    await expect(service.token()).rejects.toThrow('signed out');
    expect(TestBed.inject(NotificationService).items()).toEqual([
      expect.objectContaining({
        severity: 'warning',
        message: expect.stringContaining('A new tab or reload may still use the server cookie'),
      }),
    ]);
    expect(api.refresh).toHaveBeenCalledTimes(1);
    events.next({
      kind: 'session',
      response: session(start + 1, 'old-cookie-access'),
      clockOffset: 0,
    });
    events.next({
      kind: 'session',
      response: session(start + 2, 'other-tab-access', 'other-session'),
      clockOffset: 0,
    });
    expect(service.authenticated()).toBe(false);
    api.login.mockReturnValue(of(session(start + 3, 'manual-login-access', 'new-session')));
    await service.login('reader@example.com', 'a long password');
    expect(await service.token()).toBe('manual-login-access');
  });
  it('rejects a tombstoned session returned by HTTP even when no local logout latch exists', async () => {
    events.next({ kind: 'clear', sessionId: 'session-1' });
    await expect(service.token()).rejects.toThrow();
    expect(api.refresh).toHaveBeenCalledTimes(1);
    expect(service.authenticated()).toBe(false);
  });
  it('does not allow a stale login response to reactivate an invalidated session', async () => {
    await service.token();
    service.invalidate();
    await expect(service.login('reader@example.com', 'a long password')).rejects.toThrow();
    expect(service.authenticated()).toBe(false);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    await expect(service.token()).rejects.toThrow('signed out');
    expect(api.refresh).toHaveBeenCalledTimes(1);
  });
  it('ignores an old logged-out broadcast after a new login instead of clearing the new identity', async () => {
    await service.token();
    await service.logout();
    api.login.mockReturnValue(of(session(start + 1, 'new-access', 'new-session')));
    await service.login('reader@example.com', 'a long password');
    events.next({ kind: 'session', response: session(), clockOffset: 0 });
    expect(service.authenticated()).toBe(true);
    expect(await service.token()).toBe('new-access');
  });
  it('logs in to Home and coalesces a rejected old token with a newer token already received', async () => {
    await service.login('reader@example.com', 'a long password');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
    events.next({ kind: 'session', response: session(start + 1, 'access-2'), clockOffset: 0 });
    expect(await service.token(true, 'access-1')).toBe('access-2');
    expect(api.refresh).not.toHaveBeenCalled();
  });
});
