import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadChildren: () => import('./features/books/books.routes').then((m) => m.BOOK_ROUTES),
  },
  { path: '**', redirectTo: '' },
];
