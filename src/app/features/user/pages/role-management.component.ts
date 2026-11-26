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
  template: `
    <h1>Role Management</h1>
    <p>Manage roles in your authorized tenant. System roles have additional protection.</p>
    <a routerLink="/">Back to library</a>
    @if (error()) {
      <p role="alert" class="auth-error">{{ error() }}</p>
    }
    @if (busy()) {
      <p role="status">Loading roles…</p>
    }
    @if (organizations().length) {
      <div class="field">
        <label for="role-organization">Tenant Name</label>
        <select
          id="role-organization"
          class="form-select"
          [value]="tenantId()"
          [disabled]="busy() || saving()"
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
      <section aria-label="Tenant roles">
        <h2>Roles</h2>
        <ul class="role-list">
          @for (role of roles(); track role.roleId) {
            <li>
              <strong>{{ role.name }}</strong>
              {{ role.systemRole ? '(' + role.systemRole + ')' : '' }}
              <button
                type="button"
                class="btn btn-outline-primary"
                [disabled]="!role.editable || busy() || saving()"
                (click)="edit(role)"
              >
                Edit {{ role.name }}
              </button>
              @if (!role.editable) {
                <span>Protected or outside your editing authority.</span>
              }
            </li>
          } @empty {
            <li>No roles in this tenant.</li>
          }
        </ul>
        @if (permissions.canCreate) {
          <button
            type="button"
            class="btn btn-primary"
            [disabled]="saving() || busy()"
            (click)="newRole()"
          >
            New role
          </button>
        }
      </section>
      @if (editing() || creating()) {
        <form [formGroup]="form" (ngSubmit)="save()" novalidate>
          <h2>{{ editing() ? 'Edit role' : 'Create role' }}</h2>
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
          <button class="btn btn-primary" type="submit" [disabled]="saving() || busy()">
            {{ saving() ? 'Saving…' : 'Save role' }}
          </button>
          <button
            class="btn btn-outline-secondary"
            type="button"
            [disabled]="saving()"
            (click)="cancel()"
          >
            Cancel
          </button>
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
    this.tenantId.set(Number((event.target as HTMLSelectElement).value));
    void this.load();
  }
  newRole() {
    if (!this.catalog()?.canCreate || this.saving()) return;
    this.cancel();
    this.creating.set(true);
  }
  edit(role: ManagedRole) {
    if (!role.editable || !this.catalog()?.canEdit || this.saving()) return;
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
