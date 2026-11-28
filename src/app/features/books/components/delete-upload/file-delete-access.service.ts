import { DestroyRef, Injectable, effect, inject, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { takeUntil } from 'rxjs';
import { API_CONFIG } from '../../../../core/configuration/api.config';
import { AuthSessionService } from '../../../user/services/auth-session.service';
import { AuthBoundaryService } from '../../../user/services/auth-boundary.service';

export type FileKind = 'books' | 'spreadsheets' | 'uploads';

/** Display hints are batched by table and identity. DELETE always repeats the backend check. */
@Injectable({ providedIn: 'root' })
export class FileDeleteAccessService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(AuthSessionService);
  private readonly boundary = inject(AuthBoundaryService);
  private readonly destroy = inject(DestroyRef);
  private readonly base = inject(API_CONFIG).baseUrl.replace(/\/$/, '');
  private readonly values = signal<Record<string, boolean>>({});
  private readonly requested = new Set<string>();
  private readonly queued = new Map<FileKind, Set<number>>();
  private identity = '';
  private generation = 0;
  private scheduled = false;
  constructor() {
    effect(() => {
      const user = this.session.user();
      const identity = user ? `${user.tenantId}:${user.userId}` : '';
      if (identity !== this.identity) {
        this.reset();
        this.identity = identity;
      }
    });
    this.boundary.resets.pipe(takeUntilDestroyed()).subscribe(() => this.reset());
  }
  canDelete(kind: FileKind, id: number): boolean {
    const user = this.session.user();
    return (
      !!user &&
      `${user.tenantId}:${user.userId}` === this.identity &&
      Number.isSafeInteger(id) &&
      id > 0 &&
      this.values()[`${kind}:${id}`] === true
    );
  }
  ensure(kind: FileKind, id: number): void {
    const user = this.session.user();
    if (!user || !Number.isSafeInteger(id) || id <= 0) return;
    const identity = `${user.tenantId}:${user.userId}`;
    if (identity !== this.identity) {
      this.reset();
      this.identity = identity;
    }
    const key = `${kind}:${id}`;
    if (this.requested.has(key)) return;
    this.requested.add(key);
    const ids = this.queued.get(kind) ?? new Set<number>();
    ids.add(id);
    this.queued.set(kind, ids);
    if (this.scheduled) return;
    this.scheduled = true;
    queueMicrotask(() => this.flush());
  }
  private flush(): void {
    this.scheduled = false;
    const user = this.session.user();
    if (!user || `${user.tenantId}:${user.userId}` !== this.identity) {
      this.reset();
      this.identity = user ? `${user.tenantId}:${user.userId}` : '';
      return;
    }
    const generation = this.generation;
    const queued = [...this.queued];
    this.queued.clear();
    for (const [kind, group] of queued) {
      const ids = [...group];
      for (let start = 0; start < ids.length; start += 100) {
        const batch = ids.slice(start, start + 100);
        let params = new HttpParams().set('kind', kind);
        for (const id of batch) params = params.append('ids', id);
        this.http
          .get<Record<string, boolean>>(`${this.base}/files/delete-capabilities`, { params })
          .pipe(takeUntil(this.boundary.resets), takeUntilDestroyed(this.destroy))
          .subscribe({
            next: (result) => {
              if (generation !== this.generation) return;
              this.values.update((values) => ({
                ...values,
                ...Object.fromEntries(batch.map((id) => [`${kind}:${id}`, result[id] === true])),
              }));
            },
            error: () => {
              if (generation !== this.generation) return;
              batch.forEach((id) => this.requested.delete(`${kind}:${id}`));
            },
          });
      }
    }
  }
  private reset(): void {
    this.generation++;
    this.values.set({});
    this.requested.clear();
    this.queued.clear();
  }
}
