import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, takeUntil } from 'rxjs';
import { API_CONFIG } from '../../../../core/configuration/api.config';
import { apiErrorMessage } from '../../../../shared/utils/api-error';
import { AuthBoundaryService } from '../../../user/services/auth-boundary.service';
import { FileDeleteAccessService } from './file-delete-access.service';

@Component({
  selector: 'app-delete-upload',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      #deleteTrigger
      type="button"
      class="btn btn-danger btn-sm"
      [disabled]="pending() || disabled() || !access.canDelete(kind(), fileId())"
      [attr.aria-label]="'Delete: ' + filename()"
      [title]="
        access.canDelete(kind(), fileId())
          ? 'Delete this file'
          : 'Deletion is unavailable for this file with your current access.'
      "
      (click)="openConfirmation()"
    >
      {{ pending() ? 'Deleting…' : 'Delete' }}
    </button>
    @if (confirming()) {
      <dialog
        #confirmationDialog
        class="confirmation-view"
        role="alertdialog"
        aria-modal="true"
        [attr.aria-labelledby]="titleId"
        [attr.aria-describedby]="detailId"
        [attr.aria-busy]="pending()"
        tabindex="-1"
        (cancel)="$event.preventDefault(); cancel()"
        (keydown)="trapFocus($event)"
      >
        <h2 [id]="titleId">Delete this file?</h2>
        <div [id]="detailId">
          <p>{{ filename() }}</p>
          <p>
            The stored file and its associated imported data and generated documents will be
            removed.
          </p>
        </div>
        @if (error()) {
          <p class="text-danger" role="alert">{{ error() }}</p>
        }
        <div class="confirmation-actions">
          <button
            type="button"
            class="btn btn-secondary cancel-file-delete"
            [disabled]="pending()"
            (click)="cancel()"
          >
            Cancel
          </button>
          <button
            type="button"
            class="btn btn-danger confirm-file-delete"
            [disabled]="pending() || disabled() || !access.canDelete(kind(), fileId())"
            (click)="remove()"
          >
            {{ pending() ? 'Deleting…' : 'Confirm Delete' }}
          </button>
        </div>
      </dialog>
    }
    @if (error() && !confirming()) {
      <p class="text-danger small mt-1 mb-0" role="alert">{{ error() }}</p>
    }
  `,
  styles: `
    :host {
      display: inline-block;
      min-width: 0;
      max-width: 100%;
    }
    .confirmation-view {
      position: fixed;
      inset: 0;
      margin: auto;
      width: min(32rem, calc(100vw - 2rem));
      max-height: calc(100dvh - 2rem);
      overflow: auto;
      border: 1px solid #d1d5db;
      border-radius: 0.8rem;
      padding: 1.4rem;
      background: white;
      color: #111827;
      white-space: normal;
      overflow-wrap: anywhere;
      box-shadow: 0 1rem 3rem #0003;
    }
    .confirmation-view::backdrop {
      background: #10243b99;
    }
    h2 {
      font-size: 1.3rem;
    }
    .confirmation-actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: 0.7rem;
    }
  `,
})
export class DeleteUploadComponent {
  private static nextId = 0;
  readonly titleId = `file-delete-title-${++DeleteUploadComponent.nextId}`;
  readonly detailId = `${this.titleId}-details`;
  readonly kind = input.required<'books' | 'spreadsheets' | 'uploads'>();
  readonly fileId = input.required<number>();
  readonly filename = input('file');
  readonly disabled = input(false);
  readonly deleted = output<void>();
  readonly pending = signal(false);
  readonly error = signal('');
  readonly confirming = signal(false);
  readonly access = inject(FileDeleteAccessService);
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_CONFIG).baseUrl.replace(/\/$/, '');
  private readonly destroy = inject(DestroyRef);
  private readonly boundary = inject(AuthBoundaryService);
  private readonly injector = inject(Injector);
  private readonly trigger = viewChild<ElementRef<HTMLButtonElement>>('deleteTrigger');
  private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('confirmationDialog');
  private readonly targetChanges = new Subject<void>();
  private target = '';
  private generation = 0;
  constructor() {
    effect(() => {
      const target = `${this.kind()}:${this.fileId()}`;
      if (this.target && this.target !== target) this.reset();
      this.target = target;
      // Processing receipts are disabled by the parent. Fetch a fresh hint once they finish,
      // rather than permanently caching the backend's temporary active-upload denial.
      if (!this.disabled()) this.access.ensure(this.kind(), this.fileId());
    });
    this.boundary.resets.pipe(takeUntilDestroyed()).subscribe(() => {
      this.reset();
    });
    this.destroy.onDestroy(() => this.targetChanges.complete());
  }
  openConfirmation(): void {
    if (this.pending() || this.disabled() || !this.access.canDelete(this.kind(), this.fileId()))
      return;
    this.confirming.set(true);
    afterNextRender(
      () => {
        const dialog = this.dialog()?.nativeElement;
        if (!dialog || !this.confirming()) return;
        if (typeof dialog.showModal === 'function') dialog.showModal();
        else dialog.setAttribute('open', '');
        dialog.querySelector<HTMLButtonElement>('.cancel-file-delete')?.focus();
      },
      { injector: this.injector },
    );
  }
  remove(): void {
    if (
      !this.confirming() ||
      this.pending() ||
      this.disabled() ||
      !this.access.canDelete(this.kind(), this.fileId())
    )
      return;
    const generation = ++this.generation;
    this.pending.set(true);
    this.error.set('');
    this.http
      .delete<void>(`${this.base}/${this.kind()}/${this.fileId()}`)
      .pipe(
        takeUntil(this.targetChanges),
        takeUntil(this.boundary.resets),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: () => {
          if (generation !== this.generation) return;
          this.pending.set(false);
          this.closeDialog();
          this.confirming.set(false);
          this.deleted.emit();
        },
        error: async (error) => {
          const message = await apiErrorMessage(error);
          if (generation !== this.generation || this.destroy.destroyed) return;
          this.pending.set(false);
          this.error.set(message);
        },
      });
  }
  cancel(): void {
    if (this.pending()) return;
    this.closeDialog();
    this.confirming.set(false);
    this.error.set('');
    this.trigger()?.nativeElement.focus({ preventScroll: true });
  }
  trapFocus(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.cancel();
      return;
    }
    if (event.key !== 'Tab') return;
    const dialog = this.dialog()?.nativeElement;
    if (!dialog) return;
    const buttons = Array.from(dialog.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'));
    const first = buttons[0],
      last = buttons.at(-1),
      active = dialog.ownerDocument.activeElement;
    if (!first) {
      event.preventDefault();
      dialog.focus();
    } else if (event.shiftKey && active === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }
  private reset(): void {
    this.generation++;
    this.targetChanges.next();
    this.closeDialog();
    this.pending.set(false);
    this.error.set('');
    this.confirming.set(false);
  }
  private closeDialog(): void {
    const dialog = this.dialog()?.nativeElement;
    if (typeof dialog?.close === 'function') dialog.close();
    else dialog?.removeAttribute('open');
  }
}
