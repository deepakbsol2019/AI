import { DOCUMENT } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  signal,
} from '@angular/core';
import { NotificationService } from '../services/notification.service';
import { IconComponent } from './icon.component';

/** Add one outlet to the app and one inside each native dialog's content. */
@Component({
  selector: 'app-notifications',
  imports: [IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (active() && notifications.items().length) {
      <section
        class="notification-stack"
        aria-label="Notifications"
        aria-live="polite"
        [style.top.px]="top()"
        [style.max-height]="'calc(100dvh - ' + top() + 'px - 1rem)'"
      >
        @for (item of notifications.items(); track item.id) {
          <article
            class="notification-card"
            [class.notification-error]="item.severity === 'error'"
            [class.notification-success]="item.severity === 'success'"
            [class.notification-warning]="item.severity === 'warning'"
            [attr.role]="item.severity === 'error' ? 'alert' : 'status'"
            [attr.aria-live]="item.severity === 'error' ? 'assertive' : 'polite'"
            aria-atomic="true"
          >
            <span class="notification-mark" aria-hidden="true">
              <app-icon [name]="item.severity === 'success' ? 'check' : 'info'" />
            </span>
            <div class="notification-content">
              <p>{{ item.message }}</p>
              @if (item.action; as action) {
                <a class="notification-action" [href]="action.url">{{ action.label }}</a>
              }
            </div>
            <button
              type="button"
              class="icon-action notification-close"
              [attr.aria-label]="'Dismiss notification: ' + item.message"
              (click)="notifications.dismiss(item.id)"
            >
              <app-icon name="close" [size]="17" />
            </button>
          </article>
        }
      </section>
    }
  `,
  styles: `
    :host {
      display: contents;
    }
    .notification-stack {
      position: fixed;
      inset: auto 1rem auto auto;
      z-index: 2147483000;
      display: flex;
      flex-direction: column;
      gap: 0.65rem;
      width: min(28rem, calc(100vw - 2rem));
      max-height: calc(100dvh - 2rem);
      overflow-y: auto;
      pointer-events: none;
    }
    .notification-card {
      display: flex;
      align-items: flex-start;
      gap: 0.75rem;
      padding: 0.9rem;
      color: var(--notification-foreground, #111827);
      background: var(--notification-background, rgba(255, 255, 255, 0.97));
      border: 1px solid #7b8da5;
      border-left: 5px solid #225eb4;
      border-radius: 0.7rem;
      box-shadow: 0 6px 24px #14233933;
      pointer-events: auto;
    }
    .notification-content {
      flex: 1;
      min-width: 0;
    }
    .notification-action {
      display: inline-block;
      margin-top: 0.5rem;
      font-weight: 700;
      text-decoration: underline;
    }
    .notification-card p {
      margin: 0;
      padding-top: 0.2rem;
      font-size: 0.875rem;
      line-height: 1.5;
      overflow-wrap: anywhere;
      text-shadow: none;
    }
    .notification-mark {
      color: #225eb4;
      padding-top: 0.2rem;
    }
    .notification-error {
      border-left-color: #b42332;
    }
    .notification-error .notification-mark {
      color: #b42332;
    }
    .notification-success {
      border-left-color: #167047;
    }
    .notification-success .notification-mark {
      color: #167047;
    }
    .notification-warning {
      border-left-color: #8b5700;
    }
    .notification-warning .notification-mark {
      color: #8b5700;
    }
    .notification-close {
      flex: 0 0 auto;
    }
    @media (forced-colors: active) {
      .notification-card {
        background: Canvas;
        color: CanvasText;
        border-color: CanvasText;
      }
    }
  `,
})
export class NotificationOutletComponent {
  readonly notifications = inject(NotificationService);
  protected readonly active = signal(false);
  protected readonly top = signal(12);
  private readonly document = inject(DOCUMENT);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly destroyRef = inject(DestroyRef);

  constructor() {
    afterNextRender(() => {
      let trackedHeader: Element | null = null;
      let resizeObserver: ResizeObserver | null = null;
      const refresh = () => {
        const openDialogs = this.document.querySelectorAll<HTMLDialogElement>('dialog[open]');
        const topDialog = openDialogs.item(openDialogs.length - 1);
        const ownDialog = this.host.nativeElement.closest('dialog');
        // A dialog is in the browser's top layer. Its own outlet remains interactive;
        // the app outlet and outlets in earlier/closed dialogs do not duplicate it.
        this.active.set(topDialog ? ownDialog === topDialog : ownDialog === null);
        const header = ownDialog
          ? ownDialog.querySelector('header')
          : (this.document.querySelector('app-header header') ??
            this.document.querySelector('app-header'));
        // Float below the active header so upload/restore/back/download controls stay exposed.
        this.top.set(Math.max(12, Math.ceil(header?.getBoundingClientRect().bottom ?? 0) + 8));
        if (header !== trackedHeader) {
          trackedHeader = header;
          resizeObserver?.disconnect();
          if (header) resizeObserver?.observe(header);
        }
      };
      if (typeof ResizeObserver !== 'undefined') resizeObserver = new ResizeObserver(refresh);
      const observer = new MutationObserver(refresh);
      observer.observe(this.document.body, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ['open'],
      });
      const window = this.document.defaultView;
      window?.addEventListener('resize', refresh);
      this.destroyRef.onDestroy(() => {
        observer.disconnect();
        resizeObserver?.disconnect();
        window?.removeEventListener('resize', refresh);
      });
      refresh();
    });
  }
}
