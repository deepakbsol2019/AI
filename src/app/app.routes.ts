import { Routes } from '@angular/router';
import { authGuard } from './features/user/security/auth.guard';

export const routes: Routes = [
  {
    path: 'user',
    loadChildren: () => import('./features/user/user.routes').then((m) => m.USER_ROUTES),
  },
  {
    path: '',
    canMatch: [authGuard],
    loadChildren: () => import('./features/books/books.routes').then((m) => m.BOOK_ROUTES),
  },
  { path: '**', redirectTo: '' },
];
