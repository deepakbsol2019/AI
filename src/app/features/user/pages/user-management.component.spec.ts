import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { of, Subject, throwError } from 'rxjs';
import { UserManagementComponent } from './user-management.component';
import {
  UserManagementApiService,
  ManagedUser,
  UserDetails,
  DeletionPreview,
  DeletionJob,
} from '../data-access/user-management-api.service';
import { AuthSessionService } from '../services/auth-session.service';
import { CurrentProfileService } from '../services/current-profile.service';
import { NavigationAccessService } from '../services/navigation-access.service';

describe('Routed User Management pages', () => {
  const user: ManagedUser = {
    userId: 12,
    username: 'employee@example.com',
    displayName: 'Employee',
    tenantName: 'Tenant A',
    tenantId: 4,
    tenantType: 'ORGANIZATION',
    roles: [
      {
        roleId: 2,
        name: 'Reader',
        code: 'STAFF',
        system: false,
        status: 'ACTIVE',
        description: 'Read books',
        permissions: ['BOOKS.VIEW'],
      },
    ],
    status: 'ACTIVE',
    createdAt: null,
    lastLogin: null,
    version: 'version-1',
    canEdit: true,
    canSoftDelete: true,
    canHardDelete: true,
    canActivate: false,
    canAssignRoles: false,
    pendingJobId: null,
    deleted: false,
    deletedAt: null,
  };
  const detail: UserDetails = {
    user,
    email: user.username,
    emailVerifiedAt: null,
    updatedAt: null,
    tenantStatus: 'ACTIVE',
    membershipStatus: 'ACTIVE',
    effectivePermissions: [{ code: 'BOOKS.VIEW', name: 'View books', sourceRoles: ['Reader'] }],
    directGrantsSupported: false,
  };
  const preview: DeletionPreview = {
    userId: 12,
    tenantId: 4,
    tenantName: 'Tenant A',
    scope: 'TENANT_PURGE',
    affectedUsers: 5,
    permanent: true,
    preservesFiles: false,
    confirmation: 'DELETE TENANT 4',
    version: 'version-1',
    explanation: 'Permanently deletes this tenant and all its users and files.',
  };
  const job: DeletionJob = {
    jobId: 9,
    tenantId: 4,
    userId: 12,
    scope: 'TENANT_PURGE',
    status: 'PENDING',
    databasePurged: false,
    attempts: 0,
    error: null,
    updatedAt: '2026-11-26T02:00:00Z',
  };
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let currentUser: { userId: number } | null;
  let capabilities: {
    canViewUsers: boolean;
    canCreateUsers: boolean;
    canManageRoles: boolean;
    superUser: boolean;
    tenantId: number;
  };
  beforeEach(() => {
    currentUser = { userId: 1 };
    api = {
      list: vi.fn(() => of({ users: [user], total: 100, page: 0, size: 20, superUser: true })),
      details: vi.fn(() => of(detail)),
      edit: vi.fn(() =>
        of({ ...detail, user: { ...user, displayName: 'Updated', version: 'version-2' } }),
      ),
      roles: vi.fn(() => of(user.roles)),
      assign: vi.fn(() => of(detail)),
      preview: vi.fn(() => of(preview)),
      soft: vi.fn(() => of(undefined)),
      hard: vi.fn(() => of(job)),
      activate: vi.fn(() =>
        of({ ...detail, user: { ...user, status: 'ACTIVE', canActivate: false } }),
      ),
      job: vi.fn(() => of(job)),
      jobs: vi.fn(() => of([job])),
      retry: vi.fn(() => of(job)),
      createOptions: vi.fn(() =>
        of({
          tenantId: 4,
          tenantName: 'Tenant A',
          tenantType: 'ORGANIZATION',
          loginPolicy: 'PASSWORD',
        }),
      ),
      createTenants: vi.fn(() =>
        of([
          {
            tenantId: 4,
            tenantName: 'Tenant A',
            tenantType: 'ORGANIZATION',
            loginPolicy: 'PASSWORD',
          },
          { tenantId: 7, tenantName: 'Tenant B', tenantType: 'INDIVIDUAL', loginPolicy: 'SSO' },
        ]),
      ),
      create: vi.fn(() => of({ userId: 18, message: 'Account setup instructions were sent.' })),
    };
    capabilities = {
      canViewUsers: true,
      canCreateUsers: true,
      canManageRoles: true,
      superUser: true,
      tenantId: 4,
    };
    const page = (path: string, userPage: string) => ({
      path,
      component: UserManagementComponent,
      data: { userPage },
    });
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          page('user/users/new', 'create'),
          page('user/users/:id/delete', 'delete'),
          page('user/users/:id', 'details'),
          page('user/users', 'list'),
        ]),
        { provide: UserManagementApiService, useValue: api },
        { provide: AuthSessionService, useValue: { user: () => currentUser } },
        { provide: CurrentProfileService, useValue: { refresh: vi.fn(() => Promise.resolve()) } },
        {
          provide: NavigationAccessService,
          useValue: {
            capabilities: () => capabilities,
            ensure: vi.fn(() => Promise.resolve(capabilities)),
          },
        },
      ],
    });
  });
  async function page(url = '/user/users') {
    const harness = await RouterTestingHarness.create(url);
    await harness.fixture.whenStable();
    harness.detectChanges();
    return {
      harness,
      component: harness.routeDebugElement!.componentInstance as UserManagementComponent,
      element: harness.routeNativeElement!,
    };
  }
  it('renders exactly the eight requested columns and full-page links with list context', async () => {
    const { element } = await page('/user/users?search=employee&status=ALL&page=2');
    expect(
      [...element.querySelectorAll('thead th')].map((item) => item.textContent?.trim()),
    ).toEqual([
      'Username',
      'Tenant Name',
      'Tenant Type',
      'Role',
      'Status',
      'View User Details',
      'Soft Delete',
      'Hard Delete',
    ]);
    expect(element.textContent).toContain('Organisation');
    expect(element.textContent).toContain('Active');
    const link = [...element.querySelectorAll('a')].find(
      (item) => item.textContent?.trim() === 'View User Details',
    )!;
    expect(link.getAttribute('href')).toBe('/user/users/12?search=employee&status=ALL&page=2');
    expect(element.querySelector('[role="dialog"],dialog')).toBeNull();
    expect(api['list']).toHaveBeenCalledWith('employee', 'ALL', 2, 20);
  });
  it('defaults to the active listing and distinguishes soft-deleted from inactive', async () => {
    api['list'].mockReturnValue(
      of({
        users: [{ ...user, status: 'INACTIVE', deleted: true }],
        total: 1,
        page: 0,
        size: 20,
        superUser: true,
      }),
    );
    const { element } = await page();
    expect(api['list']).toHaveBeenCalledWith('', 'ACTIVE', 0, 20);
    expect(element.textContent).toContain('Inactive');
    expect(element.querySelector('tbody')?.textContent).toContain('Soft-deleted');
    expect(
      [...element.querySelectorAll('#user-status option')].map((option) =>
        option.textContent?.trim(),
      ),
    ).toEqual(['Active', 'Inactive', 'All Users']);
  });
  it('loads dedicated details on a direct URL without exposing secrets or losing the return context', async () => {
    const { component, element, harness } = await page(
      '/user/users/12?search=employee&status=ALL&page=2',
    );
    expect(api['details']).toHaveBeenCalledWith(12);
    expect(api['list']).not.toHaveBeenCalled();
    expect((element.querySelector('#managed-username') as HTMLInputElement).readOnly).toBe(true);
    expect(element.textContent).toContain('Tenant Type');
    expect(element.textContent).toContain('role-derived: Reader');
    expect(element.querySelector('input[type="password"],[role="dialog"]')).toBeNull();
    component.profileForm.controls.displayName.setValue('Updated');
    component.profileForm.markAsDirty();
    await component.saveProfile();
    harness.detectChanges();
    expect(api['edit']).toHaveBeenCalledWith(12, 'Updated', 'version-1');
    expect(component.details()?.user.version).toBe('version-2');
    expect(TestBed.inject(Router).url).toContain('search=employee&status=ALL&page=2');
    component.requestReturn();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/user/users?search=employee&status=ALL&page=2');
  });
  it('handles unsaved page edits explicitly and keeps editing without data loss', async () => {
    const { component } = await page('/user/users/12');
    component.profileForm.controls.displayName.setValue('Unsaved');
    component.profileForm.markAsDirty();
    component.requestReturn();
    expect(component.discardPrompt()).toBe(true);
    expect(TestBed.inject(Router).url).toBe('/user/users/12');
    component.discardPrompt.set(false);
    expect(component.profileForm.controls.displayName.value).toBe('Unsaved');
    component.discardAndReturn();
    await TestBed.inject(Router).navigateByUrl('/user/users');
    expect(api['edit']).not.toHaveBeenCalled();
  });
  it('never lets unsaved edits block logout or session-expiry navigation', async () => {
    const { component } = await page('/user/users/12');
    component.profileForm.controls.displayName.setValue('Unsaved');
    component.profileForm.markAsDirty();
    expect(component.canLeave()).toBe(false);
    currentUser = null;
    expect(component.canLeave()).toBe(true);
    const event = new Event('beforeunload', { cancelable: true }) as BeforeUnloadEvent;
    component.beforeUnload(event);
    expect(event.defaultPrevented).toBe(false);
  });
  it('clears the previous user while a changed detail URL is loading and rejects a late response', async () => {
    const { component, harness } = await page('/user/users/12');
    expect(component.details()?.user.username).toBe(user.username);
    const pending = new Subject<UserDetails>();
    api['details'].mockReturnValue(pending);
    await harness.navigateByUrl('/user/users/99');
    harness.detectChanges();
    expect(component.details()).toBeNull();
    expect(harness.routeNativeElement?.textContent).not.toContain(user.username);
    api['details'].mockReturnValue(
      of({ ...detail, user: { ...user, userId: 100, username: 'next@example.com' } }),
    );
    await harness.navigateByUrl('/user/users/100');
    pending.next(detail);
    pending.complete();
    await harness.fixture.whenStable();
    expect(component.details()?.user.userId).toBe(100);
  });
  it('keeps pending role choices through profile save and uses the separate assignment endpoint', async () => {
    api['details'].mockReturnValue(of({ ...detail, user: { ...user, canAssignRoles: true } }));
    api['edit'].mockReturnValue(
      of({
        ...detail,
        user: { ...user, displayName: 'Updated', version: 'version-2', canAssignRoles: true },
      }),
    );
    const { component } = await page('/user/users/12');
    component.selectedRoles.set(new Set([3]));
    component.profileForm.controls.displayName.setValue('Updated');
    component.profileForm.markAsDirty();
    await component.saveProfile();
    expect([...component.selectedRoles()]).toEqual([3]);
    expect(component.rolesDirty()).toBe(true);
    await component.saveRoles();
    expect(api['assign']).toHaveBeenCalledWith(12, [3], 'version-2');
  });
  it('preserves the server confirmation flow as a page and never reports queued cleanup complete', async () => {
    const { component, element, harness } = await page(
      '/user/users/12/delete?hard=true&search=employee&status=ALL&page=2',
    );
    expect(element.querySelector('[role="dialog"],dialog')).toBeNull();
    expect(element.textContent).toContain('Affected users: 5');
    expect(element.textContent).toContain('DELETE TENANT 4');
    component.confirmationForm.controls.confirmation.setValue('DELETE USER 12');
    await component.confirmDelete();
    expect(api['hard']).not.toHaveBeenCalled();
    component.confirmationForm.controls.confirmation.setValue(preview.confirmation);
    await component.confirmDelete();
    harness.detectChanges();
    expect(api['hard']).toHaveBeenCalledWith(12, preview, preview.confirmation);
    expect(element.textContent).toContain('Deletion is not complete.');
    expect(api['list']).not.toHaveBeenCalled();
    api['job'].mockReturnValue(
      of({ ...job, status: 'FAILED', databasePurged: true, error: 'Cleanup requires review.' }),
    );
    await component.refreshJob(9);
    harness.detectChanges();
    api['jobs'].mockReturnValue(
      of([{ ...job, status: 'FAILED', databasePurged: true, error: 'Cleanup requires review.' }]),
    );
    await component.loadJobs();
    harness.detectChanges();
    expect(element.querySelector('[role="dialog"] table')?.textContent).toContain('Completed');
    expect(element.textContent).toContain('Cleanup requires review.');
    expect(element.textContent).toContain('Retry validated cleanup');
    api['job'].mockReturnValue(of({ ...job, status: 'COMPLETE', databasePurged: true }));
    await component.refreshJob(9);
    harness.detectChanges();
    component.closeCleanup();
    harness.detectChanges();
    expect(element.textContent).toContain('Deletion and required cleanup completed successfully.');
    expect(element.querySelector('.result-success')).not.toBeNull();
  });
  it('shows a failure or success result and returns only when requested with the previous filters', async () => {
    const soft = {
      ...preview,
      permanent: false,
      preservesFiles: true,
      scope: 'USER_DEACTIVATE',
      confirmation: 'DEACTIVATE USER 12',
    };
    api['preview'].mockReturnValue(of(soft));
    const { component, harness } = await page(
      '/user/users/12/delete?hard=false&search=employee&status=ALL&page=2',
    );
    component.confirmationForm.controls.confirmation.setValue(soft.confirmation);
    api['soft'].mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({ status: 409, error: { detail: 'The affected scope changed.' } }),
      ),
    );
    await component.confirmDelete();
    expect(api['list']).not.toHaveBeenCalled();
    expect(component.error()).toContain('scope changed');
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('.result-failure')).not.toBeNull();
    expect(harness.routeNativeElement?.querySelector('a')).toBeNull();
    component.returnFromResult();
    await harness.fixture.whenStable();
    const retry = await harness.navigateByUrl(
      '/user/users/12/delete?hard=false&search=employee&status=ALL&page=2',
      UserManagementComponent,
    );
    await harness.fixture.whenStable();
    retry.confirmationForm.controls.confirmation.setValue(soft.confirmation);
    api['soft'].mockReturnValue(of(undefined));
    await retry.confirmDelete();
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('.result-success')).not.toBeNull();
    expect(harness.routeNativeElement?.textContent).toContain('✓');
    retry.returnFromResult();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toContain('search=employee&status=ALL&page=2');
    expect(api['list']).toHaveBeenCalledWith('employee', 'ALL', 2, 20);
    expect(api['hard']).not.toHaveBeenCalled();
  });
  it('uses the authorised tenant selector without an authentication type field and retains failed input', async () => {
    const { component, element, harness } = await page('/user/users/new');
    expect(element.textContent).toContain('Tenant A');
    expect((element.querySelector('#create-tenant') as HTMLSelectElement).disabled).toBe(false);
    expect(element.querySelectorAll('select')).toHaveLength(1);
    expect(element.querySelector('[role="dialog"]')).toBeNull();
    component.createForm.setValue({ email: 'new@example.com', displayName: 'New user' });
    api['create'].mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({ status: 503, error: { detail: 'Email delivery unavailable.' } }),
      ),
    );
    await component.createUser();
    expect(component.createForm.controls.email.value).toBe('new@example.com');
    expect(component.error()).toContain('Email delivery unavailable');
    api['create'].mockReturnValue(of({ userId: 18, message: 'Setup sent.' }));
    await component.createUser();
    await harness.fixture.whenStable();
    expect(api['create']).toHaveBeenLastCalledWith('new@example.com', 'New user', 4);
    expect(TestBed.inject(Router).url).toContain('/user/users?');
    expect(TestBed.inject(Router).url).toContain('notice=created');
  });
  it('does not show or submit Create User without its explicit capability', async () => {
    capabilities.canCreateUsers = false;
    const { element, harness } = await page();
    expect(
      [...element.querySelectorAll('a')].some((link) => link.textContent?.trim() === 'Create User'),
    ).toBe(false);
    const component = await harness.navigateByUrl('/user/users/new', UserManagementComponent);
    await harness.fixture.whenStable();
    component.createForm.setValue({ email: 'forged@example.com', displayName: 'Forged' });
    await component.createUser();
    expect(api['create']).not.toHaveBeenCalled();
  });
  it('keeps guarding later edits after a create-only user succeeds without leaving the form', async () => {
    capabilities.canViewUsers = false;
    const { component } = await page('/user/users/new');
    component.createForm.setValue({ email: 'first@example.com', displayName: 'First' });
    await component.createUser();
    expect(TestBed.inject(Router).url).toBe('/user/users/new');
    component.createForm.controls.email.setValue('second@example.com');
    component.createForm.markAsDirty();
    expect(component.canLeave()).toBe(false);
  });
  it('shows loading, empty and API failure states on the routed pages', async () => {
    const pending = new Subject<UserDetails>();
    api['details'].mockReturnValue(pending);
    const harness = await RouterTestingHarness.create('/user/users/12');
    harness.detectChanges();
    expect(harness.routeNativeElement?.textContent).toContain('Loading authorized');
    pending.error(
      new HttpErrorResponse({
        status: 404,
        error: { detail: 'User not found in your authorized scope.' },
      }),
    );
    await vi.waitFor(() =>
      expect(
        (harness.routeDebugElement!.componentInstance as UserManagementComponent).error(),
      ).toContain('User not found'),
    );
    harness.detectChanges();
    expect(harness.routeNativeElement?.textContent).toContain('User not found');
    api['list'].mockReturnValue(of({ users: [], total: 0, page: 0, size: 20, superUser: false }));
    await harness.navigateByUrl('/user/users');
    await vi.waitFor(() =>
      expect(
        (harness.routeDebugElement!.componentInstance as UserManagementComponent).result()?.total,
      ).toBe(0),
    );
    harness.detectChanges();
    expect(harness.routeNativeElement?.textContent).toContain('No users match your search');
  });
  it('normalises the removed soft-delete filter to All Users and keeps the Library action in the heading', async () => {
    const { element } = await page('/user/users?status=DELETED');
    expect(api['list']).toHaveBeenCalledWith('', 'ALL', 0, 20);
    expect(
      element.querySelector('.management-heading .heading-actions a[href="/"]')?.textContent,
    ).toContain('Back to Library');
  });
  it('opens recent cleanup as a table modal and refreshes status without navigation', async () => {
    const { component, element, harness } = await page();
    const before = TestBed.inject(Router).url;
    await component.loadJobs();
    harness.detectChanges();
    expect(element.querySelector('[role="dialog"] table thead')?.textContent).toContain(
      'Database Removal',
    );
    expect(element.querySelector('[role="dialog"] table tbody')?.textContent).toContain('PENDING');
    api['jobs'].mockReturnValue(
      of([
        {
          ...job,
          status: 'FAILED',
          databasePurged: true,
          attempts: 2,
          error: 'Cannot remove validated file',
        },
      ]),
    );
    await component.refreshCleanup();
    harness.detectChanges();
    expect(element.querySelector('[role="dialog"] table tbody')?.textContent).toContain('FAILED');
    expect(element.querySelector('[role="dialog"] table tbody')?.textContent).toContain(
      'Cannot remove validated file',
    );
    expect(TestBed.inject(Router).url).toBe(before);
    api['retry'].mockReturnValue(
      throwError(
        () => new HttpErrorResponse({ status: 403, error: { detail: 'Retry authority changed' } }),
      ),
    );
    await component.retryJob(9);
    harness.detectChanges();
    expect(element.querySelector('[role="dialog"]')?.textContent).toContain(
      'Retry authority changed',
    );
    component.cleanupKeys(new KeyboardEvent('keydown', { key: 'Escape' }));
    harness.detectChanges();
    expect(element.querySelector('[role="dialog"]')).toBeNull();
  });
  it('keeps ordinary creators on their own disabled tenant and lets the super user select another tenant', async () => {
    capabilities.superUser = false;
    const { component, element, harness } = await page('/user/users/new');
    expect((element.querySelector('#create-tenant') as HTMLSelectElement).disabled).toBe(true);
    expect(element.querySelectorAll('#create-tenant option')).toHaveLength(1);
    await component.selectCreateTenant({ target: { value: '7' } } as unknown as Event);
    expect(component.createOptions()?.tenantId).toBe(4);
    capabilities.superUser = true;
    component.createTenants.set([
      { tenantId: 4, tenantName: 'Tenant A', tenantType: 'ORGANIZATION', loginPolicy: 'PASSWORD' },
      { tenantId: 7, tenantName: 'Tenant B', tenantType: 'INDIVIDUAL', loginPolicy: 'SSO' },
    ]);
    const failedSelect = { value: '7' };
    api['createOptions'].mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({ status: 503, error: { detail: 'Tenant options unavailable' } }),
      ),
    );
    await component.selectCreateTenant({ target: failedSelect } as unknown as Event);
    expect(failedSelect.value).toBe('4');
    api['createOptions'].mockReturnValue(
      of({ tenantId: 7, tenantName: 'Tenant B', tenantType: 'INDIVIDUAL', loginPolicy: 'SSO' }),
    );
    await component.selectCreateTenant({ target: { value: '7' } } as unknown as Event);
    harness.detectChanges();
    expect(api['createOptions']).toHaveBeenLastCalledWith(7);
    expect(component.createOptions()?.loginPolicy).toBe('SSO');
    expect(element.querySelectorAll('select')).toHaveLength(1);
    component.createForm.setValue({ email: 'selected@example.com', displayName: 'Selected user' });
    await component.createUser();
    expect(api['create']).toHaveBeenCalledWith('selected@example.com', 'Selected user', 7);
  });
  it('returns from the result close control to the previous detail page only', async () => {
    api['preview'].mockReturnValue(of({ ...preview, permanent: false, preservesFiles: true }));
    const { component, element, harness } = await page(
      '/user/users/12/delete?hard=false&from=details&search=employee&status=ALL&page=2',
    );
    component.confirmationForm.controls.confirmation.setValue(preview.confirmation);
    await component.confirmDelete();
    harness.detectChanges();
    expect(element.querySelector('.delete-result a')).toBeNull();
    (element.querySelector('.result-close') as HTMLButtonElement).click();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/user/users/12?search=employee&status=ALL&page=2');
  });
  it('keeps activation separate from profile editing and selected-account only', async () => {
    api['details'].mockReturnValue(
      of({ ...detail, user: { ...user, status: 'INACTIVE', canActivate: true } }),
    );
    const { component } = await page('/user/users/12');
    await component.activate();
    expect(api['activate']).toHaveBeenCalledWith(12, 'version-1', false);
    expect(api['edit']).not.toHaveBeenCalled();
  });
});
