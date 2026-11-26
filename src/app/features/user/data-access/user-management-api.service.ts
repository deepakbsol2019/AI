import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { API_CONFIG } from '../../../core/configuration/api.config';
export interface UserRole {
  roleId: number;
  name: string;
  code: string;
  system: boolean;
  status: string;
  description: string | null;
  permissions: string[];
}
export interface ManagedUser {
  userId: number;
  username: string;
  displayName: string;
  tenantName: string;
  tenantId: number;
  tenantType: string;
  roles: UserRole[];
  status: string;
  createdAt: string | null;
  lastLogin: string | null;
  version: string;
  canEdit: boolean;
  canSoftDelete: boolean;
  canHardDelete: boolean;
  canActivate: boolean;
  canAssignRoles: boolean;
  pendingJobId: number | null;
  deleted?: boolean;
  deletedAt?: string | null;
}
export interface UserPage {
  users: ManagedUser[];
  total: number;
  page: number;
  size: number;
  superUser: boolean;
}
export interface UserDetails {
  user: ManagedUser;
  email: string;
  emailVerifiedAt: string | null;
  updatedAt: string | null;
  tenantStatus: string;
  membershipStatus: string;
  effectivePermissions: { code: string; name: string; sourceRoles: string[] }[];
  directGrantsSupported: boolean;
}
export interface CurrentProfile {
  username: string;
  displayName: string;
  email: string;
  tenantName: string;
  tenantType: string;
  roles: string[];
  status: string;
  createdAt: string | null;
  lastLogin: string | null;
}
export interface DeletionPreview {
  userId: number;
  tenantId: number;
  tenantName: string;
  scope: string;
  affectedUsers: number;
  permanent: boolean;
  preservesFiles: boolean;
  confirmation: string;
  version: string;
  explanation: string;
}
export interface DeletionJob {
  jobId: number;
  tenantId: number;
  userId: number;
  scope: string;
  status: string;
  databasePurged: boolean;
  attempts: number;
  error: string | null;
  updatedAt: string;
}
export interface CreateUserOptions {
  tenantId: number;
  tenantName: string;
  tenantType: string;
  loginPolicy: string;
}
export interface CreatedUser {
  userId: number;
  username: string;
  displayName: string;
  tenantName: string;
  tenantType: string;
  status: string;
  message: string;
}
@Injectable({ providedIn: 'root' })
export class UserManagementApiService {
  private readonly http = inject(HttpClient);
  private readonly userBase = inject(API_CONFIG).baseUrl.replace(/\/$/, '') + '/user';
  private readonly base = this.userBase + '/users';
  profile() {
    return this.http.get<CurrentProfile>(this.userBase + '/profile');
  }
  createOptions() {
    return this.http.get<CreateUserOptions>(this.base + '/create-options');
  }
  create(email: string, displayName: string) {
    return this.http.post<CreatedUser>(this.base, { email, displayName });
  }
  list(search: string, status: string, page: number, size: number) {
    return this.http.get<UserPage>(this.base, {
      params: new HttpParams()
        .set('search', search)
        .set('status', status)
        .set('page', page)
        .set('size', size),
    });
  }
  details(id: number) {
    return this.http.get<UserDetails>(`${this.base}/${id}`);
  }
  edit(id: number, displayName: string, version: string) {
    return this.http.put<UserDetails>(`${this.base}/${id}`, { displayName, version });
  }
  activate(id: number, version: string, resumeTenant: boolean) {
    return this.http.post<UserDetails>(`${this.base}/${id}/activate`, { version, resumeTenant });
  }
  roles(id: number) {
    return this.http.get<UserRole[]>(`${this.base}/${id}/assignable-roles`);
  }
  assign(id: number, roleIds: number[], version: string) {
    return this.http.put<UserDetails>(`${this.base}/${id}/roles`, { roleIds, version });
  }
  preview(id: number, hard: boolean) {
    return this.http.get<DeletionPreview>(`${this.base}/${id}/deletion-preview`, {
      params: { hard },
    });
  }
  soft(id: number, input: DeletionPreview, confirmation: string) {
    return this.http.post<void>(`${this.base}/${id}/soft-delete`, {
      version: input.version,
      scope: input.scope,
      affectedUsers: input.affectedUsers,
      confirmation,
    });
  }
  hard(id: number, input: DeletionPreview, confirmation: string) {
    return this.http.post<DeletionJob>(`${this.base}/${id}/hard-delete`, {
      version: input.version,
      scope: input.scope,
      affectedUsers: input.affectedUsers,
      confirmation,
    });
  }
  job(id: number) {
    return this.http.get<DeletionJob>(`${this.base}/jobs/${id}`);
  }
  jobs() {
    return this.http.get<DeletionJob[]>(`${this.base}/jobs`);
  }
  retry(id: number) {
    return this.http.post<DeletionJob>(`${this.base}/jobs/${id}/retry`, {});
  }
}
