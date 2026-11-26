import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { API_CONFIG } from '../../../core/configuration/api.config';

export interface ManagedRole {
  roleId: number;
  tenantId: number;
  name: string;
  description: string | null;
  systemRole: string | null;
  version: number;
  permissionIds: number[];
  editable: boolean;
}
export interface RolePermission {
  permissionId: number;
  code: string;
  name: string;
  prerequisiteId: number | null;
  routes: { method: string; path: string }[];
}
export interface RoleCatalog {
  tenantId: number;
  superUser: boolean;
  canCreate: boolean;
  canEdit: boolean;
  canAssign: boolean;
  permissions: RolePermission[];
}
export interface RoleWrite {
  tenantId: number;
  name: string;
  description: string;
  permissionIds: number[];
  version?: number;
  systemRole?: string;
}
@Injectable({ providedIn: 'root' })
export class RoleApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_CONFIG).baseUrl.replace(/\/$/, '') + '/user/roles';
  organizations() {
    return this.http.get<{ tenantId: number; name: string }[]>(`${this.base}/organizations`);
  }
  list(tenantId: number) {
    return this.http.get<ManagedRole[]>(this.base, {
      params: new HttpParams().set('tenantId', tenantId),
    });
  }
  catalog(tenantId: number) {
    return this.http.get<RoleCatalog>(`${this.base}/catalog`, {
      params: new HttpParams().set('tenantId', tenantId),
    });
  }
  create(input: RoleWrite) {
    return this.http.post<ManagedRole>(this.base, input);
  }
  update(id: number, input: RoleWrite) {
    return this.http.put<ManagedRole>(`${this.base}/${id}`, input);
  }
}
