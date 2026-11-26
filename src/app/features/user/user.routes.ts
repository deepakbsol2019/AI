import { Routes } from '@angular/router';
import { UserComponent } from './user.component';
import { authGuard } from './security/auth.guard';
import { managementCapabilityGuard } from './security/management-capability.guard';
import type { UserManagementComponent } from './pages/user-management.component';

export const USER_ROUTES: Routes = [
  {
    path: '',
    component: UserComponent,
    children: [
      {
        path: 'users/new',
        title: 'Create User',
        canMatch: [authGuard, managementCapabilityGuard('canCreateUsers')],
        canDeactivate: [(component: UserManagementComponent) => component.canLeave()],
        data: { userPage: 'create' },
        loadComponent: () =>
          import('./pages/user-management.component').then((m) => m.UserManagementComponent),
      },
      {
        path: 'users/:id/delete',
        title: 'Delete User Confirmation',
        canMatch: [authGuard, managementCapabilityGuard('canViewUsers')],
        data: { userPage: 'delete' },
        loadComponent: () =>
          import('./pages/user-management.component').then((m) => m.UserManagementComponent),
      },
      {
        path: 'users/:id',
        title: 'User Details',
        canMatch: [authGuard, managementCapabilityGuard('canViewUsers')],
        canDeactivate: [(component: UserManagementComponent) => component.canLeave()],
        data: { userPage: 'details' },
        loadComponent: () =>
          import('./pages/user-management.component').then((m) => m.UserManagementComponent),
      },
      {
        path: 'users',
        title: 'User Management',
        canMatch: [authGuard, managementCapabilityGuard('canViewUsers')],
        data: { userPage: 'list' },
        loadComponent: () =>
          import('./pages/user-management.component').then((m) => m.UserManagementComponent),
      },
      {
        path: 'roles',
        title: 'Role Management',
        canMatch: [authGuard, managementCapabilityGuard('canManageRoles')],
        loadComponent: () =>
          import('./pages/role-management.component').then((m) => m.RoleManagementComponent),
      },
      {
        path: 'login',
        title: 'Login · Bookhaven',
        loadComponent: () => import('./pages/login.component').then((m) => m.LoginComponent),
      },
      {
        path: 'register',
        title: 'Registration · Bookhaven',
        loadComponent: () => import('./pages/register.component').then((m) => m.RegisterComponent),
      },
      {
        path: 'password/forgot',
        title: 'Forgot password · Bookhaven',
        loadComponent: () => import('./pages/password.component').then((m) => m.PasswordComponent),
        data: { mode: 'forgot' },
      },
      {
        path: 'password/setup',
        title: 'Set password · Bookhaven',
        loadComponent: () => import('./pages/password.component').then((m) => m.PasswordComponent),
        data: { mode: 'setup' },
      },
      {
        path: 'password/reset',
        title: 'Reset password · Bookhaven',
        loadComponent: () => import('./pages/password.component').then((m) => m.PasswordComponent),
        data: { mode: 'reset' },
      },
      {
        path: 'enroll',
        title: 'SSO enrollment · Bookhaven',
        loadComponent: () => import('./pages/login.component').then((m) => m.LoginComponent),
        data: { enrollment: true },
      },
      {
        path: 'callback',
        title: 'Completing login · Bookhaven',
        loadComponent: () => import('./pages/callback.component').then((m) => m.CallbackComponent),
      },
      {
        path: 'error',
        title: 'Login unavailable · Bookhaven',
        loadComponent: () =>
          import('./pages/auth-error.component').then((m) => m.AuthErrorComponent),
      },
      { path: 'provider-error', redirectTo: 'error' },
      { path: '', pathMatch: 'full', redirectTo: 'login' },
      { path: '**', redirectTo: 'login' },
    ],
  },
];
