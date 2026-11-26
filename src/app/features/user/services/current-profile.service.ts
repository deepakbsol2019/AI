import { DestroyRef, effect, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  CurrentProfile,
  UserManagementApiService,
} from '../data-access/user-management-api.service';
import { AuthSessionService } from './auth-session.service';
@Injectable({ providedIn: 'root' })
export class CurrentProfileService {
  private readonly session = inject(AuthSessionService);
  private readonly api = inject(UserManagementApiService);
  private readonly destroy = inject(DestroyRef);
  private identity: string | null = null;
  private generation = 0;
  readonly profile = signal<CurrentProfile | null>(null);
  constructor() {
    effect(() => {
      const identity = this.session.user()?.userId?.toString() ?? null;
      if (this.identity !== identity) {
        this.identity = identity;
        this.generation++;
        this.profile.set(null);
      }
    });
  }
  async refresh() {
    const identity = this.session.user()?.userId?.toString();
    this.identity = identity ?? null;
    this.profile.set(null);
    const generation = ++this.generation;
    if (!identity) return;
    const value = await firstValueFrom(this.api.profile().pipe(takeUntilDestroyed(this.destroy)));
    if (generation === this.generation && this.session.user()?.userId?.toString() === identity)
      this.profile.set(value);
  }
}
