import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  UserManagementApiService,
  OwnUserDetails,
  OwnPermissions,
} from '../data-access/user-management-api.service';
import { apiErrorMessage } from '../../../shared/utils/api-error';

@Component({
  selector: 'app-own-user-details',
  standalone: true,
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="management-heading">
      <h1>Your Information</h1>
      <a class="close-page" routerLink="/" aria-label="Close your information and return to Library"
        >&#10005;</a
      >
    </header>
    <nav class="page-actions" aria-label="Your information navigation">
      <a class="btn btn-primary" routerLink="/">Back to Library</a>
      <a class="btn btn-outline-primary" routerLink="/" [queryParams]="{ profile: 'open' }"
        >Back to User Profile</a
      >
    </nav>
    @if (loading()) {
      <p role="status">Loading your information…</p>
    }
    @if (error()) {
      <p role="alert" class="auth-error">{{ error() }}</p>
      <button class="btn btn-outline-primary" (click)="load()">Retry</button>
    }
    @if (details(); as user) {
      <table class="table profile-information" aria-label="Your user information">
        <tbody>
          <tr>
            <th scope="row">Name</th>
            <td>{{ user.displayName || 'Not available' }}</td>
          </tr>
          <tr>
            <th scope="row">Username</th>
            <td>{{ user.username }}</td>
          </tr>
          <tr>
            <th scope="row">Tenant Name</th>
            <td>{{ user.tenantName || 'Not available' }}</td>
          </tr>
          <tr>
            <th scope="row">Role</th>
            <td>
              @for (role of user.roles; track $index) {
                <div>{{ role }}</div>
              } @empty {
                No assigned roles
              }
            </td>
          </tr>
        </tbody>
      </table>
      <button type="button" class="btn btn-primary" (click)="openPermissions()">
        View Permissions
      </button>
    }
    @if (permissionsOpen()) {
      <div class="modal-shade" (click)="outside($event)">
        <section
          class="permission-modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="own-permissions-title"
          tabindex="-1"
        >
          <header class="management-heading">
            <h2 id="own-permissions-title">Your Permissions</h2>
            <button
              type="button"
              class="close-page"
              (click)="closePermissions()"
              aria-label="Close permissions"
            >
              &#10005;
            </button>
          </header>
          <p class="permission-username">
            <strong>Username:</strong> {{ permissions()?.username || details()?.username }}
          </p>
          @if (permissionsLoading()) {
            <p role="status">Loading your permissions…</p>
          }
          @if (permissionsError()) {
            <p role="alert" class="auth-error">{{ permissionsError() }}</p>
            <button class="btn btn-outline-primary" (click)="refreshPermissions()">
              Retry permissions
            </button>
          }
          @if (permissions(); as data) {
            <div
              class="table-responsive"
              tabindex="0"
              role="region"
              aria-label="Your permission information"
            >
              <table class="table permission-table">
                <thead>
                  <tr>
                    <th>Permission</th>
                    <th>Description / Details</th>
                    <th>Service</th>
                    <th>Component</th>
                    <th>Applicable URL</th>
                    <th>Allowed Operations</th>
                    <th>Granted Through</th>
                  </tr>
                </thead>
                <tbody>
                  @for (permission of data.permissions; track permission.permissionId) {
                    @for (route of permission.routes; track $index) {
                      <tr>
                        <td>
                          {{ permission.name
                          }}<small>{{ permission.code }} · {{ permission.kind }}</small>
                        </td>
                        <td>{{ permission.description || 'Not available' }}</td>
                        <td>{{ route.service }}</td>
                        <td>{{ permission.component }}</td>
                        <td>
                          <code>{{ route.url }}</code>
                        </td>
                        <td>
                          {{ route.operations.join(', ') || 'Not available'
                          }}<small>{{ route.method }}</small>
                        </td>
                        <td>{{ permission.sourceRoles.join(', ') || 'Not available' }}</td>
                      </tr>
                    } @empty {
                      <tr>
                        <td>
                          {{ permission.name
                          }}<small>{{ permission.code }} · {{ permission.kind }}</small>
                        </td>
                        <td>{{ permission.description || 'Not available' }}</td>
                        <td>Not available</td>
                        <td>{{ permission.component }}</td>
                        <td>No mapped URL</td>
                        <td>Not available</td>
                        <td>{{ permission.sourceRoles.join(', ') || 'Not available' }}</td>
                      </tr>
                    }
                  } @empty {
                    <tr>
                      <td colspan="7">No currently effective permissions.</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
          <div class="page-actions">
            <button
              type="button"
              class="btn btn-outline-primary"
              [disabled]="permissionsLoading()"
              (click)="refreshPermissions()"
            >
              Refresh Permissions</button
            ><button type="button" class="btn btn-primary" (click)="closePermissions()">
              Close
            </button>
          </div>
        </section>
      </div>
    }
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    .management-heading {
      display: flex;
      justify-content: space-between;
      align-items: start;
      gap: 1rem;
      flex-wrap: wrap;
      margin-bottom: 1.25rem;
    }
    .page-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.65rem;
      margin: 1rem 0;
    }
    .close-page {
      color: var(--ink);
      border: 1px solid var(--line);
      background: #fff;
      border-radius: 0.4rem;
      padding: 0.4rem 0.65rem;
      text-decoration: none;
      min-height: 40px;
    }
    .profile-information {
      max-width: 55rem;
    }
    td {
      overflow-wrap: anywhere;
    }
    .profile-information th {
      width: 11rem;
    }
    .modal-shade {
      position: fixed;
      inset: 0;
      background: #10243f99;
      z-index: 2000;
      display: grid;
      place-items: center;
      padding: 1rem;
    }
    .permission-modal {
      background: #fff;
      border-radius: 0.8rem;
      padding: clamp(1rem, 3vw, 2rem);
      width: min(98vw, 84rem);
      max-height: 90dvh;
      overflow: auto;
      box-shadow: 0 1rem 4rem #10243f55;
    }
    .table-responsive {
      overflow-x: auto;
      max-width: 100%;
    }
    .permission-table {
      min-width: 65rem;
    }
    small {
      display: block;
      color: var(--muted);
      font-size: 0.8rem;
    }
    .permission-username {
      overflow-wrap: anywhere;
    }
  `,
})
export class OwnUserDetailsComponent {
  private readonly api = inject(UserManagementApiService);
  private readonly destroy = inject(DestroyRef);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  readonly details = signal<OwnUserDetails | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly permissionsOpen = signal(false);
  readonly permissions = signal<OwnPermissions | null>(null);
  readonly permissionsLoading = signal(false);
  readonly permissionsError = signal<string | null>(null);
  private returnFocus: HTMLElement | null = null;
  private permissionEpoch = 0;
  constructor() {
    void this.load();
  }
  async load() {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.details.set(
        await firstValueFrom(this.api.ownDetails().pipe(takeUntilDestroyed(this.destroy))),
      );
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }
  openPermissions() {
    this.returnFocus =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.permissionsOpen.set(true);
    void this.refreshPermissions();
    setTimeout(() =>
      this.element.nativeElement.querySelector<HTMLElement>('[role="dialog"]')?.focus(),
    );
  }
  async refreshPermissions() {
    const epoch = ++this.permissionEpoch;
    this.permissionsLoading.set(true);
    this.permissionsError.set(null);
    try {
      const permissions = await firstValueFrom(
        this.api.ownPermissions().pipe(takeUntilDestroyed(this.destroy)),
      );
      if (epoch === this.permissionEpoch) this.permissions.set(permissions);
    } catch (error) {
      if (epoch === this.permissionEpoch) this.permissionsError.set(await apiErrorMessage(error));
    } finally {
      if (epoch === this.permissionEpoch) this.permissionsLoading.set(false);
    }
  }
  closePermissions() {
    this.permissionEpoch++;
    this.permissionsOpen.set(false);
    this.permissionsLoading.set(false);
    this.returnFocus?.focus();
  }
  outside(event: MouseEvent) {
    if (event.target === event.currentTarget) this.closePermissions();
  }
  @HostListener('keydown', ['$event']) keyboard(event: KeyboardEvent) {
    if (!this.permissionsOpen()) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      this.closePermissions();
      return;
    }
    if (event.key !== 'Tab') return;
    const dialog = this.element.nativeElement.querySelector<HTMLElement>('[role="dialog"]');
    const items = dialog?.querySelectorAll<HTMLElement>(
      'button:not([disabled]),a[href],[tabindex="0"]',
    );
    if (!items?.length) return;
    const first = items[0],
      last = items[items.length - 1];
    if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }
}
