import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  ViewChild,
  effect,
  inject,
  signal,
} from '@angular/core';
import { AuthSessionService } from '../services/auth-session.service';
import { CurrentProfileService } from '../services/current-profile.service';
import { apiErrorMessage } from '../../../shared/utils/api-error';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-user-session-control',
  standalone: true,
  imports: [RouterLink],
  template: `@if (session.user(); as user) {
    <a class="btn btn-outline-primary" routerLink="/user/roles">Role Management</a>
    <a class="btn btn-outline-primary" routerLink="/user/users">User Management</a>
    <div class="profile-control">
      <button
        #profileTrigger
        type="button"
        class="btn btn-outline-primary profile-trigger"
        aria-haspopup="dialog"
        [attr.aria-expanded]="open()"
        aria-controls="current-profile-panel"
        (click)="toggle()"
        [attr.aria-label]="'Profile for ' + user.email"
      >
        ● {{ store.profile()?.displayName || user.displayName || 'Profile' }}
      </button>
      @if (open()) {
        <section
          id="current-profile-panel"
          role="dialog"
          aria-label="Your profile"
          class="profile-panel"
        >
          <button
            #profileClose
            type="button"
            class="btn btn-sm btn-outline-secondary"
            (click)="close(true)"
          >
            Close profile
          </button>
          @if (loading()) {
            <p role="status">Loading your profile…</p>
          }
          @if (error()) {
            <p role="alert">{{ error() }}</p>
          }
          @if (store.profile(); as profile) {
            <dl>
              <dt>Username</dt>
              <dd>{{ profile.username }}</dd>
              <dt>Name</dt>
              <dd>{{ profile.displayName || 'Not available' }}</dd>
              <dt>Account</dt>
              <dd>
                {{
                  profile.tenantType === 'INDIVIDUAL' ? 'Standalone individual' : profile.tenantName
                }}
              </dd>
              <dt>Assigned roles</dt>
              <dd>
                @for (role of profile.roles; track $index) {
                  <div>{{ role }}</div>
                } @empty {
                  No assigned roles
                }
              </dd>
            </dl>
          }
        </section>
      }
    </div>
    <button
      type="button"
      class="btn btn-primary logout-button"
      [disabled]="session.loggingOut()"
      [attr.aria-label]="'Logout ' + user.email"
      (click)="session.logout()"
    >
      {{ session.loggingOut() ? 'Logging out…' : 'Logout' }}
    </button>
  }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      :host {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        flex-wrap: wrap;
      }
      .profile-control {
        position: relative;
      }
      .profile-trigger {
        max-width: 18rem;
        overflow-wrap: anywhere;
      }
      .profile-panel {
        position: absolute;
        right: 0;
        top: calc(100% + 0.5rem);
        z-index: 1200;
        background: var(--panel-background, #fff);
        color: var(--ink, #152d47);
        border: 1px solid #bbb;
        border-radius: 0.5rem;
        box-shadow: 0 6px 24px #0003;
        padding: 1rem;
        width: min(22rem, 88vw);
      }
      dd {
        overflow-wrap: anywhere;
      }
    `,
  ],
})
export class UserSessionControlComponent {
  protected readonly session = inject(AuthSessionService);
  readonly store = inject(CurrentProfileService);
  private readonly element = inject(ElementRef);
  readonly open = signal(false);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  private request = 0;
  @ViewChild('profileTrigger') trigger?: ElementRef<HTMLButtonElement>;
  @ViewChild('profileClose') closeButton?: ElementRef<HTMLButtonElement>;
  constructor() {
    effect(() => {
      if (!this.session.user()) {
        this.open.set(false);
        this.error.set(null);
        this.request++;
      }
    });
  }
  async toggle() {
    if (this.open()) {
      this.close(false);
      return;
    }
    this.open.set(true);
    this.loading.set(true);
    this.error.set(null);
    const request = ++this.request;
    setTimeout(() => this.closeButton?.nativeElement.focus());
    try {
      await this.store.refresh();
    } catch (error) {
      if (request === this.request) this.error.set(await apiErrorMessage(error));
    } finally {
      if (request === this.request) this.loading.set(false);
    }
  }
  close(focus: boolean) {
    this.open.set(false);
    this.request++;
    if (focus) this.trigger?.nativeElement.focus();
  }
  @HostListener('document:keydown.escape') escape() {
    if (this.open()) this.close(true);
  }
  @HostListener('document:click', ['$event']) outside(event: MouseEvent) {
    if (this.open() && !this.element.nativeElement.contains(event.target)) this.close(false);
  }
}
