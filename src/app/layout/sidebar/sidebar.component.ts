import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  output,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthSessionService } from '../../features/user/services/auth-session.service';
import { CurrentProfileService } from '../../features/user/services/current-profile.service';
import { NavigationAccessService } from '../../features/user/services/navigation-access.service';
import { apiErrorMessage } from '../../shared/utils/api-error';

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@if (session.user(); as user) {
    <div class="sidebar-top">
      <span>My workspace</span
      ><button
        type="button"
        class="sidebar-close"
        aria-label="Collapse sidebar"
        (click)="collapse.emit()"
      >
        &#10094;
      </button>
    </div>
    <div class="sidebar-scroll">
      <section aria-label="Your profile" class="sidebar-profile">
        <svg
          class="profile-avatar"
          role="img"
          aria-label="Default illustrated profile avatar"
          viewBox="0 0 96 96"
        >
          <circle cx="48" cy="48" r="46" fill="#e7effa" />
          <path d="M16 78c4-18 16-26 32-26s28 8 32 26" fill="#315a89" />
          <circle cx="48" cy="35" r="18" fill="#b9cce4" />
          <path
            d="M30 32c1-14 8-21 20-21 11 0 18 10 17 21-7-3-10-8-12-11-5 6-13 10-25 11Z"
            fill="#26456a"
          />
          <path d="m31 72 17 7 17-7v12l-17 7-17-7Z" fill="#fff" />
          <path d="M48 79v12" stroke="#9bb2cd" stroke-width="2" />
        </svg>
        <dl>
          <dt>Username</dt>
          <dd>{{ profiles.profile()?.username || user.email }}</dd>
          <dt>Name</dt>
          <dd>{{ profiles.profile()?.displayName || user.displayName || 'Not available' }}</dd>
          <dt>Account Type</dt>
          <dd>
            {{ tenantType(profiles.profile()?.tenantType || access.capabilities()?.tenantType) }}
          </dd>
          <dt>Access Roles</dt>
          <dd>
            @for (role of profiles.profile()?.roles || []; track $index) {
              <span class="role-name">{{ role }}</span>
            } @empty {
              <span>{{ profileLoading() ? 'Loading…' : 'No assigned roles' }}</span>
            }
          </dd>
        </dl>
        <div class="profile-actions">
          <a class="btn btn-outline-primary" routerLink="/user/profile" (click)="navigate.emit()"
            >View Your Detail</a
          >
          @if (access.capabilities()?.canCreateUsers) {
            <a class="btn btn-primary" routerLink="/user/users/new" (click)="navigate.emit()"
              >Add User</a
            >
          }
        </div>
        @if (profileLoading()) {
          <p role="status" class="sidebar-note">Loading profile…</p>
        }
        @if (profileError()) {
          <p role="alert" class="sidebar-error">{{ profileError() }}</p>
          <button type="button" class="btn btn-sm btn-outline-primary" (click)="loadProfile()">
            Retry profile
          </button>
        }
      </section>
      <nav aria-label="Workspace navigation">
        <a routerLink="/user/uploads" routerLinkActive="active" (click)="navigate.emit()"
          >My Uploads</a
        >
        <a
          routerLink="/"
          routerLinkActive="active"
          [routerLinkActiveOptions]="{ exact: true }"
          (click)="navigate.emit()"
          >Library</a
        >
        @if (access.capabilities()?.canViewUsers) {
          <a
            routerLink="/user/users"
            routerLinkActive="active"
            [routerLinkActiveOptions]="{ exact: true }"
            (click)="navigate.emit()"
            >User Management</a
          >
        }
        @if (access.capabilities()?.canManageRoles) {
          <a routerLink="/user/roles" routerLinkActive="active" (click)="navigate.emit()"
            >Role Management</a
          >
        }
      </nav>
      @if (access.loading()) {
        <p role="status" class="sidebar-note">Loading navigation…</p>
      }
      @if (access.error()) {
        <p role="alert" class="sidebar-error">Navigation could not be loaded.</p>
        <button type="button" class="btn btn-sm btn-outline-primary" (click)="access.refresh()">
          Retry navigation
        </button>
      }
    </div>
    <div class="sidebar-bottom">
      <button
        type="button"
        class="btn btn-primary logout-button"
        [disabled]="session.loggingOut()"
        [attr.aria-label]="'Logout ' + user.email"
        (click)="session.logout()"
      >
        {{ session.loggingOut() ? 'Logging out…' : 'Logout' }}
      </button>
    </div>
  }`,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      min-height: 0;
      min-width: 0;
      color: var(--ink);
      background: var(--panel-background, #fff);
      border-right: 1px solid var(--line);
    }
    .sidebar-top {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex: 0 0 auto;
      padding: 0.65rem 1rem;
      gap: 0.5rem;
      font-weight: 700;
      font-size: 0.83rem;
      border-bottom: 1px solid var(--line);
    }
    .sidebar-close {
      color: var(--blue-dark);
      background: transparent;
      border: 1px solid var(--line);
      border-radius: 0.4rem;
      min-width: 36px;
      min-height: 36px;
    }
    .sidebar-scroll {
      flex: 1 1 0;
      min-height: 0;
      overflow: auto;
      padding: 1rem;
      overscroll-behavior: contain;
    }
    .sidebar-profile {
      text-align: center;
    }
    .profile-avatar {
      width: 64px;
      height: 64px;
      margin-bottom: 0.5rem;
    }
    dl {
      margin: 0;
      text-align: left;
      overflow-wrap: anywhere;
    }
    dt {
      font-size: 0.7rem;
      color: var(--muted);
      margin-top: 0.5rem;
    }
    dd {
      font-size: 0.78rem;
      font-weight: 600;
      margin: 0;
      padding: 0.35rem 0.5rem;
      border-radius: 0.35rem;
      background: #174783;
      color: #fff;
      line-height: 1.4;
      cursor: text;
    }
    .role-name {
      display: block;
      font-weight: 500;
    }
    .profile-actions {
      display: grid;
      gap: 0.5rem;
      margin-top: 0.9rem;
    }
    .profile-actions .btn {
      min-height: 34px;
      font-size: 0.8rem;
      padding: 0.4rem 0.5rem;
    }
    nav {
      display: grid;
      gap: 0.35rem;
      border-top: 1px solid var(--line);
      padding-top: 1rem;
      margin-top: 1rem;
    }
    nav a {
      padding: 0.65rem 0.6rem;
      border-radius: 0.4rem;
      text-decoration: none;
      overflow-wrap: anywhere;
      font-size: 0.85rem;
      font-weight: 600;
    }
    nav a:hover,
    nav a.active {
      background: #e4edf9;
      color: #173f73;
    }
    .sidebar-bottom {
      padding: 0.8rem 1rem;
      border-top: 1px solid var(--line);
      flex: 0 0 auto;
    }
    .logout-button {
      width: 100%;
    }
    .sidebar-note {
      font-size: 0.76rem;
      color: var(--muted);
      margin-top: 0.6rem;
    }
    .sidebar-error {
      font-size: 0.78rem;
      color: #9a1c20;
      margin-top: 0.6rem;
      overflow-wrap: anywhere;
    }
    @media (max-height: 400px) {
      .profile-avatar {
        width: 48px;
        height: 48px;
      }
      .sidebar-top,
      .sidebar-bottom {
        padding: 0.35rem 0.6rem;
      }
    }
  `,
})
export class SidebarComponent {
  protected readonly session = inject(AuthSessionService);
  readonly profiles = inject(CurrentProfileService);
  readonly access = inject(NavigationAccessService);
  readonly collapse = output<void>();
  readonly navigate = output<void>();
  readonly profileLoading = signal(false);
  readonly profileError = signal<string | null>(null);
  private request = 0;
  constructor() {
    effect(() => {
      const identity = this.session.user()?.userId;
      untracked(() => {
        if (identity) {
          void this.loadProfile();
          void this.access.ensure();
        } else {
          this.request++;
          this.profileError.set(null);
          this.profileLoading.set(false);
        }
      });
    });
  }
  tenantType(type: string | undefined): string {
    return type === 'INDIVIDUAL'
      ? 'Individual'
      : type === 'ORGANIZATION'
        ? 'Organisation'
        : 'Not available';
  }
  async loadProfile() {
    const identity = this.session.user()?.userId;
    if (!identity) return;
    const request = ++this.request;
    this.profileLoading.set(true);
    this.profileError.set(null);
    try {
      await this.profiles.refresh();
    } catch (error) {
      if (request === this.request) this.profileError.set(await apiErrorMessage(error));
    } finally {
      if (request === this.request) this.profileLoading.set(false);
    }
  }
}
