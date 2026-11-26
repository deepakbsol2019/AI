import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { OwnUserDetailsComponent } from './own-user-details.component';
import {
  UserManagementApiService,
  OwnPermissions,
} from '../data-access/user-management-api.service';

describe('Your information and permissions popup', () => {
  const profile = {
    username: 'self@example.com',
    displayName: 'Your Name',
    email: 'self@example.com',
    tenantId: 5,
    tenantName: 'Own Tenant',
    tenantType: 'ORGANIZATION',
    roles: ['Reader', 'Reviewer'],
    status: 'ACTIVE',
    createdAt: null,
    lastLogin: null,
  };
  const permissions: OwnPermissions = {
    username: profile.username,
    permissions: [
      {
        permissionId: 1,
        name: 'Read books',
        description: 'Read the tenant catalogue',
        code: 'BOOKS.VIEW',
        component: 'BOOKS',
        kind: 'VIEW',
        sourceRoles: ['Reader'],
        routes: [
          { service: 'BOOK_SEARCH', method: 'GET', url: '/api/search/books', operations: ['Read'] },
        ],
      },
    ],
  };
  let api: { ownDetails: ReturnType<typeof vi.fn>; ownPermissions: ReturnType<typeof vi.fn> };
  beforeEach(() => {
    api = { ownDetails: vi.fn(() => of(profile)), ownPermissions: vi.fn(() => of(permissions)) };
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: UserManagementApiService, useValue: api }],
    });
  });
  async function page() {
    const fixture = TestBed.createComponent(OwnUserDetailsComponent);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }
  it('loads only the authenticated self endpoint and shows ordinary information with explicit return routes', async () => {
    const fixture = await page(),
      text = fixture.nativeElement.textContent;
    expect(api.ownDetails).toHaveBeenCalledWith();
    expect(text).toContain('Your Name');
    expect(text).toContain('self@example.com');
    expect(text).toContain('Own Tenant');
    expect(text).toContain('Reviewer');
    expect(fixture.nativeElement.querySelector('table')?.textContent).not.toContain('permissions');
    expect(
      fixture.nativeElement.querySelector('a[aria-label^="Close"]')?.getAttribute('href'),
    ).toBe('/');
    expect(fixture.nativeElement.querySelector('a[href="/?profile=open"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('input')).toBeNull();
  });
  it('shows actual permission metadata in a modal and closes back to the information page', async () => {
    const fixture = await page(),
      component = fixture.componentInstance;
    component.openPermissions();
    await fixture.whenStable();
    fixture.detectChanges();
    const modal = fixture.nativeElement.querySelector('[role="dialog"]');
    expect(modal).not.toBeNull();
    expect(modal.textContent).toContain('Username: self@example.com');
    expect(modal.textContent).toContain('Read the tenant catalogue');
    expect(modal.textContent).toContain('BOOK_SEARCH');
    expect(modal.textContent).toContain('/api/search/books');
    expect(modal.textContent).toContain('Read');
    expect(modal.textContent).toContain('GET');
    component.keyboard(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="dialog"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.profile-information')).not.toBeNull();
  });
  it('keeps permission loading and failure inside the modal and ignores a late response after close', async () => {
    const fixture = await page(),
      component = fixture.componentInstance,
      pending = new Subject<OwnPermissions>();
    api.ownPermissions.mockReturnValue(pending);
    component.openPermissions();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="dialog"]')?.textContent).toContain(
      'Loading your permissions',
    );
    component.closePermissions();
    pending.next(permissions);
    pending.complete();
    await fixture.whenStable();
    expect(component.permissions()).toBeNull();
    api.ownPermissions.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 503,
            error: { detail: 'Permissions temporarily unavailable' },
          }),
      ),
    );
    component.openPermissions();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="dialog"]')?.textContent).toContain(
      'Permissions temporarily unavailable',
    );
    expect(fixture.nativeElement.querySelector('.profile-information')).not.toBeNull();
  });
});
