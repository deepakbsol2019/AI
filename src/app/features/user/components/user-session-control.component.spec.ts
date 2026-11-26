import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, Subject } from 'rxjs';
import { UserSessionControlComponent } from './user-session-control.component';
import { AuthSessionService } from '../services/auth-session.service';
import {
  UserManagementApiService,
  CurrentProfile,
} from '../data-access/user-management-api.service';
import { CurrentProfileService } from '../services/current-profile.service';
describe('Authenticated header profile', () => {
  const profile: CurrentProfile = {
    username: 'current@example.com',
    displayName: 'Current User',
    email: 'current@example.com',
    tenantName: 'Current Company',
    tenantType: 'ORGANIZATION',
    roles: ['Reader', 'Editor'],
    status: 'ACTIVE',
    createdAt: null,
    lastLogin: null,
  };
  const current = signal<{ userId: number; email: string; displayName: string } | null>(null);
  let api: { profile: ReturnType<typeof vi.fn> };
  beforeEach(() => {
    current.set({ userId: 1, email: profile.username, displayName: profile.displayName });
    api = { profile: vi.fn(() => of({ ...profile, effectivePermissions: ['MUST_NOT_RENDER'] })) };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: AuthSessionService,
          useValue: {
            user: current,
            loggingOut: signal(false),
            logout: vi.fn(() => current.set(null)),
          },
        },
        { provide: UserManagementApiService, useValue: api },
      ],
    });
  });
  async function header() {
    const fixture = TestBed.createComponent(UserSessionControlComponent);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }
  it('loads only the current-user endpoint and shows username/profile/roles without editing or permission lists', async () => {
    const fixture = await header();
    await fixture.componentInstance.toggle();
    fixture.detectChanges();
    expect(api.profile).toHaveBeenCalledWith();
    const panel = fixture.nativeElement.querySelector('[role=dialog]') as HTMLElement;
    expect(panel.textContent).toContain('current@example.com');
    expect(panel.textContent).toContain('Reader');
    expect(panel.textContent).toContain('Editor');
    expect(panel.textContent).not.toContain('MUST_NOT_RENDER');
    expect(panel.textContent?.toLowerCase()).not.toContain('permissions');
    expect(panel.querySelector('input,textarea,select,form')).toBeNull();
  });
  it('supports Escape and outside-click dismissal and clears identity on logout', async () => {
    const fixture = await header();
    await fixture.componentInstance.toggle();
    fixture.detectChanges();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    expect(fixture.componentInstance.open()).toBe(false);
    await fixture.componentInstance.toggle();
    fixture.detectChanges();
    document.body.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.open()).toBe(false);
    await fixture.componentInstance.toggle();
    current.set(null);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=dialog]')).toBeNull();
    expect(TestBed.inject(CurrentProfileService).profile()).toBeNull();
  });
  it('never displays a late response for another signed-in identity', async () => {
    const fixture = await header();
    const pending = new Subject<CurrentProfile>();
    api.profile.mockReturnValue(pending);
    const loading = fixture.componentInstance.toggle();
    current.set({ userId: 2, email: 'next@example.com', displayName: 'Next' });
    await fixture.whenStable();
    pending.next(profile);
    pending.complete();
    await loading;
    fixture.detectChanges();
    expect(TestBed.inject(CurrentProfileService).profile()).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('current@example.com');
  });
});
