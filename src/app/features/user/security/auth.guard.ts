import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { catchError, from, map, of } from 'rxjs';
import { AuthSessionService } from '../services/auth-session.service';

export const authGuard: CanMatchFn = () => {
  const session = inject(AuthSessionService);
  const router = inject(Router);
  return from(session.token()).pipe(
    map(() => true),
    catchError(() => of(router.parseUrl('/user/login'))),
  );
};
