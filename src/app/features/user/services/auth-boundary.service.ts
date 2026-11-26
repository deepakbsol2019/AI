import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';

/** Cancels tenant-specific browser work without a dependency from authentication to books. */
@Injectable({ providedIn: 'root' })
export class AuthBoundaryService {
  private readonly resetSubject = new Subject<void>();
  readonly resets = this.resetSubject.asObservable();
  reset(): void {
    this.resetSubject.next();
  }
}
