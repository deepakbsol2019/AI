import { HttpErrorResponse } from '@angular/common/http';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom, Subject, takeUntil } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NotificationService } from '../../../shared/services/notification.service';
import { AuthApiService } from '../data-access/auth-api.service';
import { AuthResponse } from '../models/auth.models';
import { AuthTabCoordinator } from './auth-tab-coordinator.service';
import { AuthBoundaryService } from './auth-boundary.service';

@Injectable({ providedIn: 'root' })
export class AuthSessionService {
  private readonly api = inject(AuthApiService);
  private readonly tabs = inject(AuthTabCoordinator);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);
  private readonly boundary = inject(AuthBoundaryService);
  private readonly state = signal<AuthResponse | null>(null);
  readonly user = computed(() => this.state()?.user ?? null);
  readonly authenticated = computed(() => this.state() !== null);
  readonly loggingOut = signal(false);
  private offset = 0;
  private epoch = 0;
  private refreshWork: Promise<string> | null = null;
  private pendingAbort = new AbortController();
  private readonly stop = new Subject<void>();
  private timer: ReturnType<typeof setTimeout> | undefined;
  private readonly rejectedSessions = new Set<number | string>();
  private signedOut = false;
  private latestServerTime = 0;

  constructor() {
    this.tabs.events.pipe(takeUntilDestroyed()).subscribe((event) => {
      if (event.kind === 'session') {
        if (
          this.rejectedSessions.has(event.response.sessionId) ||
          Date.parse(event.response.serverTime) < this.latestServerTime
        )
          return;
        const previous = this.state();
        if (previous && previous.sessionId !== event.response.sessionId) {
          this.rejectedSessions.add(previous.sessionId);
          this.clear(false);
          this.toLogin();
          this.notifications.show(
            'The account changed in another tab. Log in again to open a fresh library.',
            'info',
          );
          return;
        }
        this.accept(event.response, false, event.clockOffset);
      } else if (event.sessionId === null || this.state()?.sessionId === event.sessionId) {
        const currentId = this.state()?.sessionId;
        if (currentId !== undefined) this.rejectedSessions.add(currentId);
        if (event.sessionId !== null) this.rejectedSessions.add(event.sessionId);
        this.signedOut = true;
        this.clear(false);
        this.toLogin();
      } else if (event.sessionId !== null) this.rejectedSessions.add(event.sessionId);
    });
    const resume = () => this.checkDeadline();
    const visibility = () => {
      if (document.visibilityState === 'visible') resume();
    };
    window.addEventListener('focus', resume);
    window.addEventListener('pageshow', resume);
    document.addEventListener('visibilitychange', visibility);
    inject(DestroyRef).onDestroy(() => {
      clearTimeout(this.timer);
      this.pendingAbort.abort();
      this.stop.next();
      this.stop.complete();
      window.removeEventListener('focus', resume);
      window.removeEventListener('pageshow', resume);
      document.removeEventListener('visibilitychange', visibility);
    });
  }

  /** Called only by navigation, a protected request, or an OIDC callback; never by a keepalive timer. */
  token(force = false, rejectedToken?: string): Promise<string> {
    if (this.signedOut) {
      this.toLogin();
      return Promise.reject(new Error('You are signed out. Please log in again.'));
    }
    if (!this.checkDeadline())
      return Promise.reject(new Error('Your session has expired. Please log in again.'));
    const current = this.state();
    if (
      this.usable(current) &&
      (!force || (rejectedToken !== undefined && current.accessToken !== rejectedToken))
    ) {
      return Promise.resolve(current.accessToken);
    }
    if (this.refreshWork) return this.refreshWork;
    const epoch = this.epoch;
    const work = this.tabs
      .exclusive(async () => {
        if (epoch !== this.epoch) throw new Error('Sign-in was cancelled.');
        const latest = this.state();
        if (
          this.usable(latest) &&
          (!force || (rejectedToken !== undefined && latest.accessToken !== rejectedToken))
        )
          return latest.accessToken;
        const response = await firstValueFrom(this.api.refresh().pipe(takeUntil(this.stop)));
        if (epoch !== this.epoch) throw new Error('Sign-in was cancelled.');
        if (!this.accept(response, true))
          throw new Error('Your session has expired. Please log in again.');
        return response.accessToken;
      }, this.pendingAbort.signal)
      .catch((error: unknown) => {
        if (
          epoch === this.epoch &&
          ((error instanceof HttpErrorResponse && error.status === 401) || !this.state())
        ) {
          this.clear(true);
          this.toLogin();
        }
        throw error;
      });
    this.refreshWork = work;
    void work
      .finally(() => {
        if (this.refreshWork === work) this.refreshWork = null;
      })
      .catch(() => undefined);
    return work;
  }

  /** Background polling must never rotate a cookie or wait for a foreground refresh. */
  tokenForBackgroundRequest(): string | null {
    if (this.signedOut || !this.checkDeadline()) return null;
    const current = this.state();
    return this.usable(current) ? current.accessToken : null;
  }

  async login(email: string, password: string, mfaCode?: string): Promise<void> {
    const previousId = this.state()?.sessionId;
    if (previousId !== undefined) this.rejectedSessions.add(previousId);
    this.signedOut = true;
    this.clear(false);
    const epoch = this.epoch;
    await this.tabs.exclusive(async () => {
      const response = await firstValueFrom(
        this.api.login(email, password, mfaCode).pipe(takeUntil(this.stop)),
      );
      if (epoch !== this.epoch) throw new Error('Sign-in was cancelled.');
      if (!this.accept(response, true, undefined, true))
        throw new Error('Your session has expired. Please log in again.');
    }, this.pendingAbort.signal);
    await this.router.navigateByUrl('/');
  }
  async logout(): Promise<void> {
    if (this.loggingOut()) return;
    this.loggingOut.set(true);
    this.signedOut = true;
    this.clear(true);
    this.toLogin();
    try {
      await this.tabs.exclusive(() => firstValueFrom(this.api.logout()));
    } catch {
      this.notifications.show(
        'You are signed out in this tab, but server logout could not be confirmed. A new tab or reload may still use the server cookie until it expires or logout succeeds.',
        'warning',
      );
    } finally {
      this.loggingOut.set(false);
    }
  }
  invalidate(): void {
    this.signedOut = true;
    this.clear(true);
    this.toLogin();
  }
  checkDeadline(): boolean {
    const current = this.state();
    if (current && this.now() >= this.deadline(current)) {
      // Do not send a stale logout cookie: another tab may already have rotated it.
      this.clear(false);
      this.toLogin('expired');
      return false;
    }
    return true;
  }
  private usable(value: AuthResponse | null): value is AuthResponse {
    return (
      value !== null &&
      this.now() < Date.parse(value.accessExpiresAt) &&
      this.now() < this.deadline(value)
    );
  }
  private now(): number {
    return Date.now() + this.offset;
  }
  private deadline(value: AuthResponse): number {
    return Math.min(
      Date.parse(value.refreshExpiresAt),
      value.absoluteExpiresAt ? Date.parse(value.absoluteExpiresAt) : Infinity,
    );
  }
  private accept(
    value: AuthResponse,
    broadcast: boolean,
    clockOffset?: number,
    manualLogin = false,
  ): boolean {
    // Apply revocation to HTTP responses too, including a still-valid cookie after offline logout.
    if (this.rejectedSessions.has(value.sessionId) || (this.signedOut && !manualLogin))
      return false;
    if (
      ![
        Date.parse(value.serverTime),
        Date.parse(value.accessExpiresAt),
        this.deadline(value),
      ].every(Number.isFinite) ||
      !value.accessToken
    ) {
      throw new Error('The server returned invalid session expiry information.');
    }
    this.offset = clockOffset ?? Date.parse(value.serverTime) - Date.now();
    this.latestServerTime = Date.parse(value.serverTime);
    if (this.now() >= this.deadline(value)) {
      this.clear(false);
      this.toLogin('expired');
      return false;
    }
    if (manualLogin) this.signedOut = false;
    this.state.set(value);
    clearTimeout(this.timer);
    this.timer = setTimeout(
      () => this.checkDeadline(),
      Math.max(0, this.deadline(value) - this.now()),
    );
    if (broadcast)
      this.tabs.publish({ kind: 'session', response: value, clockOffset: this.offset });
    return true;
  }
  private clear(broadcast: boolean): void {
    const id = this.state()?.sessionId ?? null;
    if (id !== null && broadcast) this.rejectedSessions.add(id);
    this.epoch++;
    this.pendingAbort.abort();
    this.pendingAbort = new AbortController();
    this.stop.next();
    this.refreshWork = null;
    clearTimeout(this.timer);
    this.state.set(null);
    this.boundary.reset();
    this.notifications.clear();
    if (broadcast) this.tabs.publish({ kind: 'clear', sessionId: id });
  }
  private toLogin(reason?: string): void {
    if (
      !this.router.url.startsWith('/user/') ||
      this.router.url.startsWith('/user/roles') ||
      this.router.url.startsWith('/user/users')
    )
      void this.router.navigate(['/user/login'], { queryParams: reason ? { reason } : {} });
  }
}
