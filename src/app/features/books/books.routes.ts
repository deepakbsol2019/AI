import { Routes } from '@angular/router';

export const BOOK_ROUTES: Routes = [
  {
    path: 'spreadsheets/:fileId/view',
    title: 'Bookhaven · Spreadsheet viewer',
    loadComponent: () =>
      import('./components/spreadsheet-view/spreadsheet-page.component').then(
        (m) => m.SpreadsheetPageComponent,
      ),
  },
  {
    path: '',
    pathMatch: 'full',
    title: 'Bookhaven · Your digital library',
    loadComponent: () => import('./pages/home/home.component').then((m) => m.HomeComponent),
  },
];
