import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { AuthSessionService } from '../services/auth-session.service';
import {
  NavigationAccessService,
  ManagementCapabilities,
} from '../services/navigation-access.service';

type ManagementCapability = keyof Pick<
  ManagementCapabilities,
  'canViewUsers' | 'canCreateUsers' | 'canManageRoles'
>;

export function managementCapabilityGuard(capability: ManagementCapability): CanMatchFn {
  return async () => {
    const access = inject(NavigationAccessService);
    const router = inject(Router);
    const session = inject(AuthSessionService);
    try {
      // CanMatch guards may start together. Await the shared authentication bootstrap
      // before checking tenant capabilities on a direct URL or browser refresh.
      await session.token();
      return (await access.ensure())?.[capability] === true || router.createUrlTree(['/']);
    } catch {
      return router.createUrlTree(['/user/login']);
    }
  };
}
