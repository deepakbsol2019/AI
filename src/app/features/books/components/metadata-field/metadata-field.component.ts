import { DOCUMENT } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  EnvironmentInjector,
  inject,
  Injector,
  input,
  OnDestroy,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Subscription } from 'rxjs';
import { NotificationService } from '../../../../shared/services/notification.service';
import { NotificationOutletComponent } from '../../../../shared/ui/notification-outlet.component';
import { TruncatedTextComponent } from '../../../../shared/ui/truncated-text.component';
import { apiErrorMessage } from '../../../../shared/utils/api-error';
import { lockLibraryScroll } from '../../../../shared/utils/scroll-lock';
import { MetadataApiService } from '../../data-access/metadata-api.service';
import type { Book, BookTopic } from '../../models/book.models';
import {
  isDescriptionMetadataField,
  isTopicMetadataField,
  METADATA_FIELD_LIMITS,
  MetadataField,
} from '../../models/metadata-fields';

const FALLBACKS: Readonly<Record<MetadataField, string>> = {
  bookName: 'Untitled book',
  bookType: 'Unknown',
  authorName: 'Author not provided',
  bookDetails: 'No description available.',
  topicName: 'Untitled topic',
  topicDetails: 'No description available.',
};
let nextMetadataFieldId = 1;

