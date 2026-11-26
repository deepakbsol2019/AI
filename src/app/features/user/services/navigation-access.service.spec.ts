import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthSessionService } from './auth-session.service';
import { NavigationAccessService } from './navigation-access.service';
import { managementCapabilityGuard } from '../security/management-capability.guard';

describe('Authenticated navigation capabilities', () => {
  const user = signal<{ userId: number } | null>({ userId: 1 });
  let token: ReturnType<typeof vi.fn<() => Promise<string>>>;
  const allowed = {
    superUser: false,
    canViewUsers: true,
    canCreateUsers: false,
    canManageRoles: false,
    tenantId: 4,
    tenantName: 'Tenant',
    tenantType: 'ORGANIZATION',
  };
  beforeEach(() => {
    user.set({ userId: 1 });
    token = vi.fn(() => Promise.resolve('test-access'));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthSessionService, useValue: { user, token } },
      ],
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('loads capabilities only from authenticated context and caches them for the current identity', async () => {
    const access = TestBed.inject(NavigationAccessService);
    const loading = access.ensure();
    TestBed.inject(HttpTestingController)
      .expectOne(
        (request) =>
          request.url.endsWith('/user/users/capabilities') && request.params.keys().length === 0,
      )
      .flush(allowed);
    expect(await loading).toEqual(allowed);
    expect(await access.ensure()).toEqual(allowed);
    expect(access.capabilities()?.canCreateUsers).toBe(false);
  });
  it('clears grants and rejects stale responses after identity changes or logout', async () => {
    const access = TestBed.inject(NavigationAccessService);
    const loading = access.ensure();
    const request = TestBed.inject(HttpTestingController).expectOne((req) =>
      req.url.endsWith('/user/users/capabilities'),
    );
    user.set({ userId: 2 });
    TestBed.tick();
    request.flush(allowed);
    expect(await loading).toBeNull();
    expect(access.capabilities()).toBeNull();
    user.set(null);
    TestBed.tick();
    expect(await access.ensure()).toBeNull();
  });
  it('protects direct Create User navigation independently from user viewing and role management', async () => {
    const result = TestBed.runInInjectionContext(
      () =>
        managementCapabilityGuard('canCreateUsers')(
          {} as never,
          [],
          {} as never,
        ) as Promise<unknown>,
    );
    await Promise.resolve();
    TestBed.inject(HttpTestingController)
      .expectOne((req) => req.url.endsWith('/user/users/capabilities'))
      .flush(allowed);
    expect(TestBed.inject(Router).serializeUrl((await result) as never)).toBe('/');
  });
  it('waits for a pending authentication bootstrap before checking direct-route capabilities', async () => {
    user.set(null);
    let completeBootstrap!: (value: string) => void;
    token.mockImplementation(
      () =>
        new Promise<string>((resolve) => {
          completeBootstrap = resolve;
        }),
    );
    const result = TestBed.runInInjectionContext(
      () =>
        managementCapabilityGuard('canViewUsers')({} as never, [], {} as never) as Promise<unknown>,
    );
    let completed = false;
    void result.then(() => {
      completed = true;
    });
    await Promise.resolve();
    expect(token).toHaveBeenCalledOnce();
    expect(completed).toBe(false);
    TestBed.inject(HttpTestingController).expectNone((req) =>
      req.url.endsWith('/user/users/capabilities'),
    );
    user.set({ userId: 1 });
    completeBootstrap('bootstrapped-access');
    await Promise.resolve();
    TestBed.tick();
    TestBed.inject(HttpTestingController)
      .expectOne((req) => req.url.endsWith('/user/users/capabilities'))
      .flush(allowed);
    expect(await result).toBe(true);
  });
  it('returns to login when authentication bootstrap fails without loading capabilities', async () => {
    user.set(null);
    token.mockRejectedValueOnce(new Error('No valid session'));
    const result = TestBed.runInInjectionContext(
      () =>
        managementCapabilityGuard('canViewUsers')({} as never, [], {} as never) as Promise<unknown>,
    );
    expect(TestBed.inject(Router).serializeUrl((await result) as never)).toBe('/user/login');
    TestBed.inject(HttpTestingController).expectNone((req) =>
      req.url.endsWith('/user/users/capabilities'),
    );
  });
});
