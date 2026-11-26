import { DestroyRef, Injectable, effect, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { API_CONFIG } from '../../../core/configuration/api.config';
import { AuthSessionService } from './auth-session.service';
import { apiErrorMessage } from '../../../shared/utils/api-error';

export interface ManagementCapabilities {
  superUser: boolean;
  canViewUsers: boolean;
  canCreateUsers: boolean;
  canManageRoles: boolean;
  tenantId: number;
  tenantName: string;
  tenantType: string;
}

/** Navigation hints come from the authenticated server context; APIs remain authoritative. */
@Injectable({ providedIn: 'root' })
export class NavigationAccessService {
  private readonly session = inject(AuthSessionService);
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  private readonly endpoint =
    inject(API_CONFIG).baseUrl.replace(/\/$/, '') + '/user/users/capabilities';
  private identity: string | null = null;
  private generation = 0;
  private pending: Promise<ManagementCapabilities | null> | null = null;
  readonly capabilities = signal<ManagementCapabilities | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      const identity = this.session.user()?.userId?.toString() ?? null;
      if (this.identity !== identity) {
        this.identity = identity;
        this.generation++;
        this.pending = null;
        this.capabilities.set(null);
        this.loading.set(false);
        this.error.set(null);
      }
    });
  }

  ensure(): Promise<ManagementCapabilities | null> {
    const identity = this.session.user()?.userId?.toString() ?? null;
    if (!identity) return Promise.resolve(null);
    if (this.identity === identity && this.capabilities())
      return Promise.resolve(this.capabilities());
    if (this.identity === identity && this.pending) return this.pending;
    return this.refresh();
  }

  refresh(): Promise<ManagementCapabilities | null> {
    const identity = this.session.user()?.userId?.toString() ?? null;
    this.identity = identity;
    const generation = ++this.generation;
    this.capabilities.set(null);
    this.error.set(null);
    if (!identity) {
      this.pending = null;
      this.loading.set(false);
      return Promise.resolve(null);
    }
    this.loading.set(true);
    const work = firstValueFrom(
      this.http.get<ManagementCapabilities>(this.endpoint).pipe(takeUntilDestroyed(this.destroy)),
    )
      .then((access) => {
        if (generation !== this.generation || this.session.user()?.userId?.toString() !== identity)
          return null;
        this.capabilities.set(access);
        return access;
      })
      .catch(async (error: unknown) => {
        if (generation === this.generation && this.session.user()?.userId?.toString() === identity)
          this.error.set(await apiErrorMessage(error));
        return null;
      })
      .finally(() => {
        if (generation === this.generation) {
          this.pending = null;
          this.loading.set(false);
        }
      });
    this.pending = work;
    return work;
  }
}
