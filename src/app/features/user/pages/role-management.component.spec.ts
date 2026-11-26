import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { RoleApiService, ManagedRole } from '../data-access/role-api.service';
import { AuthSessionService } from '../services/auth-session.service';
import { RoleManagementComponent } from './role-management.component';

describe('Organization role management', () => {
  const protectedRole: ManagedRole = {
    roleId: 1,
    tenantId: 10,
    name: 'Administrator',
    description: '',
    systemRole: 'ADMIN',
    version: 3,
    permissionIds: [1, 2],
    editable: false,
  };
  const ordinary: ManagedRole = {
    roleId: 2,
    tenantId: 10,
    name: 'Reader',
    description: '',
    systemRole: null,
    version: 4,
    permissionIds: [1],
    editable: true,
  };
  let api: {
    organizations: ReturnType<typeof vi.fn>;
    list: ReturnType<typeof vi.fn>;
    catalog: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    update: ReturnType<typeof vi.fn>;
  };
  let session: { user: ReturnType<typeof vi.fn>; invalidate: ReturnType<typeof vi.fn> };
  beforeEach(() => {
    api = {
      organizations: vi.fn(() => of([{ tenantId: 10, name: 'Company A' }])),
      list: vi.fn(() => of([protectedRole, ordinary])),
      catalog: vi.fn(() =>
        of({
          tenantId: 10,
          superUser: false,
          canCreate: true,
          canEdit: true,
          canAssign: false,
          permissions: [
            {
              permissionId: 1,
              code: 'BOOKS.VIEW',
              name: 'View',
              prerequisiteId: null,
              routes: [{ method: 'GET', path: '/api/search/books' }],
            },
            {
              permissionId: 2,
              code: 'BOOKS.EDIT',
              name: 'Edit',
              prerequisiteId: 1,
              routes: [{ method: 'PATCH', path: '/api/books/{id}/metadata' }],
            },
          ],
        }),
      ),
      create: vi.fn(() => of(ordinary)),
      update: vi.fn(() => of(ordinary)),
    };
    session = { user: vi.fn(() => ({ tenantId: 10 })), invalidate: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: RoleApiService, useValue: api },
        { provide: AuthSessionService, useValue: session },
      ],
    });
  });
  async function page() {
    const fixture = TestBed.createComponent(RoleManagementComponent);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }
  it('lists only the authorized scope and disables protected role edits while showing method/route distinctions', async () => {
    const fixture = await page();
    const component = fixture.componentInstance;
    expect(api.list).toHaveBeenCalledWith(10);
    expect(api.catalog).toHaveBeenCalledWith(10);
    const buttons = [...fixture.nativeElement.querySelectorAll('button')] as HTMLButtonElement[];
    expect(buttons.find((b) => b.textContent?.includes('Edit Administrator'))?.disabled).toBe(true);
    component.edit(protectedRole);
    expect(component.editing()).toBeNull();
    component.newRole();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#role-system')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('GET /api/search/books');
    expect(fixture.nativeElement.textContent).toContain('PATCH /api/books/{id}/metadata');
  });
  it('sends an equal permission set without client role identity and prevents duplicate saves', async () => {
    const component = (await page()).componentInstance;
    component.newRole();
    component.form.controls.name.setValue('Equal');
    component.selected.set(new Set([1, 2]));
    const pending = new Subject<ManagedRole>();
    api.create.mockReturnValue(pending);
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const saving = component.save();
    await component.save();
    expect(api.create).toHaveBeenCalledOnce();
    expect(api.create).toHaveBeenCalledWith({
      tenantId: 10,
      name: 'Equal',
      description: '',
      permissionIds: [1, 2],
    });
    pending.next(ordinary);
    pending.complete();
    await saving;
    expect(session.invalidate).toHaveBeenCalledOnce();
  });
  it('submits the version on edits and shows stale-save errors without reporting success', async () => {
    const component = (await page()).componentInstance;
    component.edit(ordinary);
    component.form.controls.name.setValue('Renamed');
    api.update.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { detail: 'This role changed. Reload it before saving.' },
          }),
      ),
    );
    await component.save();
    expect(api.update).toHaveBeenCalledWith(
      2,
      expect.objectContaining({ tenantId: 10, version: 4, name: 'Renamed', permissionIds: [1] }),
    );
    expect(component.error()).toContain('Reload');
    expect(session.invalidate).not.toHaveBeenCalled();
  });
  it('displays a clear backend denial and never exposes a role form when unauthorized', async () => {
    api.organizations.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 403,
            error: { detail: 'You do not have permission to perform this action.' },
          }),
      ),
    );
    const fixture = await page();
    expect(fixture.nativeElement.querySelector('[role=alert]')?.textContent).toContain(
      'permission',
    );
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
  });
  it('explains removal of inactive grants when an authorized editor opens a role', async () => {
    const fixture = await page();
    fixture.componentInstance.edit({ ...ordinary, permissionIds: [1, 999] });
    fixture.detectChanges();
    expect([...fixture.componentInstance.selected()]).toEqual([1]);
    expect(fixture.nativeElement.textContent).toContain(
      'inactive or unavailable permissions will be removed',
    );
  });
  it('includes action prerequisites and removes dependent actions when View is unchecked', async () => {
    const component = (await page()).componentInstance;
    const catalog = component.catalog()!;
    component.toggle(catalog.permissions[1], { target: { checked: true } } as unknown as Event);
    expect([...component.selected()]).toEqual([2, 1]);
    component.toggle(catalog.permissions[0], { target: { checked: false } } as unknown as Event);
    expect(component.selected().size).toBe(0);
  });
});
