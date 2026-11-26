import { DestroyRef, inject, Injectable } from '@angular/core';
import { Subject } from 'rxjs';
import { API_CONFIG } from '../../../core/configuration/api.config';
import { SessionEvent } from '../models/auth.models';

/** Only ephemeral messages; neither credential type is written to browser storage. */
@Injectable({ providedIn: 'root' })
export class AuthTabCoordinator {
  private readonly name = `bookhaven-session:${inject(API_CONFIG).baseUrl}`;
  private readonly channel =
    typeof BroadcastChannel === 'function' ? new BroadcastChannel(this.name) : null;
  private readonly incoming = new Subject<SessionEvent>();
  readonly events = this.incoming.asObservable();

  constructor() {
    if (this.channel)
      this.channel.onmessage = (event: MessageEvent<unknown>) => {
        const data = event.data as Partial<SessionEvent> | null;
        if (data?.kind === 'clear' || (data?.kind === 'session' && 'response' in data)) {
          this.incoming.next(data as SessionEvent);
        }
      };
    inject(DestroyRef).onDestroy(() => {
      this.channel?.close();
      this.incoming.complete();
    });
  }
  publish(event: SessionEvent): void {
    this.channel?.postMessage(event);
  }
  exclusive<T>(work: () => Promise<T>, signal?: AbortSignal): Promise<T> {
    if (!navigator.locks?.request) {
      return Promise.reject(
        new Error(
          'Secure sign-in requires a browser with Web Locks support. Use a current browser over HTTPS or localhost.',
        ),
      );
    }
    return navigator.locks.request(
      this.name,
      { mode: 'exclusive', ...(signal ? { signal } : {}) },
      work,
    );
  }
}
