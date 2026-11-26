import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  inject,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { combineLatest, firstValueFrom, Observable } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  UserManagementApiService,
  UserPage,
  UserDetails,
  UserRole,
  DeletionPreview,
  DeletionJob,
  CreateUserOptions,
} from '../data-access/user-management-api.service';
import { AuthSessionService } from '../services/auth-session.service';
import { CurrentProfileService } from '../services/current-profile.service';
import { NavigationAccessService } from '../services/navigation-access.service';
import { apiErrorMessage } from '../../../shared/utils/api-error';

type PageMode = 'list' | 'details' | 'create' | 'delete';

/** Routed management pages share behavior and list context; none opens a dialog. */
@Component({
  selector: 'app-user-management',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
        color: var(--ink, #172b46);
      }
      .page-links,
      .page-actions,
      .pagination-controls {
        display: flex;
        gap: 0.7rem;
        flex-wrap: wrap;
        align-items: center;
        margin-block: 1rem;
      }
      .table-responsive {
        max-width: 100%;
        overflow-x: auto;
      }
      table {
        width: 100%;
        min-width: 58rem;
      }
      td,
      th {
        vertical-align: top;
        overflow-wrap: anywhere;
      }
      .secondary {
        font-size: 0.85rem;
        color: var(--muted, #536275);
      }
      .danger {
        border-left: 5px solid #ad2020;
        padding: 1rem;
        background: #fff5f5;
        margin-block: 1rem;
      }
      dl {
        display: grid;
        grid-template-columns: minmax(8rem, 1fr) 2fr;
        gap: 0.5rem;
      }
      dt {
        font-weight: 650;
      }
      dd {
        overflow-wrap: anywhere;
      }
      label {
        display: block;
      }
      fieldset {
        margin-block: 1rem;
      }
      .role-summary {
        margin-block: 0.65rem;
      }
      .profile-form,
      .create-form {
        max-width: 40rem;
      }
      .profile-form input,
      .create-form input {
        margin-bottom: 1rem;
      }
      .status-note {
        display: block;
      }
      .confirmation-page {
        max-width: 56rem;
      }
      @media (max-width: 480px) {
        dl {
          grid-template-columns: 1fr;
          gap: 0.25rem;
        }
        dd {
          margin-bottom: 0.5rem;
        }
      }
    `,
  ],
  template: `
    <h1>
      {{
        mode() === 'list'
          ? 'User Management'
          : mode() === 'create'
            ? 'Create User'
            : mode() === 'delete'
              ? 'Confirm ' + (hardDelete() ? 'Hard Delete' : 'Soft Delete')
              : 'User Details'
      }}
    </h1>
    <nav class="page-links" aria-label="User Management pages">
      @if (mode() !== 'list' && access.capabilities()?.canViewUsers) {
        <a [routerLink]="['/user/users']" [queryParams]="tableParams()" (click)="back($event)"
          >Back to User Management</a
        >
      }
      <a routerLink="/">Back to library</a>
      @if (mode() === 'list' && access.capabilities()?.canCreateUsers) {
        <a class="btn btn-primary" [routerLink]="['/user/users/new']" [queryParams]="tableParams()"
          >Create User</a
        >
      }
    </nav>
    @if (error()) {
      <p role="alert" class="auth-error">{{ error() }}</p>
    }
    @if (message()) {
      <p role="status" class="auth-success">{{ message() }}</p>
    }
    @if (discardPrompt()) {
      <section class="danger" aria-label="Unsaved changes">
        <p>Discard your unsaved profile or role changes?</p>
        <button type="button" class="btn btn-danger" (click)="discardAndReturn()">
          Discard changes and return
        </button>
        <button type="button" class="btn btn-outline-primary" (click)="discardPrompt.set(false)">
          Keep editing
        </button>
      </section>
    }
    @if (loading()) {
      <p role="status">
        Loading authorized {{ mode() === 'list' ? 'users' : 'user information' }}…
      </p>
    }

    @if (mode() === 'list') {
      <form [formGroup]="filters" (ngSubmit)="search()" class="row g-2 my-3">
        <div class="col">
          <label for="user-search">Search username, name or tenant</label
          ><input id="user-search" class="form-control" formControlName="search" maxlength="200" />
        </div>
        <div class="col">
          <label for="user-status">Status</label
          ><select id="user-status" class="form-select" formControlName="status">
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Inactive</option>
            <option value="DELETED">Soft-deleted</option>
            <option value="ALL">All users</option>
          </select>
        </div>
        <div class="col-auto">
          <button class="btn btn-primary" type="submit" [disabled]="loading()">Search</button>
        </div>
      </form>
      @if (result(); as page) {
        <p>
          {{ page.total }} authorized users.
          {{
            page.superUser
              ? 'Existing super-user scope applies.'
              : 'Ordinary employees in your tenant only.'
          }}
        </p>
        <div class="table-responsive" tabindex="0" role="region" aria-label="User Management table">
          <table class="table">
            <caption>
              Authorized users and account actions
            </caption>
            <thead>
              <tr>
                <th scope="col">Username</th>
                <th scope="col">Tenant Name</th>
                <th scope="col">Tenant Type</th>
                <th scope="col">Role</th>
                <th scope="col">Status</th>
                <th scope="col">View User Details</th>
                <th scope="col">Soft Delete</th>
                <th scope="col">Hard Delete</th>
              </tr>
            </thead>
            <tbody>
              @for (user of page.users; track user.userId) {
                <tr [attr.data-user-id]="user.userId">
                  <td>{{ user.username }}</td>
                  <td>{{ user.tenantName || 'Not available' }}</td>
                  <td>{{ tenantType(user.tenantType) }}</td>
                  <td>
                    @for (role of user.roles; track role.roleId) {
                      <div>{{ role.name }}</div>
                    } @empty {
                      No assigned role
                    }
                  </td>
                  <td>
                    {{ status(user.status) }}
                    @if (user.deleted) {
                      <small class="status-note">Soft-deleted</small>
                    } @else if (user.status !== 'ACTIVE' && user.status !== 'INACTIVE') {
                      <small class="status-note">{{ user.status }}</small>
                    }
                  </td>
                  <td>
                    <a
                      class="btn btn-sm btn-outline-primary"
                      [routerLink]="['/user/users', user.userId]"
                      [queryParams]="tableParams()"
                      >View User Details</a
                    >
                  </td>
                  <td>
                    <button
                      type="button"
                      class="btn btn-sm btn-outline-warning"
                      [disabled]="!user.canSoftDelete || working()"
                      (click)="navigateDelete(user.userId, false)"
                    >
                      Soft Delete
                    </button>
                  </td>
                  <td>
                    <button
                      type="button"
                      class="btn btn-sm btn-outline-danger"
                      [disabled]="!user.canHardDelete || working()"
                      (click)="navigateDelete(user.userId, true)"
                    >
                      Hard Delete
                    </button>
                  </td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="8">No users match your search and status filter.</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <div class="pagination-controls">
          <button
            type="button"
            class="btn btn-outline-primary"
            [disabled]="pageIndex() === 0 || loading()"
            (click)="changePage(-1)"
          >
            Previous</button
          ><span>Page {{ pageIndex() + 1 }}</span
          ><button
            type="button"
            class="btn btn-outline-primary"
            [disabled]="(pageIndex() + 1) * page.size >= page.total || loading()"
            (click)="changePage(1)"
          >
            Next
          </button>
        </div>
        <button
          type="button"
          class="btn btn-outline-secondary"
          [disabled]="working()"
          (click)="loadJobs()"
        >
          Review recent cleanup jobs
        </button>
        <ul>
          @for (item of recentJobs(); track item.jobId) {
            <li>
              Job {{ item.jobId }} · Tenant {{ item.tenantId }} · User {{ item.userId }} ·
              {{ item.status }}
              <button
                type="button"
                class="btn btn-sm btn-outline-secondary"
                (click)="refreshJob(item.jobId)"
              >
                View cleanup status
              </button>
            </li>
          }
        </ul>
      }
    }

    @if (mode() === 'create' && createOptions(); as options) {
      <p>
        Create a user in your current tenant. Account setup follows its existing
        {{ options.loginPolicy === 'SSO' ? 'SSO enrollment' : 'password setup' }} flow.
      </p>
      <dl>
        <dt>Tenant Name</dt>
        <dd>{{ options.tenantName }}</dd>
        <dt>Tenant Type</dt>
        <dd>{{ tenantType(options.tenantType) }}</dd>
      </dl>
      <form class="create-form" [formGroup]="createForm" (ngSubmit)="createUser()">
        <label for="create-email">Username / email</label
        ><input
          id="create-email"
          type="email"
          class="form-control"
          formControlName="email"
          maxlength="320"
          autocomplete="off"
        />
        <label for="create-name">Name / display name</label
        ><input
          id="create-name"
          class="form-control"
          formControlName="displayName"
          maxlength="150"
          autocomplete="off"
        />
        @if (createForm.touched && createForm.invalid) {
          <p role="alert">Enter a valid email and a name of 1 to 150 characters.</p>
        }
        <p>
          The new account starts without assigned roles. Existing authorized role-assignment
          controls remain separate.
        </p>
        <div class="page-actions">
          <button type="submit" class="btn btn-primary" [disabled]="working()">
            {{ working() ? 'Creating…' : 'Create User' }}</button><a
            [routerLink]="['/user/users']"
            [queryParams]="tableParams()"
            (click)="back($event)"
            >Cancel</a
          >
        </div>
      </form>
    }

    @if (mode() === 'details' && details(); as detail) {
      <section aria-label="User details">
        <h2>Identity and profile</h2>
        <dl>
          <dt>User ID</dt>
          <dd>{{ detail.user.userId }}</dd>
          <dt>Username</dt>
          <dd>{{ detail.user.username }}</dd>
          <dt>Email</dt>
          <dd>{{ detail.email || 'Not available' }}</dd>
          <dt>Status</dt>
          <dd>
            {{ status(detail.user.status) }}
            @if (detail.user.deleted) {
              — Soft-deleted
            }
          </dd>
          <dt>Tenant Name</dt>
          <dd>{{ detail.user.tenantName }}</dd>
          <dt>Tenant Type</dt>
          <dd>{{ tenantType(detail.user.tenantType) }}</dd>
          <dt>Tenant ID</dt>
          <dd>{{ detail.user.tenantId }}</dd>
          <dt>Tenant status</dt>
          <dd>{{ detail.tenantStatus }}</dd>
          <dt>Membership status</dt>
          <dd>{{ detail.membershipStatus }}</dd>
          <dt>Created</dt>
          <dd>{{ (detail.user.createdAt | date: 'medium') || 'Not available' }}</dd>
          <dt>Last login</dt>
          <dd>{{ (detail.user.lastLogin | date: 'medium') || 'Not available' }}</dd>
          <dt>Email verified</dt>
          <dd>{{ (detail.emailVerifiedAt | date: 'medium') || 'Not available' }}</dd>
          <dt>Updated</dt>
          <dd>{{ (detail.updatedAt | date: 'medium') || 'Not available' }}</dd>
          @if (detail.user.deleted) {
            <dt>Soft-deleted</dt>
            <dd>{{ (detail.user.deletedAt | date: 'medium') || 'Not available' }}</dd>
          }
        </dl>
        <form class="profile-form" [formGroup]="profileForm" (ngSubmit)="saveProfile()">
          <label for="managed-username">Username (read-only)</label
          ><input
            id="managed-username"
            class="form-control"
            [value]="detail.user.username"
            readonly
          />
          <label for="managed-display-name">Name / display name</label
          ><input
            id="managed-display-name"
            class="form-control"
            formControlName="displayName"
            maxlength="150"
            [readOnly]="!detail.user.canEdit || working()"
          />
          @if (profileForm.touched && profileForm.invalid) {
            <p role="alert">Enter a name of 1 to 150 characters.</p>
          }
          <div class="page-actions">
            @if (detail.user.canEdit) {
              <button
                type="submit"
                class="btn btn-primary"
                [disabled]="working() || !profileForm.dirty"
              >
                {{ working() ? 'Saving…' : 'Save User Details' }}
              </button>
            }
            <button
              type="button"
              class="btn btn-outline-secondary"
              [disabled]="working()"
              (click)="requestReturn()"
            >
              Cancel / return to table
            </button>
          </div>
        </form>
        <h2>Assigned roles</h2>
        @for (role of detail.user.roles; track role.roleId) {
          <div class="role-summary">
            <strong>{{ role.name }}</strong> ({{ role.status }})
            <div>{{ role.description || 'No description available' }}</div>
            <div>{{ role.permissions.join(', ') || 'No catalog grants' }}</div>
          </div>
        } @empty {
          <p>No assigned roles.</p>
        }
        @if (detail.user.canAssignRoles) {
          <fieldset [disabled]="working()">
            <legend>Change assigned roles</legend>
            @if (roleLoading()) {
              <p role="status">Loading assignable roles…</p>
            }
            @if (roleError()) {
              <p role="alert">{{ roleError() }}</p>
            }
            @for (role of availableRoles(); track role.roleId) {
              <label
                ><input
                  type="checkbox"
                  [checked]="selectedRoles().has(role.roleId)"
                  (change)="toggleRole(role.roleId, $event)"
                />{{ role.name }} — {{ role.description || 'No description'
                }}<span class="secondary">
                  {{ role.permissions.join(', ') || 'No permissions' }}</span
                ></label
              >
            }
            <button
              type="button"
              class="btn btn-outline-primary"
              [disabled]="roleLoading() || !!roleError() || !rolesDirty()"
              (click)="saveRoles()"
            >
              Save assigned roles
            </button>
          </fieldset>
        }
        <h2>Effective permissions</h2>
        <p>Visibility does not grant permission-edit authority.</p>
        <ul>
          @for (permission of detail.effectivePermissions; track permission.code) {
            <li>
              {{ permission.name }} ({{ permission.code }}) — role-derived:
              {{ permission.sourceRoles.join(', ') || 'No effective source' }}
            </li>
          } @empty {
            <li>
              No currently effective permissions. Inactive accounts cannot authorize requests.
            </li>
          }
        </ul>
        @if (!detail.directGrantsSupported) {
          <p class="secondary">Direct user grants are not part of this application's model.</p>
        }
        @if (detail.user.canActivate) {
          <section aria-label="Activate selected account">
            <h2>Activate selected account</h2>
            <p>
              Activate only {{ detail.user.username }} in {{ detail.user.tenantName }} (tenant
              {{ detail.user.tenantId }}). Other inactive accounts remain inactive. Fresh login is
              required.
            </p>
            <form [formGroup]="activationForm" (ngSubmit)="activate()">
              <label
                ><input type="checkbox" formControlName="resumeTenant" /> Explicitly resume an
                inactive/suspended tenant if necessary; do not activate other users.</label
              ><button type="submit" class="btn btn-success" [disabled]="working()">
                {{ working() ? 'Activating…' : 'Activate selected account' }}
              </button>
            </form>
          </section>
        }
        <div class="page-actions">
          <button
            type="button"
            class="btn btn-outline-warning"
            [disabled]="!detail.user.canSoftDelete || working()"
            (click)="navigateDelete(detail.user.userId, false)"
          >
            Soft Delete</button
          ><button
            type="button"
            class="btn btn-outline-danger"
            [disabled]="!detail.user.canHardDelete || working()"
            (click)="navigateDelete(detail.user.userId, true)"
          >
            Hard Delete
          </button>
          @if (detail.user.pendingJobId) {
            <button
              type="button"
              class="btn btn-outline-secondary"
              (click)="refreshJob(detail.user.pendingJobId)"
            >
              Review cleanup job {{ detail.user.pendingJobId }}
            </button>
          }
        </div>
      </section>
    }

    @if (mode() === 'delete' && deletePreview(); as preview) {
      <section class="confirmation-page" aria-label="Delete confirmation">
        <div class="danger">
          <p>
            <strong>{{ preview.tenantName }} — tenant {{ preview.tenantId }}</strong>
          </p>
          <p>Target user ID {{ preview.userId }}. Affected users: {{ preview.affectedUsers }}.</p>
          <p>{{ preview.explanation }}</p>
          <p>
            {{
              preview.preservesFiles
                ? 'Files and folders are preserved.'
                : 'This permanently removes exclusively owned data/files according to the validated scope.'
            }}
          </p>
          <p>
            Required confirmation: <strong>{{ preview.confirmation }}</strong>
          </p>
        </div>
        <form [formGroup]="confirmationForm" (ngSubmit)="confirmDelete()">
          <label for="delete-confirmation">Type the exact confirmation phrase</label
          ><input
            id="delete-confirmation"
            class="form-control"
            formControlName="confirmation"
            autocomplete="off"
            [readOnly]="working() || submitted()"
          />
          <div class="page-actions">
            <button
              type="submit"
              class="btn btn-danger"
              [disabled]="
                working() ||
                submitted() ||
                confirmationForm.controls.confirmation.value !== preview.confirmation
              "
            >
              {{
                working()
                  ? 'Submitting…'
                  : preview.permanent
                    ? 'Confirm Hard Delete'
                    : 'Confirm Soft Delete'
              }}</button><button
              type="button"
              class="btn btn-outline-secondary"
              [disabled]="working()"
              (click)="requestReturn()"
            >
              {{ submitted() ? 'Return to table' : 'Cancel' }}
            </button>
          </div>
        </form>
      </section>
    }
    @if (!loading() && !error() && mode() === 'details' && !details()) {
      <p>No authorized user details available.</p>
    }
    @if (job(); as cleanup) {
      <section aria-label="Deletion cleanup status" class="my-3">
        <h2>Deletion job {{ cleanup.jobId }}</h2>
        <p>Database/account removal: {{ cleanup.databasePurged ? 'completed' : 'pending' }}.</p>
        <p role="status">
          {{
            cleanup.status === 'COMPLETE'
              ? 'Deletion and required cleanup completed.'
              : 'Deletion is not complete. Cleanup status: ' + cleanup.status
          }}
        </p>
        <p>Tenant {{ cleanup.tenantId }} · User {{ cleanup.userId }} · {{ cleanup.scope }}</p>
        @if (cleanup.error) {
          <p role="alert">{{ cleanup.error }}</p>
        }
        <div class="page-actions">
          <button
            type="button"
            class="btn btn-outline-primary"
            [disabled]="working()"
            (click)="refreshJob(cleanup.jobId)"
          >
            Refresh cleanup status
          </button>
          @if (cleanup.status === 'FAILED' && access.capabilities()?.superUser) {
            <button
              type="button"
              class="btn btn-outline-danger"
              [disabled]="working()"
              (click)="retryJob(cleanup.jobId)"
            >
              Retry validated cleanup
            </button>
          }
        </div>
      </section>
    }
  `,
})
export class UserManagementComponent {
  private readonly api = inject(UserManagementApiService);
  private readonly session = inject(AuthSessionService);
  private readonly profiles = inject(CurrentProfileService);
  readonly access = inject(NavigationAccessService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly fb = inject(FormBuilder).nonNullable;
  readonly filters = this.fb.group({ search: [''], status: ['ACTIVE'] });
  readonly profileForm = this.fb.group({
    displayName: ['', [Validators.required, Validators.maxLength(150)]],
  });
  readonly createForm = this.fb.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
    displayName: ['', [Validators.required, Validators.maxLength(150)]],
  });
  readonly confirmationForm = this.fb.group({ confirmation: [''] });
  readonly activationForm = this.fb.group({ resumeTenant: [false] });
  readonly mode = signal<PageMode>('list');
  readonly hardDelete = signal(false);
  readonly pageIndex = signal(0);
  readonly result = signal<UserPage | null>(null);
  readonly details = signal<UserDetails | null>(null);
  readonly createOptions = signal<CreateUserOptions | null>(null);
  readonly loading = signal(false);
  readonly working = signal(false);
  readonly error = signal<string | null>(null);
  readonly message = signal<string | null>(null);
  readonly availableRoles = signal<UserRole[]>([]);
  readonly selectedRoles = signal(new Set<number>());
  readonly roleLoading = signal(false);
  readonly roleError = signal<string | null>(null);
  readonly deletePreview = signal<DeletionPreview | null>(null);
  readonly discardPrompt = signal(false);
  readonly submitted = signal(false);
  readonly job = signal<DeletionJob | null>(null);
  readonly recentJobs = signal<DeletionJob[]>([]);
  private epoch = 0;
  private targetId = 0;
  private allowLeave = false;
  constructor() {
    void this.access.ensure();
    combineLatest([this.route.data, this.route.paramMap, this.route.queryParamMap])
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe(([data, params, query]) => {
        this.result.set(null);
        this.details.set(null);
        this.createOptions.set(null);
        this.deletePreview.set(null);
        this.job.set(null);
        this.recentJobs.set([]);
        this.availableRoles.set([]);
        this.selectedRoles.set(new Set());
        this.roleError.set(null);
        this.roleLoading.set(false);
        this.profileForm.reset();
        this.activationForm.reset();
        this.confirmationForm.reset();
        this.submitted.set(false);
        this.allowLeave = false;
        this.mode.set((data['userPage'] as PageMode) || 'list');
        this.targetId = Number(params.get('id'));
        const status = query.get('status') || 'ACTIVE';
        this.filters.reset({
          search: query.get('search') || '',
          status: ['ACTIVE', 'INACTIVE', 'DELETED', 'ALL'].includes(status) ? status : 'ACTIVE',
        });
        this.pageIndex.set(Math.max(0, Number.parseInt(query.get('page') || '0', 10) || 0));
        this.hardDelete.set(query.get('hard') === 'true');
        this.message.set(
          query.get('notice') === 'created'
            ? 'User created. Account setup instructions were sent.'
            : query.get('notice') === 'deleted'
              ? 'Selected scope soft-deleted. Data, files and folders were preserved.'
              : null,
        );
        this.error.set(null);
        this.discardPrompt.set(false);
        void this.load();
      });
  }
  private value<T>(request: Observable<T>) {
    return firstValueFrom(request.pipe(takeUntilDestroyed(this.destroy)));
  }
  tableParams() {
    const filter = this.filters.getRawValue();
    return { search: filter.search, status: filter.status, page: this.pageIndex() };
  }
  tenantType(type: string) {
    return type === 'INDIVIDUAL'
      ? 'Individual'
      : type === 'ORGANIZATION'
        ? 'Organisation'
        : 'Not available';
  }
  status(value: string) {
    return value === 'ACTIVE' ? 'Active' : 'Inactive';
  }
  async load() {
    const epoch = ++this.epoch;
    this.loading.set(true);
    this.error.set(null);
    try {
      if (this.mode() === 'list') {
        const f = this.filters.getRawValue();
        const data = await this.value(this.api.list(f.search, f.status, this.pageIndex(), 20));
        if (epoch !== this.epoch) return;
        if (!data.users.length && data.total > 0 && this.pageIndex() > 0) {
          await this.router.navigate(['/user/users'], {
            queryParams: { ...this.tableParams(), page: Math.floor((data.total - 1) / 20) },
            replaceUrl: true,
          });
          return;
        }
        this.result.set(data);
      } else if (this.mode() === 'create') {
        const options = await this.value(this.api.createOptions());
        if (epoch === this.epoch) this.createOptions.set(options);
      } else {
        if (!Number.isSafeInteger(this.targetId) || this.targetId <= 0)
          throw new Error('Choose a valid user.');
        if (this.mode() === 'delete') {
          const preview = await this.value(this.api.preview(this.targetId, this.hardDelete()));
          if (epoch === this.epoch) {
            this.deletePreview.set(preview);
            this.confirmationForm.reset();
          }
        } else {
          const detail = await this.value(this.api.details(this.targetId));
          if (epoch !== this.epoch) return;
          this.setDetails(detail);
          if (detail.user.canAssignRoles) void this.loadRoles(detail.user.userId, epoch);
        }
      }
    } catch (error) {
      if (epoch === this.epoch) {
        this.result.set(null);
        this.error.set(await apiErrorMessage(error));
      }
    } finally {
      if (epoch === this.epoch) this.loading.set(false);
    }
  }
  search() {
    void this.router.navigate(['/user/users'], { queryParams: { ...this.tableParams(), page: 0 } });
  }
  changePage(delta: number) {
    void this.router.navigate(['/user/users'], {
      queryParams: { ...this.tableParams(), page: Math.max(0, this.pageIndex() + delta) },
    });
  }
  navigateDelete(id: number, hard: boolean) {
    void this.router.navigate(['/user/users', id, 'delete'], {
      queryParams: { ...this.tableParams(), hard },
    });
  }
  private setDetails(detail: UserDetails) {
    this.details.set(detail);
    this.profileForm.reset({ displayName: detail.user.displayName });
    this.selectedRoles.set(new Set(detail.user.roles.map((role) => role.roleId)));
  }
  private async loadRoles(id: number, epoch: number) {
    this.roleLoading.set(true);
    try {
      const roles = await this.value(this.api.roles(id));
      if (epoch === this.epoch) this.availableRoles.set(roles);
    } catch (error) {
      if (epoch === this.epoch) this.roleError.set(await apiErrorMessage(error));
    } finally {
      if (epoch === this.epoch) this.roleLoading.set(false);
    }
  }
  rolesDirty() {
    const original = this.details()?.user.roles.map((role) => role.roleId) || [];
    return (
      original.length !== this.selectedRoles().size ||
      original.some((id) => !this.selectedRoles().has(id))
    );
  }
  canLeave() {
    if (!this.session.user()) return true;
    if (this.allowLeave) return true;
    if (this.working()) return false;
    if (
      this.profileForm.dirty ||
      this.rolesDirty() ||
      (this.mode() === 'create' && this.createForm.dirty)
    ) {
      this.discardPrompt.set(true);
      return false;
    }
    return true;
  }
  @HostListener('window:beforeunload', ['$event']) beforeUnload(event: BeforeUnloadEvent) {
    if (!this.session.user()) return;
    if (
      this.profileForm.dirty ||
      this.rolesDirty() ||
      (this.mode() === 'create' && this.createForm.dirty)
    )
      event.preventDefault();
  }
  back(event: Event) {
    event.preventDefault();
    this.requestReturn();
  }
  requestReturn() {
    if (this.canLeave())
      void this.router.navigate([this.access.capabilities()?.canViewUsers ? '/user/users' : '/'], {
        queryParams: this.tableParams(),
      });
  }
  discardAndReturn() {
    if (this.working()) return;
    this.allowLeave = true;
    void this.router.navigate([this.access.capabilities()?.canViewUsers ? '/user/users' : '/'], {
      queryParams: this.tableParams(),
    });
  }
  async saveProfile() {
    const detail = this.details();
    if (!detail?.user.canEdit || this.working()) return;
    this.profileForm.markAllAsTouched();
    if (this.profileForm.invalid || !this.profileForm.controls.displayName.value.trim()) return;
    this.working.set(true);
    this.error.set(null);
    try {
      const saved = await this.value(
        this.api.edit(
          detail.user.userId,
          this.profileForm.controls.displayName.value,
          detail.user.version,
        ),
      );
      const pending = this.rolesDirty() ? new Set(this.selectedRoles()) : null;
      this.setDetails(saved);
      if (pending) this.selectedRoles.set(pending);
      this.message.set('User details saved.');
      if (String(saved.user.userId) === String(this.session.user()?.userId))
        await this.profiles.refresh();
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
    } finally {
      this.working.set(false);
    }
  }
  toggleRole(id: number, event: Event) {
    const roles = new Set(this.selectedRoles());
    (event.target as HTMLInputElement).checked ? roles.add(id) : roles.delete(id);
    this.selectedRoles.set(roles);
  }
  async saveRoles() {
    const detail = this.details();
    if (!detail?.user.canAssignRoles || this.working()) return;
    if (this.profileForm.dirty) {
      this.error.set('Save or cancel your profile edits before changing roles.');
      return;
    }
    this.working.set(true);
    this.error.set(null);
    try {
      this.setDetails(
        await this.value(
          this.api.assign(detail.user.userId, [...this.selectedRoles()], detail.user.version),
        ),
      );
      this.message.set('Assigned roles saved. The account must sign in again.');
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
    } finally {
      this.working.set(false);
    }
  }
  async activate() {
    const detail = this.details();
    if (!detail?.user.canActivate || this.working()) return;
    this.working.set(true);
    this.error.set(null);
    try {
      this.setDetails(
        await this.value(
          this.api.activate(
            detail.user.userId,
            detail.user.version,
            this.activationForm.controls.resumeTenant.value,
          ),
        ),
      );
      this.message.set('Only the selected account was activated. A fresh login is required.');
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
    } finally {
      this.working.set(false);
    }
  }
  async createUser() {
    if (!this.createOptions() || !this.access.capabilities()?.canCreateUsers || this.working())
      return;
    this.createForm.markAllAsTouched();
    if (this.createForm.invalid || !this.createForm.controls.displayName.value.trim()) return;
    this.working.set(true);
    this.error.set(null);
    try {
      const input = this.createForm.getRawValue();
      const created = await this.value(
        this.api.create(input.email.trim(), input.displayName.trim()),
      );
      this.createForm.reset();
      if (this.access.capabilities()?.canViewUsers) {
        this.allowLeave = true;
        await this.router.navigate(['/user/users'], {
          queryParams: { ...this.tableParams(), notice: 'created', status: 'ALL' },
        });
      } else {
        this.message.set(created.message);
      }
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
    } finally {
      this.working.set(false);
    }
  }
  async confirmDelete() {
    const preview = this.deletePreview();
    if (
      !preview ||
      this.working() ||
      this.submitted() ||
      this.confirmationForm.controls.confirmation.value !== preview.confirmation
    )
      return;
    this.working.set(true);
    this.error.set(null);
    try {
      if (preview.permanent) {
        this.job.set(
          await this.value(
            this.api.hard(
              preview.userId,
              preview,
              this.confirmationForm.controls.confirmation.value,
            ),
          ),
        );
        this.submitted.set(true);
        this.message.set(
          'Deletion accepted. It is not complete until the cleanup job reports COMPLETE.',
        );
      } else {
        await this.value(
          this.api.soft(preview.userId, preview, this.confirmationForm.controls.confirmation.value),
        );
        this.allowLeave = true;
        await this.router.navigate(['/user/users'], {
          queryParams: { ...this.tableParams(), notice: 'deleted' },
        });
      }
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
    } finally {
      this.working.set(false);
    }
  }
  async refreshJob(id: number) {
    if (this.working()) return;
    this.working.set(true);
    this.error.set(null);
    try {
      this.job.set(await this.value(this.api.job(id)));
      if (this.mode() === 'list') await this.load();
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
    } finally {
      this.working.set(false);
    }
  }
  async loadJobs() {
    if (this.working()) return;
    this.working.set(true);
    try {
      this.recentJobs.set(await this.value(this.api.jobs()));
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
    } finally {
      this.working.set(false);
    }
  }
  async retryJob(id: number) {
    if (this.working()) return;
    this.working.set(true);
    try {
      this.job.set(await this.value(this.api.retry(id)));
      this.message.set('Cleanup retry queued; completion is still pending.');
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
    } finally {
      this.working.set(false);
    }
  }
}
