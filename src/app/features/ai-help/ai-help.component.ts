import { DOCUMENT } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  HostListener,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { firstValueFrom, Observable, takeUntil } from 'rxjs';
import { ApplicationLayoutComponent } from '../../layout/application-layout/application-layout.component';
import { apiErrorMessage } from '../../shared/utils/api-error';
import { downloadFilename } from '../../shared/utils/download-filename';
import { AuthBoundaryService } from '../user/services/auth-boundary.service';
import { AiHelpApiService, ChatMessage, ChatPage } from './ai-help-api.service';

interface Confirmation {
  kind: 'clean' | 'delete';
  pageId: number;
  name: string;
  revision: number;
}

@Component({
  selector: 'app-ai-help',
  standalone: true,
  imports: [FormsModule, RouterLink, ApplicationLayoutComponent],
  templateUrl: './ai-help.component.html',
  styleUrl: './ai-help.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiHelpComponent {
  private readonly api = inject(AiHelpApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly boundary = inject(AuthBoundaryService);
  private readonly document = inject(DOCUMENT);
  private readonly dialog = viewChild<ElementRef<HTMLElement>>('confirmDialog');
  private generation = 0;
  private viewVersion = 0;
  private messageMutation = 0;
  private olderPage = 0;
  private returnFocus: HTMLElement | null = null;
  private readonly draftVersions = new Map<number, number>();
  private readonly retries = new Map<number, { text: string; requestId: string }>();
  private readonly drafts = signal<Record<number, string>>({});
  readonly pages = signal<ChatPage[]>([]);
  readonly selectedId = signal<number | null>(null);
  readonly selected = computed(
    () => this.pages().find((page) => page.pageId === this.selectedId()) ?? null,
  );
  readonly draft = computed(() => this.drafts()[this.selectedId() ?? -1] ?? '');
  readonly messages = signal<ChatMessage[]>([]);
  readonly totalMessages = signal(0);
  readonly hasOlder = signal(false);
  readonly loading = signal(true);
  readonly loadingOlder = signal(false);
  readonly selecting = signal(false);
  readonly mutating = signal(false);
  readonly sending = signal<ReadonlySet<number>>(new Set());
  readonly exporting = signal<ReadonlySet<number>>(new Set());
  readonly error = signal('');
  readonly notice = signal('');
  readonly confirmation = signal<Confirmation | null>(null);
  readonly renaming = signal(false);
  renameText = '';

  constructor() {
    this.boundary.resets.pipe(takeUntilDestroyed()).subscribe(() => this.reset());
    effect(() => {
      const dialog = this.dialog()?.nativeElement;
      if (this.confirmation() && dialog) dialog.querySelector<HTMLButtonElement>('button')?.focus();
    });
    void this.load();
  }

  async load(): Promise<void> {
    const generation = this.generation;
    this.viewVersion++;
    this.pages.set([]);
    this.selectedId.set(null);
    this.messages.set([]);
    this.loading.set(true);
    this.error.set('');
    try {
      const listing = await this.request(this.api.list());
      if (!this.active(generation)) return;
      this.pages.set(listing.pages);
      const page =
        listing.pages.find((item) => item.pageId === listing.lastSelectedPageId) ??
        listing.pages[0];
      if (!page) await this.create();
      else await this.selectPage(page, page.pageId !== listing.lastSelectedPageId);
    } catch (failure) {
      await this.showError(failure, generation);
    } finally {
      if (this.active(generation)) this.loading.set(false);
    }
  }

  async create(): Promise<void> {
    if (this.mutating() || this.selecting() || this.confirmation()) return;
    const generation = this.generation;
    this.mutating.set(true);
    this.error.set('');
    try {
      const page = await this.request(this.api.create());
      if (!this.active(generation)) return;
      this.pages.update((pages) => [...pages, page]);
      await this.selectPage(page, false);
    } catch (failure) {
      await this.showError(failure, generation);
    } finally {
      if (this.active(generation)) this.mutating.set(false);
    }
  }

  async selectPage(page: ChatPage, persist = true): Promise<void> {
    if (this.selecting() || this.confirmation() || (persist && this.mutating())) return;
    const generation = this.generation,
      view = ++this.viewVersion;
    this.selecting.set(true);
    this.loading.set(true);
    this.error.set('');
    this.notice.set('');
    this.messages.set([]);
    this.totalMessages.set(0);
    this.hasOlder.set(false);
    this.loadingOlder.set(false);
    this.selectedId.set(page.pageId);
    this.renaming.set(false);
    this.olderPage = 0;
    try {
      if (persist) {
        page = await this.request(this.api.select(page.pageId));
        if (!this.current(generation, view, page.pageId)) return;
        this.replacePage(page);
      }
      const mutation = this.messageMutation;
      const response = await this.request(this.api.messages(page.pageId));
      if (!this.current(generation, view, page.pageId)) return;
      if (response.revision < (this.selected()?.revision ?? 0)) return;
      this.pages.update((pages) =>
        pages.map((item) =>
          item.pageId === page.pageId ? { ...item, revision: response.revision } : item,
        ),
      );
      this.messages.set(
        mutation === this.messageMutation
          ? response.messages
          : this.merge(response.messages, this.messages()),
      );
      this.totalMessages.set(Math.max(response.totalElements, this.messages().length));
      this.hasOlder.set(response.hasOlder);
    } catch (failure) {
      if (this.current(generation, view, page.pageId))
        await this.showError(failure, generation, page.pageId);
    } finally {
      if (this.current(generation, view, page.pageId)) {
        this.loading.set(false);
        this.selecting.set(false);
      }
    }
  }

  async loadOlder(): Promise<void> {
    const page = this.selected();
    if (!page || this.loading() || this.loadingOlder() || !this.hasOlder() || this.confirmation())
      return;
    const generation = this.generation,
      view = this.viewVersion;
    this.loadingOlder.set(true);
    this.error.set('');
    try {
      const response = await this.request(this.api.messages(page.pageId, this.olderPage + 1));
      if (!this.current(generation, view, page.pageId)) return;
      if (response.revision !== this.selected()?.revision) {
        this.loadingOlder.set(false);
        await this.selectPage(this.selected()!, false);
        return;
      }
      this.olderPage++;
      this.messages.set(this.merge(response.messages, this.messages()));
      this.totalMessages.set(Math.max(response.totalElements, this.messages().length));
      this.hasOlder.set(response.hasOlder);
    } catch (failure) {
      if (this.current(generation, view, page.pageId))
        await this.showError(failure, generation, page.pageId);
    } finally {
      if (this.current(generation, view, page.pageId)) this.loadingOlder.set(false);
    }
  }

  setDraft(text: string): void {
    const id = this.selectedId();
    if (id === null) return;
    this.draftVersions.set(id, (this.draftVersions.get(id) ?? 0) + 1);
    this.drafts.update((drafts) => ({ ...drafts, [id]: text }));
  }
  composerKey(event: KeyboardEvent): void {
    if (event.isComposing) return;
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      void this.send();
    }
  }
  async send(): Promise<void> {
    const page = this.selected(),
      text = this.draft();
    if (
      !page ||
      !text.trim() ||
      this.loading() ||
      this.sending().has(page.pageId) ||
      this.mutating() ||
      this.confirmation()
    )
      return;
    const generation = this.generation,
      draftVersion = this.draftVersions.get(page.pageId) ?? 0;
    const retry = this.retries.get(page.pageId);
    const requestId = retry?.text === text ? retry.requestId : crypto.randomUUID();
    this.retries.set(page.pageId, { text, requestId });
    this.sending.update((ids) => new Set([...ids, page.pageId]));
    this.error.set('');
    this.notice.set('');
    try {
      const response = await this.request(
        this.api.send(page.pageId, page.revision, text, requestId),
      );
      if (!this.active(generation)) return;
      this.retries.delete(page.pageId);
      if ((this.draftVersions.get(page.pageId) ?? 0) === draftVersion)
        this.drafts.update((drafts) => ({ ...drafts, [page.pageId]: '' }));
      this.messageMutation++;
      if (this.selectedId() === page.pageId && response.revision === this.selected()?.revision) {
        const existed = this.messages().some(
          (message) => message.messageId === response.message.messageId,
        );
        this.messages.set(this.merge(this.messages(), [response.message]));
        if (!existed) this.totalMessages.update((total) => total + 1);
        this.notice.set(response.acknowledgment);
      }
    } catch (failure) {
      if (this.selectedId() === page.pageId) await this.showError(failure, generation, page.pageId);
    } finally {
      if (this.active(generation))
        this.sending.update((ids) => {
          const next = new Set(ids);
          next.delete(page.pageId);
          return next;
        });
    }
  }

  beginRename(): void {
    this.renameText = this.selected()?.name ?? '';
    this.renaming.set(true);
    this.error.set('');
  }
  async rename(): Promise<void> {
    const page = this.selected(),
      name = this.renameText.trim();
    if (!page || !name || this.mutating() || this.confirmation()) return;
    const generation = this.generation;
    this.mutating.set(true);
    this.error.set('');
    try {
      const updated = await this.request(this.api.rename(page.pageId, name));
      if (!this.active(generation)) return;
      this.replacePage(updated);
      this.renaming.set(false);
      this.notice.set('Chat name saved.');
    } catch (failure) {
      await this.showError(failure, generation);
    } finally {
      if (this.active(generation)) this.mutating.set(false);
    }
  }

  ask(kind: 'clean' | 'delete', trigger?: Event): void {
    const page = this.selected();
    if (
      !page ||
      this.loading() ||
      this.loadingOlder() ||
      this.mutating() ||
      this.sending().has(page.pageId) ||
      this.exporting().has(page.pageId)
    )
      return;
    this.returnFocus = trigger?.currentTarget instanceof HTMLElement ? trigger.currentTarget : null;
    this.error.set('');
    this.confirmation.set({ kind, pageId: page.pageId, name: page.name, revision: page.revision });
  }
  cancel(): void {
    if (!this.mutating()) {
      this.confirmation.set(null);
      this.error.set('');
      this.returnFocus?.focus();
    }
  }
  async confirm(): Promise<void> {
    const action = this.confirmation();
    if (!action || this.mutating() || this.exporting().has(action.pageId)) return;
    const generation = this.generation;
    this.mutating.set(true);
    this.error.set('');
    try {
      if (action.kind === 'clean') {
        const page = await this.request(this.api.clean(action.pageId, action.revision));
        if (!this.active(generation)) return;
        this.viewVersion++;
        this.messageMutation++;
        this.replacePage(page);
        this.messages.set([]);
        this.totalMessages.set(0);
        this.hasOlder.set(false);
        this.olderPage = 0;
        this.retries.delete(action.pageId);
        this.notice.set('All stored messages in this chat were removed.');
        this.confirmation.set(null);
      } else {
        const result = await this.request(this.api.delete(action.pageId, action.revision));
        if (!this.active(generation)) return;
        this.viewVersion++;
        this.pages.update((pages) => pages.filter((page) => page.pageId !== action.pageId));
        this.messages.set([]);
        this.selectedId.set(null);
        this.totalMessages.set(0);
        this.hasOlder.set(false);
        this.drafts.update((drafts) => {
          const next = { ...drafts };
          delete next[action.pageId];
          return next;
        });
        this.retries.delete(action.pageId);
        this.confirmation.set(null);
        const next =
          this.pages().find((page) => page.pageId === result.lastSelectedPageId) ?? this.pages()[0];
        if (next) await this.selectPage(next, false);
        else {
          this.mutating.set(false);
          await this.create();
        }
      }
    } catch (failure) {
      await this.showError(failure, generation);
    } finally {
      if (this.active(generation)) this.mutating.set(false);
    }
  }

  async download(page = this.selected()): Promise<void> {
    if (!page || this.exporting().has(page.pageId) || this.mutating()) return;
    const generation = this.generation;
    this.exporting.update((ids) => new Set([...ids, page.pageId]));
    this.error.set('');
    try {
      const response = await this.request(this.api.pdf(page.pageId));
      if (!this.active(generation)) return;
      const blob = response.body;
      if (!blob || blob.size === 0) throw new Error('This chat has no conversation to download.');
      if (!blob.type.toLowerCase().includes('pdf'))
        throw new HttpErrorResponse({ error: blob, status: 502 });
      const filename = downloadFilename(
        response.headers.get('Content-Disposition'),
        `${page.name}.pdf`,
      );
      const url = URL.createObjectURL(blob),
        link = this.document.createElement('a');
      link.href = url;
      link.download = filename;
      link.hidden = true;
      this.document.body.appendChild(link);
      try {
        link.click();
      } finally {
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      }
      if (this.selectedId() === page.pageId) this.notice.set(`Download ready: ${filename}`);
    } catch (failure) {
      await this.showError(failure, generation);
    } finally {
      if (this.active(generation))
        this.exporting.update((ids) => {
          const next = new Set(ids);
          next.delete(page.pageId);
          return next;
        });
    }
  }

  @HostListener('document:keydown', ['$event'])
  keyboard(event: KeyboardEvent): void {
    if (!this.confirmation()) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      this.cancel();
      return;
    }
    if (event.key !== 'Tab') return;
    const buttons = [
      ...(this.dialog()?.nativeElement.querySelectorAll<HTMLButtonElement>(
        'button:not(:disabled)',
      ) ?? []),
    ];
    const first = buttons[0],
      last = buttons.at(-1);
    if (event.shiftKey && this.document.activeElement === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && this.document.activeElement === last) {
      event.preventDefault();
      first?.focus();
    }
  }
  private request<T>(request: Observable<T>): Promise<T> {
    return firstValueFrom(
      request.pipe(takeUntil(this.boundary.resets), takeUntilDestroyed(this.destroyRef)),
    );
  }
  private active(generation: number): boolean {
    return generation === this.generation && !this.destroyRef.destroyed;
  }
  private current(generation: number, view: number, id: number): boolean {
    return this.active(generation) && view === this.viewVersion && id === this.selectedId();
  }
  private replacePage(page: ChatPage): void {
    this.pages.update((pages) => pages.map((item) => (item.pageId === page.pageId ? page : item)));
  }
  private merge(first: ChatMessage[], second: ChatMessage[]): ChatMessage[] {
    return [
      ...new Map([...first, ...second].map((message) => [message.messageId, message])).values(),
    ].sort((a, b) => a.messageOrder - b.messageOrder || a.messageId - b.messageId);
  }
  private async showError(failure: unknown, generation: number, pageId?: number): Promise<void> {
    const message = await apiErrorMessage(failure);
    if (this.active(generation) && (pageId === undefined || this.selectedId() === pageId))
      this.error.set(message);
  }
  private reset(): void {
    this.generation++;
    this.viewVersion++;
    this.messageMutation++;
    this.pages.set([]);
    this.selectedId.set(null);
    this.messages.set([]);
    this.totalMessages.set(0);
    this.drafts.set({});
    this.draftVersions.clear();
    this.retries.clear();
    this.hasOlder.set(false);
    this.sending.set(new Set());
    this.exporting.set(new Set());
    this.confirmation.set(null);
    this.error.set('');
    this.notice.set('');
    this.loading.set(false);
    this.loadingOlder.set(false);
    this.selecting.set(false);
    this.mutating.set(false);
    this.renaming.set(false);
  }
}
