import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom, forkJoin } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  RoleApiService,
  ManagedRole,
  RoleCatalog,
  RolePermission,
  RoleWrite,
} from '../data-access/role-api.service';
import { AuthSessionService } from '../services/auth-session.service';
import { apiErrorMessage } from '../../../shared/utils/api-error';

@Component({
  selector: 'app-role-management',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    .management-heading {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1rem;
      flex-wrap: wrap;
      margin-bottom: 1.25rem;
    }
    .management-heading h1 {
      margin: 0;
    }
    .role-actions {
      display: flex;
      gap: 0.65rem;
      align-items: center;
      flex-wrap: wrap;
    }
    .role-list {
      list-style: none;
      padding: 0;
      display: grid;
      gap: 0.85rem;
      margin-block: 1rem;
    }
    .role-list li {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      flex-wrap: wrap;
      padding: 0.9rem;
      border: 1px solid var(--line);
      border-radius: 0.6rem;
    }
    .role-identity {
      flex: 1 1 12rem;
      overflow-wrap: anywhere;
    }
    .role-note {
      color: var(--muted);
      font-size: 0.85rem;
    }
    .role-form {
      margin-top: 1.25rem;
    }
    .role-form fieldset {
      display: grid;
      gap: 0.7rem;
    }
    .role-form code {
      overflow-wrap: anywhere;
    }
  `,
  template: `
    <div class="management-heading">
      <h1>Role Management</h1>
      <div class="role-actions" aria-label="Role Management actions">
        <button
          class="btn btn-outline-primary"
          type="button"
          [disabled]="busy() || saving()"
          (click)="viewExistingRoles()"
        >
          View Existing Roles
        </button>
        @if (catalog()?.canCreate) {
          <button
            class="btn btn-primary"
            type="button"
            [disabled]="busy() || saving()"
            (click)="newRole()"
          >
            New Role
          </button>
        }
        <a class="btn btn-outline-primary" routerLink="/">Back to Library</a>
      </div>
    </div>
    <p>Manage roles in your authorized tenant. System roles have additional protection.</p>
    @if (error()) {
      <p role="alert" class="auth-error">{{ error() }}</p>
    }
    @if (busy()) {
      <p role="status">Loading roles…</p>
    }
    @if (organizations().length && viewingRoles()) {
      <div class="field">
        <label for="role-organization">Tenant Name</label>
        <select
          id="role-organization"
          class="form-select"
          [value]="tenantId()"
          [disabled]="busy() || saving() || !catalog()?.superUser"
          (change)="selectOrganization($event)"
        >
          @for (organization of organizations(); track organization.tenantId) {
            <option [value]="organization.tenantId">
              {{ organization.name }} ({{ organization.tenantId }})
            </option>
          }
        </select>
      </div>
    }
    @if (catalog(); as permissions) {
      @if (viewingRoles()) {
        <section aria-label="Tenant roles">
          <h2>Roles</h2>
          <ul class="role-list">
            @for (role of roles(); track role.roleId) {
              <li>
                <div class="role-identity">
                  <strong>{{ role.name }}</strong>
                  {{ role.systemRole ? '(' + role.systemRole + ')' : '' }}
                </div>
                @if (role.systemRole !== 'SUPER_USER') {
                  <div class="role-actions">
                    <button
                      type="button"
                      class="btn btn-outline-primary"
                      [disabled]="!role.editable || busy() || saving()"
                      (click)="edit(role)"
                    >
                      Edit {{ role.name }}
                    </button>
                    @if (permissions.canCreate) {
                      <button
                        type="button"
                        class="btn btn-outline-primary"
                        [disabled]="busy() || saving() || !canCopy(role)"
                        (click)="copyRole(role)"
                      >
                        Copy Role <span class="visually-hidden">{{ role.name }}</span>
                      </button>
                    }
                  </div>
                  @if (!role.editable) {
                    <span class="role-note">Protected or outside your editing authority.</span>
                  }
                } @else {
                  <span class="role-note">Managed in the database.</span>
                }
              </li>
            } @empty {
              <li>No roles in this tenant.</li>
            }
          </ul>
        </section>
      }
      @if (editing() || creating()) {
        <form class="role-form" [formGroup]="form" (ngSubmit)="save()" novalidate>
          <h2>{{ editing() ? 'Edit role' : 'Create role' }}</h2>
          <p>Tenant Name: {{ selectedTenantName() }}</p>
          @if (copyingFrom()) {
            <p role="status">
              Copying {{ copyingFrom() }}. Enter a new role name; the original role will not change.
            </p>
          }
          <div class="field">
            <label for="role-name">Role name</label
            ><input
              id="role-name"
              class="form-control"
              formControlName="name"
              maxlength="120"
              [readOnly]="saving()"
            />
          </div>
          @if (form.controls.name.touched && form.controls.name.invalid) {
            <p role="alert">Enter a role name of 1 to 120 characters.</p>
          }
          <div class="field">
            <label for="role-description">Description</label
            ><textarea
              id="role-description"
              class="form-control"
              formControlName="description"
              maxlength="2000"
              [readOnly]="saving()"
            ></textarea>
          </div>
          @if (permissions.superUser && creating()) {
            <div class="field">
              <label for="role-system">Role identity</label
              ><select id="role-system" class="form-select" formControlName="systemRole">
                <option value="">Ordinary role</option>
                <option value="ADMIN">Protected ADMIN</option>
              </select>
            </div>
          }
          <fieldset [disabled]="saving()">
            <legend>Grantable permissions</legend>
            @if (unavailablePermissions()) {
              <p role="status">
                {{ unavailablePermissions() }} inactive or unavailable permissions will be removed
                when you save this role.
              </p>
            }
            <p>
              Choose any subset, including your entire grantable set. Actions require their View
              permission.
            </p>
            @for (permission of permissions.permissions; track permission.permissionId) {
              <div class="field">
                <label
                  ><input
                    type="checkbox"
                    [checked]="selected().has(permission.permissionId)"
                    (change)="toggle(permission, $event)"
                  />
                  {{ permission.name }} ({{ permission.code }})</label
                >
                @for (route of permission.routes; track route.method + route.path) {
                  <div>
                    <code>{{ route.method }} {{ route.path }}</code>
                  </div>
                }
              </div>
            }
          </fieldset>
          <div class="role-actions">
            <button class="btn btn-primary" type="submit" [disabled]="saving() || busy()">
              {{ saving() ? 'Saving…' : 'Save Role' }}
            </button>
            <button
              class="btn btn-outline-secondary"
              type="button"
              [disabled]="saving()"
              (click)="cancel()"
            >
              Cancel
            </button>
          </div>
        </form>
      }
    }
  `,
})
export class RoleManagementComponent {
  private readonly api = inject(RoleApiService);
  private readonly session = inject(AuthSessionService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(FormBuilder).nonNullable;
  readonly organizations = signal<{ tenantId: number; name: string }[]>([]);
  readonly tenantId = signal(Number(this.session.user()?.tenantId));
  readonly roles = signal<ManagedRole[]>([]);
  readonly catalog = signal<RoleCatalog | null>(null);
  readonly busy = signal(false);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly editing = signal<ManagedRole | null>(null);
  readonly creating = signal(false);
  readonly viewingRoles = signal(true);
  readonly copyingFrom = signal<string | null>(null);
  readonly selected = signal(new Set<number>());
  readonly unavailablePermissions = signal(0);
  readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    description: ['', Validators.maxLength(2000)],
    systemRole: [''],
  });
  constructor() {
    void this.initialize();
  }
  private async initialize() {
    this.busy.set(true);
    try {
      this.organizations.set(
        await firstValueFrom(this.api.organizations().pipe(takeUntilDestroyed(this.destroyRef))),
      );
      await this.load();
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
  async load() {
    this.busy.set(true);
    this.error.set(null);
    this.catalog.set(null);
    this.roles.set([]);
    this.cancel();
    try {
      const data = await firstValueFrom(
        forkJoin({
          roles: this.api.list(this.tenantId()),
          catalog: this.api.catalog(this.tenantId()),
        }).pipe(takeUntilDestroyed(this.destroyRef)),
      );
      this.roles.set(data.roles);
      this.catalog.set(data.catalog);
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
  selectOrganization(event: Event) {
    if (!this.catalog()?.superUser || this.busy() || this.saving()) return;
    this.tenantId.set(Number((event.target as HTMLSelectElement).value));
    void this.load();
  }
  newRole() {
    if (!this.catalog()?.canCreate || this.saving()) return;
    this.cancel();
    this.viewingRoles.set(false);
    this.creating.set(true);
  }
  viewExistingRoles() {
    if (this.busy() || this.saving()) return;
    this.cancel();
    void this.load();
  }
  selectedTenantName() {
    return (
      this.organizations().find((tenant) => tenant.tenantId === this.tenantId())?.name ||
      'Not available'
    );
  }
  canCopy(role: ManagedRole) {
    if (!this.catalog()?.canCreate || role.systemRole === 'SUPER_USER') return false;
    const available = new Set(
      this.catalog()?.permissions.map((permission) => permission.permissionId),
    );
    return role.tenantId === this.tenantId() && role.permissionIds.every((id) => available.has(id));
  }
  copyRole(role: ManagedRole) {
    if (!this.canCopy(role) || this.saving() || this.busy()) return;
    this.newRole();
    this.copyingFrom.set(role.name);
    this.selected.set(new Set(role.permissionIds));
    this.form.reset({ name: '', description: role.description || '', systemRole: '' });
  }
  edit(role: ManagedRole) {
    if (
      role.systemRole === 'SUPER_USER' ||
      !role.editable ||
      !this.catalog()?.canEdit ||
      this.saving()
    )
      return;
    this.copyingFrom.set(null);
    this.viewingRoles.set(false);
    this.creating.set(false);
    this.editing.set(role);
    const grantable = new Set(
      this.catalog()?.permissions.map((permission) => permission.permissionId) ?? [],
    );
    const selected = role.permissionIds.filter((id) => grantable.has(id));
    this.unavailablePermissions.set(role.permissionIds.length - selected.length);
    this.selected.set(new Set(selected));
    this.form.reset({ name: role.name, description: role.description ?? '', systemRole: '' });
    this.error.set(null);
  }
  cancel() {
    this.editing.set(null);
    this.creating.set(false);
    this.viewingRoles.set(true);
    this.copyingFrom.set(null);
    this.selected.set(new Set());
    this.unavailablePermissions.set(0);
    this.form.reset();
  }
  toggle(permission: RolePermission, event: Event) {
    const values = new Set(this.selected());
    if ((event.target as HTMLInputElement).checked) {
      values.add(permission.permissionId);
      if (permission.prerequisiteId !== null) values.add(permission.prerequisiteId);
    } else {
      values.delete(permission.permissionId);
      for (const item of this.catalog()?.permissions ?? [])
        if (item.prerequisiteId === permission.permissionId) values.delete(item.permissionId);
    }
    this.selected.set(values);
  }
  async save() {
    if (this.saving() || this.busy() || (!this.creating() && !this.editing())) return;
    this.form.markAllAsTouched();
    if (this.form.invalid || !this.form.controls.name.value.trim()) return;
    this.saving.set(true);
    this.error.set(null);
    const value = this.form.getRawValue();
    const role = this.editing();
    const input: RoleWrite = {
      tenantId: this.tenantId(),
      name: value.name,
      description: value.description,
      permissionIds: [...this.selected()],
      ...(role
        ? { version: role.version }
        : value.systemRole
          ? { systemRole: value.systemRole }
          : {}),
    };
    try {
      await firstValueFrom(
        (role ? this.api.update(role.roleId, input) : this.api.create(input)).pipe(
          takeUntilDestroyed(this.destroyRef),
        ),
      );
      this.session.invalidate();
      await this.router.navigate(['/user/login'], { queryParams: { reason: 'role-saved' } });
    } catch (error) {
      this.error.set(await apiErrorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }
}
