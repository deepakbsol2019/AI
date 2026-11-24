import { DOCUMENT } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  input,
  OnDestroy,
  signal,
  viewChild,
} from '@angular/core';

let nextPreviewId = 0;
const activePreviews = new WeakMap<Document, TruncatedTextComponent>();

@Component({
  selector: 'app-truncated-text',
  standalone: true,
  templateUrl: './truncated-text.component.html',
  styleUrl: './truncated-text.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TruncatedTextComponent implements OnDestroy {
  readonly value = input<string | null>(null);
  readonly fallback = input<string>('—');
  protected readonly fullText = computed(() => {
    const value = this.value();
    return value !== null && value.trim() !== '' ? value : this.fallback();
  });
  protected readonly truncated = signal(false);
  protected readonly previewOpen = signal(false);
  protected readonly previewId = `full-text-preview-${++nextPreviewId}`;
  private readonly document = inject(DOCUMENT);
  private readonly trigger = viewChild<ElementRef<HTMLElement>>('trigger');
  private readonly preview = viewChild<ElementRef<HTMLElement>>('preview');
  private observer: ResizeObserver | null = null;
  private fonts: FontFaceSet | null = null;
  private frame: number | null = null;
  private microtaskQueued = false;
  private closeTimer: ReturnType<typeof setTimeout> | undefined;
  private pointerWithin = false;
  private pinned = false;
  private ignoreFocus = false;
  private nativeOpen = false;
  private portalParent: Node | null = null;
  private portalNext: Node | null = null;
  private portaled = false;
  private destroyed = false;

  constructor() {
    effect(() => {
      this.fullText();
      this.scheduleMeasure();
    });
    afterNextRender(() => {
      const trigger = this.trigger()?.nativeElement;
      const preview = this.preview()?.nativeElement;
      if (!trigger || !preview) return;
      this.portalParent = preview.parentNode;
      this.portalNext = preview.nextSibling;
      if (typeof ResizeObserver !== 'undefined') {
        this.observer = new ResizeObserver(this.scheduleMeasure);
        this.observer.observe(trigger);
      }
      this.document.defaultView?.addEventListener('resize', this.scheduleMeasure);
      this.fonts = this.document.fonts ?? null;
      this.fonts?.addEventListener('loadingdone', this.scheduleMeasure);
      void this.fonts?.ready.then(() => {
        if (!this.destroyed) this.scheduleMeasure();
      });
      this.measure();
    });
  }

  protected pointerEnter(): void {
    this.pointerWithin = true;
    clearTimeout(this.closeTimer);
    this.showPreview(false);
  }

  protected previewEnter(): void {
    this.pointerWithin = true;
    clearTimeout(this.closeTimer);
  }

  protected pointerLeave(): void {
    this.pointerWithin = false;
    this.scheduleClose();
  }

  protected focused(): void {
    if (!this.ignoreFocus) this.showPreview(false);
  }

  protected focusLeft(): void {
    this.scheduleClose();
  }

  protected activate(event: Event): void {
    this.measure();
    if (!this.truncated()) return;
    event.preventDefault();
    event.stopPropagation();
    if (this.previewOpen() && this.pinned) this.closePreview(true);
    else {
      this.showPreview(true);
      this.preview()?.nativeElement.focus({ preventScroll: true });
    }
  }

  protected triggerKey(event: KeyboardEvent): void {
    if (event.key === 'Enter' || event.key === ' ') this.activate(event);
  }

  protected dismiss(event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    this.closePreview(true);
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.closePreview(false);
    this.observer?.disconnect();
    this.fonts?.removeEventListener('loadingdone', this.scheduleMeasure);
    this.document.defaultView?.removeEventListener('resize', this.scheduleMeasure);
    if (this.frame !== null) this.document.defaultView?.cancelAnimationFrame(this.frame);
  }

  private readonly scheduleMeasure = (): void => {
    if (this.destroyed || this.frame !== null || this.microtaskQueued) return;
    const window = this.document.defaultView;
    if (window?.requestAnimationFrame) {
      this.frame = window.requestAnimationFrame(() => {
        this.frame = null;
        if (!this.destroyed) this.measure();
      });
    } else {
      this.microtaskQueued = true;
      queueMicrotask(() => {
        this.microtaskQueued = false;
        if (!this.destroyed) this.measure();
      });
    }
  };

  private measure(): void {
    const trigger = this.trigger()?.nativeElement;
    if (!trigger) return;
    const clipped = trigger.clientWidth > 0 && trigger.scrollWidth > trigger.clientWidth;
    this.truncated.set(clipped);
    if (!clipped) this.closePreview(false);
    else if (this.previewOpen()) this.positionPreview();
  }

  private showPreview(pin: boolean): void {
    this.measure();
    if (!this.truncated() || this.destroyed) return;
    clearTimeout(this.closeTimer);
    if (pin) this.pinned = true;
    if (this.previewOpen()) return;
    activePreviews.get(this.document)?.closePreview(false);
    activePreviews.set(this.document, this);
    const preview = this.preview()?.nativeElement;
    if (!preview) return;
    this.previewOpen.set(true);
    preview.hidden = false;
    preview.style.visibility = 'hidden';
    try {
      if (typeof preview.showPopover !== 'function') throw new Error('Popover API unavailable');
      preview.showPopover();
      this.nativeOpen = true;
    } catch {
      const dialogs = this.document.querySelectorAll<HTMLDialogElement>('dialog[open]');
      const container =
        this.trigger()?.nativeElement.closest('dialog[open]') ??
        dialogs.item(dialogs.length - 1) ??
        this.document.body;
      container.appendChild(preview);
      this.portaled = true;
    }
    this.positionPreview();
    this.document.addEventListener('pointerdown', this.outsidePointer, true);
    this.document.addEventListener('keydown', this.escapeKey, true);
    this.document.addEventListener('scroll', this.scrolled, true);
  }

  private positionPreview(): void {
    const trigger = this.trigger()?.nativeElement;
    const preview = this.preview()?.nativeElement;
    if (!trigger || !preview || !this.previewOpen()) return;
    const window = this.document.defaultView;
    const viewport = window?.visualViewport;
    const width = viewport?.width || window?.innerWidth || 1024;
    const height = viewport?.height || window?.innerHeight || 768;
    const originX = viewport?.offsetLeft || 0;
    const originY = viewport?.offsetTop || 0;
    const gap = 8;
    const maxWidth = Math.max(0, Math.min(480, width - gap * 2));
    const maxHeight = Math.max(0, Math.min(360, height - gap * 2));
    preview.style.width = `${maxWidth}px`;
    preview.style.maxWidth = `${maxWidth}px`;
    preview.style.maxHeight = `${maxHeight}px`;
    const anchor = trigger.getBoundingClientRect();
    const box = preview.getBoundingClientRect();
    const renderedWidth = Math.min(maxWidth, box.width || maxWidth);
    const renderedHeight = Math.min(maxHeight, box.height || maxHeight);
    const left = Math.min(
      Math.max(anchor.left, originX + gap),
      originX + width - renderedWidth - gap,
    );
    const below = anchor.bottom + gap;
    const preferredTop =
      below + renderedHeight <= originY + height - gap ? below : anchor.top - renderedHeight - gap;
    const top = Math.max(
      originY + gap,
      Math.min(preferredTop, originY + height - renderedHeight - gap),
    );
    preview.style.left = `${left}px`;
    preview.style.top = `${top}px`;
    preview.style.visibility = 'visible';
  }

  private scheduleClose(): void {
    clearTimeout(this.closeTimer);
    if (!this.previewOpen()) return;
    this.closeTimer = setTimeout(() => {
      const focused = this.document.activeElement;
      const inside =
        this.trigger()?.nativeElement.contains(focused) ||
        this.preview()?.nativeElement.contains(focused);
      if (!this.pinned && !this.pointerWithin && !inside) this.closePreview(false);
    }, 200);
  }

  private closePreview(restoreFocus: boolean): void {
    clearTimeout(this.closeTimer);
    this.pinned = false;
    this.previewOpen.set(false);
    if (activePreviews.get(this.document) === this) activePreviews.delete(this.document);
    this.document.removeEventListener('pointerdown', this.outsidePointer, true);
    this.document.removeEventListener('keydown', this.escapeKey, true);
    this.document.removeEventListener('scroll', this.scrolled, true);
    const preview = this.preview()?.nativeElement;
    if (preview) {
      if (this.nativeOpen) {
        try {
          preview.hidePopover();
        } catch {
          /* Already removed from the top layer. */
        }
      }
      this.nativeOpen = false;
      preview.hidden = true;
      if (this.portaled) {
        this.portalParent?.insertBefore(
          preview,
          this.portalNext?.parentNode === this.portalParent ? this.portalNext : null,
        );
        this.portaled = false;
      }
    }
    if (restoreFocus && !this.destroyed) {
      this.ignoreFocus = true;
      this.trigger()?.nativeElement.focus({ preventScroll: true });
      queueMicrotask(() => {
        this.ignoreFocus = false;
      });
    }
  }

  private readonly outsidePointer = (event: Event): void => {
    const target = event.target as Node | null;
    if (
      !this.trigger()?.nativeElement.contains(target) &&
      !this.preview()?.nativeElement.contains(target)
    ) {
      this.closePreview(false);
    }
  };

  private readonly escapeKey = (event: KeyboardEvent): void => {
    if (event.key !== 'Escape') return;
    event.preventDefault();
    event.stopPropagation();
    this.closePreview(true);
  };

  private readonly scrolled = (event: Event): void => {
    if (!this.preview()?.nativeElement.contains(event.target as Node | null))
      this.closePreview(false);
  };
}