@Component({
  selector: 'app-metadata-field',
  imports: [TruncatedTextComponent, NotificationOutletComponent],
  templateUrl: './metadata-field.component.html',
  styleUrl: './metadata-field.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetadataFieldComponent implements OnDestroy {
  readonly book = input.required<Book>();
  readonly topic = input<BookTopic | null>(null);
  readonly field = input.required<MetadataField>();
  readonly label = input.required<string>();
  readonly saved = output<Book>();
  readonly editing = signal(false);
  readonly pending = signal(false);
  readonly draft = signal('');
  readonly error = signal<string | null>(null);
  readonly description = computed(() => isDescriptionMetadataField(this.field()));
  readonly fallback = computed(() => FALLBACKS[this.field()]);
  readonly rawValue = computed(() => {
    const field = this.field();
    return isTopicMetadataField(field)
      ? (this.topic()?.[field] ?? null)
      : (this.book()[field] ?? null);
  });
  readonly displayValue = computed(() => {
    const raw = this.rawValue();
    if (!raw?.trim()) return null;
    return this.field() === 'bookType' ? raw.trim() : raw;
  });
  private readonly instanceId = nextMetadataFieldId++;
  readonly editorId = computed(
    () =>
      `metadata-${this.instanceId}-${this.book().bookId}-${this.topic()?.topicId ?? 'book'}-${this.field()}`,
  );
  readonly limit = computed(() =>
    this.field() === 'bookName' &&
    this.book().topics.some((topic) => topic.topicName === `${this.book().bookName}_offset`)
      ? 248
      : (METADATA_FIELD_LIMITS[this.field()] ?? null),
  );
  readonly readOnlyReason = computed(() => {
    if (!isTopicMetadataField(this.field())) return null;
    const topic = this.topic();
    if (!topic) return 'The topic is unavailable.';
    const book = this.book();
    const legacyWholeBook =
      book.topics.length === 1 &&
      topic.topicName === book.bookName &&
      topic.topicDetails === book.bookDetails &&
      topic.startPage === 1 &&
      topic.endPage === book.totalPages &&
      topic.printedStartPage === null &&
      topic.printedEndPage === null;
    return (topic.topicName ?? '').endsWith('_offset') || legacyWholeBook
      ? 'Managed PDF storage entry cannot be edited.'
      : null;
  });
  private readonly api = inject(MetadataApiService);
  private readonly notifications = inject(NotificationService);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly environmentInjector = inject(EnvironmentInjector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('descriptionDialog');
  private readonly control =
    viewChild<ElementRef<HTMLInputElement | HTMLTextAreaElement>>('editorControl');
  private request: Subscription | null = null;
  private version = 0;
  private identity = '';
  private destroyed = false;
  private opener: HTMLElement | null = null;
  private nativeOpener: HTMLElement | null = null;
  private focusFallbacks: HTMLElement[] = [];
  private releaseScroll: (() => void) | null = null;

  constructor() {
    effect(() => {
      const identity = `${this.book().bookId}:${this.topic()?.topicId ?? ''}:${this.field()}`;
      if (this.identity && identity !== this.identity) {
        ++this.version;
        this.request?.unsubscribe();
        this.request = null;
        this.finish(false);
      }
      this.identity = identity;
    });
  }

  beginEdit(event?: Event): void {
    event?.stopPropagation();
    if (this.editing() || this.pending() || this.readOnlyReason()) return;
    this.opener =
      this.document.activeElement instanceof HTMLElement ? this.document.activeElement : null;
    const host = this.host.nativeElement;
    this.focusFallbacks = [
      host.closest('dialog')?.querySelector<HTMLElement>('#topic-filter') ?? null,
      host.closest<HTMLElement>('.book-scroll-area'),
      host.closest<HTMLElement>('[data-library-scroll-region]'),
      this.document.getElementById('main-content'),
    ].filter((element): element is HTMLElement => element !== null);
    this.draft.set(this.rawValue() ?? '');
    this.error.set(null);
    this.editing.set(true);
    afterNextRender(
      () => {
        if (!this.editing() || this.destroyed) return;
        if (this.description()) {
          this.releaseScroll = lockLibraryScroll(this.document);
          this.nativeOpener =
            this.document.activeElement instanceof HTMLElement ? this.document.activeElement : null;
          try {
            this.dialog()?.nativeElement.showModal();
          } catch {
            this.releaseScroll();
            this.releaseScroll = null;
            this.finish(true);
            this.notifications.show('The description editor could not open.', 'error');
            return;
          }
        }
        this.control()?.nativeElement.focus();
      },
      { injector: this.injector },
    );
  }

  updateDraft(event: Event): void {
    if (this.pending()) return;
    this.draft.set((event.target as HTMLInputElement | HTMLTextAreaElement).value);
    this.error.set(null);
  }

  save(event?: Event): void {
    event?.preventDefault();
    event?.stopPropagation();
    if (!this.editing() || this.pending() || this.readOnlyReason()) return;
    const field = this.field();
    const raw = this.draft();
    const trimmed = raw.trim();
    const limit = this.limit();
    if ((field === 'bookName' || field === 'topicName') && !trimmed) {
      this.error.set(`${this.label()} is required.`);
      return;
    }
    if (limit !== null && trimmed.length > limit) {
      this.error.set(`${this.label()} must be ${limit} characters or fewer.`);
      return;
    }
    if (field === 'topicName' && trimmed.endsWith('_offset')) {
      this.error.set('The _offset suffix is reserved for managed PDF storage.');
      return;
    }
    const value = this.description()
      ? trimmed
        ? raw
        : null
      : field === 'bookType'
        ? trimmed || 'Unknown'
        : trimmed || null;
    const topic = this.topic();
    if (isTopicMetadataField(field) && !topic) return;
    const request = isTopicMetadataField(field)
      ? this.api.patchTopic(this.book().bookId, topic!.topicId, field, value)
      : this.api.patchBook(this.book().bookId, field, value);
    const version = ++this.version;
    this.pending.set(true);
    this.error.set(null);
    this.request = request.subscribe({
      next: (confirmed) => {
        if (version !== this.version || this.destroyed) return;
        this.request = null;
        this.finish(true);
        this.notifications.show(`${this.label()} updated.`, 'success');
        this.saved.emit(confirmed);
      },
      error: (error: unknown) => void this.failed(error, version),
    });
  }

  cancel(event?: Event): void {
    event?.preventDefault();
    event?.stopPropagation();
    if (this.pending()) return;
    this.finish(true);
  }

  onDialogCancel(event: Event): void {
    event.stopPropagation();
    if (this.pending()) event.preventDefault();
  }

  onDialogClosed(): void {
    if (this.editing()) this.finish(true);
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    ++this.version;
    this.request?.unsubscribe();
    this.finish(false);
  }

  private async failed(error: unknown, version: number): Promise<void> {
    const message = await apiErrorMessage(error);
    if (version !== this.version || this.destroyed) return;
    this.request = null;
    this.pending.set(false);
    this.error.set(message);
    this.notifications.show(message, 'error');
  }

  private finish(restoreFocus: boolean): void {
    const dialog = this.dialog()?.nativeElement;
    const control = this.control()?.nativeElement;
    const nativeOpener = this.nativeOpener;
    this.editing.set(false);
    this.pending.set(false);
    this.error.set(null);
    dialog?.close();
    this.releaseScroll?.();
    this.releaseScroll = null;
    if (restoreFocus && this.opener) {
      const opener = this.opener;
      const host = this.host.nativeElement;
      const document = this.document;
      const fallbacks = [...this.focusFallbacks];
      afterNextRender(
        () => {
          const active = document.activeElement;
          const pencil = host.isConnected
            ? host.querySelector<HTMLElement>('.metadata-pencil')
            : null;
          if (
            active &&
            active.isConnected &&
            active !== document.body &&
            active !== document.documentElement &&
            active !== opener &&
            active !== nativeOpener &&
            active !== control &&
            !dialog?.contains(active)
          )
            return;
          const candidates = [
            opener !== document.body && opener !== document.documentElement ? opener : null,
            pencil,
            ...fallbacks,
          ];
          const target = candidates.find(
            (element) => element?.isConnected && !element.hasAttribute('disabled'),
          );
          if (!target) return;
          const tabIndex = target.getAttribute('tabindex');
          if (tabIndex === null) target.setAttribute('tabindex', '-1');
          target.focus({ preventScroll: true });
          if (tabIndex === null) target.removeAttribute('tabindex');
        },
        { injector: this.environmentInjector },
      );
    }
    this.opener = null;
    this.nativeOpener = null;
    this.focusFallbacks = [];
  }
}
