import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SidebarComponent } from './sidebar.component';
import { AuthSessionService } from '../../features/user/services/auth-session.service';
import { CurrentProfileService } from '../../features/user/services/current-profile.service';
import { NavigationAccessService } from '../../features/user/services/navigation-access.service';

describe('Shared account sidebar', () => {
  const identity = { userId: 1, email: 'reader@example.com', displayName: 'Library Reader' };
  const current = signal<typeof identity | null>(identity);
  const profile = signal({
    username: identity.email,
    displayName: identity.displayName,
    tenantType: 'ORGANIZATION',
    roles: ['Reader', 'Editor'],
  });
  const capabilities = signal({
    canViewUsers: false,
    canCreateUsers: false,
    canManageRoles: false,
  });
  let logout: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    current.set(identity);
    profile.set({
      username: identity.email,
      displayName: identity.displayName,
      tenantType: 'ORGANIZATION',
      roles: ['Reader', 'Editor'],
    });
    capabilities.set({ canViewUsers: false, canCreateUsers: false, canManageRoles: false });
    logout = vi.fn(() => current.set(null));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: AuthSessionService,
          useValue: { user: current, loggingOut: signal(false), logout },
        },
        {
          provide: CurrentProfileService,
          useValue: { profile, refresh: vi.fn(() => Promise.resolve()) },
        },
        {
          provide: NavigationAccessService,
          useValue: {
            capabilities,
            loading: signal(false),
            error: signal(null),
            ensure: vi.fn(() => Promise.resolve(capabilities())),
            refresh: vi.fn(),
          },
        },
      ],
    });
  });
  it('shows own identity, tenant type and assigned roles with an illustrated fallback and no permissions or editing', async () => {
    const fixture = TestBed.createComponent(SidebarComponent);
    await fixture.whenStable();
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[role=img]')?.getAttribute('aria-label')).toContain(
      'Default illustrated',
    );
    expect(element.textContent).toContain('reader@example.com');
    expect(element.textContent).toContain('Library Reader');
    expect(element.textContent).toContain('Organisation');
    expect(element.textContent).toContain('Reader');
    expect(element.textContent).toContain('Editor');
    expect([...element.querySelectorAll('dt')].map((item) => item.textContent?.trim())).toEqual([
      'Username',
      'Name',
      'Account Type',
      'Access Roles',
    ]);
    expect(element.querySelectorAll('dd')).toHaveLength(4);
    expect(element.querySelector('dd button,dd input,dd a')).toBeNull();
    expect(element.querySelector('a[href="/user/profile"]')?.textContent).toContain(
      'View Your Detail',
    );
    expect(element.textContent).not.toContain('permissions');
    expect(element.querySelector('input,select,textarea')).toBeNull();
  });
  it('shows only explicitly permitted management links and keeps create separate from role management', async () => {
    const fixture = TestBed.createComponent(SidebarComponent);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a[href="/user/users"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('a[href="/user/roles"]')).toBeNull();
    capabilities.set({ canViewUsers: true, canCreateUsers: true, canManageRoles: false });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a[href="/user/users"]')?.textContent).toContain(
      'User Management',
    );
    expect(fixture.nativeElement.querySelector('a[href="/user/users/new"]')?.textContent).toContain(
      'Add User',
    );
    expect(fixture.nativeElement.querySelector('a[href="/user/roles"]')).toBeNull();
    capabilities.set({ canViewUsers: true, canCreateUsers: false, canManageRoles: true });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a[href="/user/roles"]')?.textContent).toContain(
      'Role Management',
    );
  });
  it('keeps Add User permission-based even for an Individual profile', async () => {
    profile.set({ ...profile(), tenantType: 'INDIVIDUAL' });
    capabilities.set({ canViewUsers: false, canCreateUsers: true, canManageRoles: false });
    const fixture = TestBed.createComponent(SidebarComponent);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Individual');
    expect(fixture.nativeElement.querySelector('a[href="/user/users/new"]')?.textContent).toContain(
      'Add User',
    );
    profile.set({ ...profile(), tenantType: 'ORGANIZATION' });
  });
  it('exposes collapse and logout actions and removes own identity on logout', async () => {
    const fixture = TestBed.createComponent(SidebarComponent);
    const collapse = vi.fn();
    fixture.componentInstance.collapse.subscribe(collapse);
    await fixture.whenStable();
    fixture.detectChanges();
    fixture.nativeElement.querySelector('[aria-label="Collapse sidebar"]').click();
    expect(collapse).toHaveBeenCalledOnce();
    fixture.nativeElement.querySelector('.logout-button').click();
    fixture.detectChanges();
    expect(logout).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.textContent).not.toContain(identity.email);
  });
});
