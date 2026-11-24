import { Routes } from '@angular/router';

export const BOOK_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Bookhaven · Your digital library',
    loadComponent: () => import('./pages/home/home.component').then((m) => m.HomeComponent),
  },
];
