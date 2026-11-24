import { DestroyRef, inject, Injectable, signal } from '@angular/core';

export type NotificationSeverity = 'success' | 'error' | 'info' | 'warning';

export interface NotificationItem {
  readonly id: number;
  readonly message: string;
  readonly severity: NotificationSeverity;
}

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly entries = signal<readonly NotificationItem[]>([]);
  private readonly timers = new Map<number, ReturnType<typeof setTimeout>>();
  private nextId = 1;
  private destroyed = false;
  readonly items = this.entries.asReadonly();

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      for (const timer of this.timers.values()) clearTimeout(timer);
      this.timers.clear();
      this.entries.set([]);
    });
  }

  show(message: string, severity: NotificationSeverity = 'info'): number {
    const id = this.nextId++;
    if (this.destroyed) return id;
    this.entries.update((items) => [...items, { id, message, severity }]);
    this.timers.set(
      id,
      setTimeout(() => this.dismiss(id), 15_000),
    );
    return id;
  }

  dismiss(id: number): void {
    const timer = this.timers.get(id);
    if (timer !== undefined) clearTimeout(timer);
    this.timers.delete(id);
    this.entries.update((items) => items.filter((item) => item.id !== id));
  }
}
