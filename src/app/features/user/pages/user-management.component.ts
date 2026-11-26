import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
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

/** Routed management pages retain list context; cleanup details use an accessible modal. */
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
      .management-heading {
        display: flex;
        justify-content: space-between;
        align-items: start;
        gap: 1rem;
        flex-wrap: wrap;
        margin-bottom: 1.25rem;
      }
      .heading-actions {
        display: flex;
        gap: 0.65rem;
        flex-wrap: wrap;
      }
      .delete-result {
        position: relative;
        padding: clamp(1rem, 3vw, 2rem);
        border: 1px solid;
        border-radius: 0.8rem;
        max-width: 60rem;
      }
      .delete-result p {
        font-size: 1.05rem;
        line-height: 1.6;
        white-space: pre-wrap;
        overflow-wrap: anywhere;
      }
      .delete-result .btn {
        margin: 0.5rem 0.65rem 0.25rem 0;
      }
      .result-success {
        background: #ecf9f0;
        border-color: #8ac39d;
        color: #125c2d;
      }
      .result-failure {
        background: #fff0ef;
        border-color: #df9d97;
        color: #9a1c20;
      }
      .result-pending {
        background: #fff9e7;
        border-color: #d8bd6e;
        color: #6c5100;
      }
      .result-icon {
        display: block;
        font-size: 2.5rem;
        font-weight: 800;
        line-height: 1.2;
        margin-bottom: 0.7rem;
      }
      .result-close {
        margin-left: auto;
        float: right;
        border: 1px solid var(--line);
        border-radius: 0.35rem;
        padding: 0.35rem 0.65rem;
        background: #fff;
        color: var(--ink);
        min-height: 40px;
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
      .cleanup-modal {
        background: #fff;
        color: var(--ink);
        border-radius: 0.8rem;
        padding: clamp(1rem, 3vw, 2rem);
        width: min(98vw, 90rem);
        max-height: 90dvh;
        overflow: auto;
        box-shadow: 0 1rem 4rem #10243f55;
      }
      .cleanup-modal table {
        min-width: 65rem;
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
    @if (deleteResult(); as outcome) {
      <section
        class="delete-result"
        [class.result-success]="outcome.kind === 'success'"
        [class.result-failure]="outcome.kind === 'failure'"
        [class.result-pending]="outcome.kind === 'pending'"
        aria-label="Delete result"
      >
        <button
          type="button"
          class="result-close"
          aria-label="Close delete result and return"
          (click)="returnFromResult()"
        >
          &#10005;
        </button>
        <span class="result-icon" aria-hidden="true">{{
          outcome.kind === 'success' ? '✓' : outcome.kind === 'failure' ? '✕' : '◷'
        }}</span>
        <h1>
          {{
            outcome.kind === 'success'
              ? 'Deletion successful'
              : outcome.kind === 'failure'
                ? 'Deletion failed'
                : 'Deletion pending'
          }}
        </h1>
        <p [attr.role]="outcome.kind === 'failure' ? 'alert' : 'status'">{{ outcome.message }}</p>
        @if (job()) {
          <button type="button" class="btn btn-outline-primary" (click)="loadJobs()">
            Review Recent Cleanup
          </button>
        }
        <button type="button" class="btn btn-primary" (click)="returnFromResult()">Return</button>
      </section>
    } @else {
      <div class="management-heading">
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
        <div class="heading-actions">
          @if (mode() === 'list' && access.capabilities()?.canCreateUsers) {
            <a
              class="btn btn-primary"
              [routerLink]="['/user/users/new']"
              [queryParams]="tableParams()"
              >Add User</a
            >
          }
          <a class="btn btn-outline-primary" routerLink="/">Back to Library</a>
        </div>
      </div>
      <nav class="page-links" aria-label="User Management pages">
        @if (mode() !== 'list' && access.capabilities()?.canViewUsers) {
          <a [routerLink]="['/user/users']" [queryParams]="tableParams()" (click)="back($event)"
            >Back to User Management</a
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
            ><input
              id="user-search"
              class="form-control"
              formControlName="search"
              maxlength="200"
            />
          </div>
          <div class="col">
            <label for="user-status">Status</label
            ><select id="user-status" class="form-select" formControlName="status">
              <option value="ACTIVE">Active</option>
              <option value="INACTIVE">Inactive</option>
              <option value="ALL">All Users</option>
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
          <div
            class="table-responsive"
            tabindex="0"
            role="region"
            aria-label="User Management table"
          >
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
            Review Recent Cleanup
          </button>
        }
      }

      @if (mode() === 'create' && createOptions(); as options) {
        <p>
          Add a user to the selected tenant. Account setup follows its existing
          {{ options.loginPolicy === 'SSO' ? 'SSO enrollment' : 'password setup' }} flow.
        </p>
        <label for="create-tenant">Tenant Name</label>
        <select
          id="create-tenant"
          class="form-select"
          [value]="options.tenantId"
          [disabled]="!access.capabilities()?.superUser || working()"
          (change)="selectCreateTenant($event)"
        >
          @for (tenant of createTenants(); track tenant.tenantId) {
            <option [value]="tenant.tenantId">
              {{ tenant.tenantName }} ({{ tenantType(tenant.tenantType) }})
            </option>
          }
        </select>
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
                (click)="openJob(detail.user.pendingJobId)"
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
    }
    @if (cleanupOpen()) {
      <div class="modal-shade" (click)="cleanupOutside($event)">
        <section
          class="cleanup-modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="cleanup-title"
          tabindex="-1"
        >
          <header class="management-heading">
            <h2 id="cleanup-title">Recent Cleanup Jobs</h2>
            <button
              type="button"
              class="result-close"
              aria-label="Close recent cleanup"
              (click)="closeCleanup()"
            >
              &#10005;
            </button>
          </header>
          @if (cleanupLoading()) {
            <p role="status">Refreshing cleanup information…</p>
          }
          @if (cleanupError()) {
            <p role="alert" class="auth-error">{{ cleanupError() }}</p>
          }
          <div
            class="table-responsive"
            tabindex="0"
            role="region"
            aria-label="Recent cleanup jobs table"
          >
            <table class="table">
              <thead>
                <tr>
                  <th>Job ID</th>
                  <th>Tenant ID</th>
                  <th>User ID</th>
                  <th>Scope</th>
                  <th>Status</th>
                  <th>Database Removal</th>
                  <th>Attempts</th>
                  <th>Error / Details</th>
                  <th>Updated</th>
                </tr>
              </thead>
              <tbody>
                @for (item of recentJobs(); track item.jobId) {
                  <tr>
                    <td>{{ item.jobId }}</td>
                    <td>{{ item.tenantId }}</td>
                    <td>{{ item.userId }}</td>
                    <td>{{ item.scope }}</td>
                    <td>{{ item.status }}</td>
                    <td>{{ item.databasePurged ? 'Completed' : 'Pending' }}</td>
                    <td>{{ item.attempts }}</td>
                    <td>
                      {{ item.error || 'None' }}
                      @if (item.status === 'FAILED' && access.capabilities()?.superUser) {
                        <button
                          class="btn btn-sm btn-outline-danger"
                          (click)="retryJob(item.jobId)"
                          [disabled]="working()"
                        >
                          Retry validated cleanup
                        </button>
                      }
                    </td>
                    <td>{{ (item.updatedAt | date: 'medium') || 'Not available' }}</td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="9">No recent cleanup jobs.</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <div class="page-actions">
            <button
              class="btn btn-outline-primary"
              type="button"
              (click)="refreshCleanup()"
              [disabled]="cleanupLoading()"
            >
              Refresh Cleanup Status</button
            ><button type="button" class="btn btn-primary" (click)="closeCleanup()">Close</button>
          </div>
        </section>
      </div>
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
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
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
  readonly createTenants = signal<CreateUserOptions[]>([]);
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
  readonly cleanupOpen = signal(false);
  readonly cleanupLoading = signal(false);
  readonly cleanupError = signal<string | null>(null);
  readonly deleteResult = signal<{
    kind: 'success' | 'failure' | 'pending';
    message: string;
  } | null>(null);
  private cleanupReturnFocus: HTMLElement | null = null;
  private cleanupEpoch = 0;
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
        this.createTenants.set([]);
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
        this.deleteResult.set(null);
        this.cleanupOpen.set(false);
        this.cleanupLoading.set(false);
        this.cleanupError.set(null);
        this.cleanupEpoch++;
        this.allowLeave = false;
        this.mode.set((data['userPage'] as PageMode) || 'list');
        this.targetId = Number(params.get('id'));
        const status = query.get('status') || 'ACTIVE';
        this.filters.reset({
          search: query.get('search') || '',
          status:
            status === 'DELETED'
              ? 'ALL'
              : ['ACTIVE', 'INACTIVE', 'ALL'].includes(status)
                ? status
                : 'ACTIVE',
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
        const offered = await this.value(this.api.createTenants());
        const tenants = this.access.capabilities()?.superUser
          ? offered
          : offered.filter(
              (tenant) => tenant.tenantId === Number(this.access.capabilities()?.tenantId),
            );
        const current =
          tenants.find(
            (tenant) => tenant.tenantId === Number(this.access.capabilities()?.tenantId),
          ) || tenants[0];
        if (epoch === this.epoch) this.createTenants.set(tenants);
        const options = await this.value(this.api.createOptions(current?.tenantId));
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
      queryParams: {
        ...this.tableParams(),
        hard,
        from: this.mode() === 'details' ? 'details' : 'list',
      },
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
        this.api.create(
          input.email.trim(),
          input.displayName.trim(),
          this.createOptions()!.tenantId,
        ),
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
      this.deleteResult() ||
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
        this.updateDeleteResult(this.job()!);
      } else {
        await this.value(
          this.api.soft(preview.userId, preview, this.confirmationForm.controls.confirmation.value),
        );
        this.submitted.set(true);
        this.deleteResult.set({
          kind: 'success',
          message:
            'Selected scope soft-deleted successfully. Data, files and folders were preserved.',
        });
      }
    } catch (error) {
      const message = await apiErrorMessage(error);
      this.error.set(message);
      this.deleteResult.set({ kind: 'failure', message });
    } finally {
      this.working.set(false);
    }
  }
  async refreshJob(id: number) {
    if (this.working()) return;
    this.working.set(true);
    this.error.set(null);
    try {
      const job = await this.value(this.api.job(id));
      this.job.set(job);
      this.recentJobs.update((jobs) =>
        jobs.some((item) => item.jobId === id)
          ? jobs.map((item) => (item.jobId === id ? job : item))
          : [job, ...jobs],
      );
      if (this.deleteResult()) this.updateDeleteResult(job);
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
    } finally {
      this.working.set(false);
    }
  }
  async openJob(id: number) {
    await this.refreshJob(id);
    await this.loadJobs();
  }
  async loadJobs() {
    this.cleanupReturnFocus =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.cleanupOpen.set(true);
    setTimeout(() =>
      this.element.nativeElement.querySelector<HTMLElement>('[role="dialog"]')?.focus(),
    );
    await this.refreshCleanup();
  }
  async refreshCleanup() {
    if (this.cleanupLoading()) return;
    const epoch = ++this.cleanupEpoch;
    this.cleanupLoading.set(true);
    this.cleanupError.set(null);
    try {
      const jobs = await this.value(this.api.jobs());
      if (epoch !== this.cleanupEpoch) return;
      const selected = this.job();
      let latest = selected ? jobs.find((item) => item.jobId === selected.jobId) : undefined;
      if (selected && !latest) latest = await this.value(this.api.job(selected.jobId));
      if (epoch !== this.cleanupEpoch) return;
      this.recentJobs.set(
        latest && !jobs.some((item) => item.jobId === latest!.jobId) ? [latest, ...jobs] : jobs,
      );
      if (latest) {
        this.job.set(latest);
        if (this.deleteResult()) this.updateDeleteResult(latest);
      }
    } catch (error) {
      if (epoch === this.cleanupEpoch) this.cleanupError.set(await apiErrorMessage(error));
    } finally {
      if (epoch === this.cleanupEpoch) this.cleanupLoading.set(false);
    }
  }
  async retryJob(id: number) {
    if (this.working()) return;
    this.working.set(true);
    try {
      this.job.set(await this.value(this.api.retry(id)));
      if (this.deleteResult()) this.updateDeleteResult(this.job()!);
      await this.refreshCleanup();
    } catch (error) {
      const message = await apiErrorMessage(error);
      if (this.cleanupOpen()) this.cleanupError.set(message);
      else this.error.set(message);
    } finally {
      this.working.set(false);
    }
  }
  async selectCreateTenant(event: Event) {
    if (!this.access.capabilities()?.superUser || this.working()) return;
    const id = Number((event.target as HTMLSelectElement).value);
    if (!this.createTenants().some((tenant) => tenant.tenantId === id)) return;
    this.working.set(true);
    this.error.set(null);
    try {
      this.createOptions.set(await this.value(this.api.createOptions(id)));
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
      (event.target as HTMLSelectElement).value = String(this.createOptions()?.tenantId || '');
    } finally {
      this.working.set(false);
    }
  }
  returnFromResult() {
    this.allowLeave = true;
    void this.router.navigate(
      this.route.snapshot.queryParamMap.get('from') === 'details'
        ? ['/user/users', this.targetId]
        : ['/user/users'],
      { queryParams: this.tableParams() },
    );
  }
  private updateDeleteResult(job: DeletionJob) {
    this.deleteResult.set(
      job.status === 'COMPLETE'
        ? { kind: 'success', message: 'Deletion and required cleanup completed successfully.' }
        : job.status === 'FAILED'
          ? {
              kind: 'failure',
              message: job.error || 'Deletion could not complete. Review the cleanup job details.',
            }
          : {
              kind: 'pending',
              message:
                'Deletion accepted. Deletion is not complete. Cleanup status: ' + job.status + '.',
            },
    );
  }
  closeCleanup() {
    this.cleanupEpoch++;
    this.cleanupOpen.set(false);
    this.cleanupLoading.set(false);
    this.cleanupReturnFocus?.focus();
  }
  cleanupOutside(event: MouseEvent) {
    if (event.target === event.currentTarget) this.closeCleanup();
  }
  @HostListener('keydown', ['$event']) cleanupKeys(event: KeyboardEvent) {
    if (!this.cleanupOpen()) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      this.closeCleanup();
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
