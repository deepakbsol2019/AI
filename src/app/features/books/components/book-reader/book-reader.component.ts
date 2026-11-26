import { DOCUMENT } from '@angular/common';
import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  OnDestroy,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { Subscription } from 'rxjs';
import { IconComponent } from '../../../../shared/ui/icon.component';
import { NotificationOutletComponent } from '../../../../shared/ui/notification-outlet.component';
import { apiErrorMessage } from '../../../../shared/utils/api-error';
import { lockLibraryScroll } from '../../../../shared/utils/scroll-lock';
import { BooksApiService } from '../../data-access/books-api.service';
import { BookDownloadService } from '../../data-access/book-download.service';
import { PdfReaderService } from '../../data-access/pdf-reader.service';
import { PageTurnSoundService } from '../../data-access/page-turn-sound.service';
import {
  spreadPages,
  stepReader,
  type ReaderPageMode,
  type ReaderStage,
} from '../../models/reader-spreads';
import { sourceOrderedTopics } from '../../models/reader-topic-order';
import type {
  PdfLoadHandle,
  PdfPageTheme,
  PdfReaderDocument,
  PdfReaderPage,
  PdfRenderHandle,
  ReaderRequest,
} from '../../models/reader.models';

@Component({
  selector: 'app-book-reader',
  imports: [IconComponent, NotificationOutletComponent],
  providers: [PageTurnSoundService],
  templateUrl: './book-reader.component.html',
  styleUrl: './book-reader.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BookReaderComponent implements OnDestroy {
  readonly request = input.required<ReaderRequest>();
  readonly closed = output<void>();
  readonly downloads = inject(BookDownloadService);
  readonly status = signal<'loading' | 'ready' | 'empty' | 'error'>('loading');
  readonly error = signal('');
  readonly rendering = signal(false);
  readonly pageNumber = signal(1);
  readonly pageCount = signal(0);
  readonly zoom = signal(1);
  readonly pageTheme = signal<PdfPageTheme>('original');
  readonly fullPage = signal(false);
  readonly pageMode = signal<ReaderPageMode>(
    typeof window !== 'undefined' && window.innerWidth > 700 ? 'double' : 'single',
  );
  readonly stage = signal<ReaderStage>('pages');
  readonly soundEnabled = signal(false);
  readonly visiblePages = computed(() =>
    spreadPages(this.pageNumber(), this.pageCount(), this.pageMode()),
  );
  readonly pageLabel = computed(() => {
    if (this.fullPage() && this.stage() !== 'pages')
      return this.stage() === 'front' ? 'Front cover' : 'Back cover';
    const pages = this.visiblePages();
    return pages.length === 2
      ? `Pages ${pages[0]}–${pages[1]} of ${this.pageCount()}`
      : `Page ${this.pageNumber()} of ${this.pageCount()}`;
  });
  readonly canPrevious = computed(() => this.canStep(-1));
  readonly canNext = computed(() => this.canStep(1));
  readonly orderedTopics = computed(() => {
    const request = this.request();
    return sourceOrderedTopics(request.book, request.topics);
  });
  readonly scopeLabel = computed(() =>
    this.request().mode === 'book'
      ? 'Complete book'
      : this.request().mode === 'topic'
        ? 'Single topic'
        : `${this.request().topics.length} selected topics`,
  );
  readonly title = computed(() =>
    this.request().mode === 'topic'
      ? this.request().topics[0]?.topicName || 'Book topic'
      : this.request().book.bookName,
  );
  readonly downloadLabel = computed(() =>
    this.request().mode === 'book'
      ? 'Download Book'
      : this.request().mode === 'topic'
        ? 'Download Topic'
        : 'Download Selected Topic',
  );
  readonly downloading = computed(() => {
    const request = this.request();
    if (request.mode === 'book') return this.downloads.isBookDownloading(request.book.bookId);
    if (request.mode === 'topic')
      return (
        this.downloads.isSelectedTopicsDownloading(request.book.bookId) ||
        this.downloads.isTopicDownloading(request.book.bookId, request.topics[0]?.topicId ?? 0)
      );
    return this.downloads.isAnyTopicDownloading(request.book.bookId);
  });
  private readonly api = inject(BooksApiService);
  private readonly engine = inject(PdfReaderService);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly sound = inject(PageTurnSoundService);
  private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('readerDialog');
  private readonly canvas = viewChild<ElementRef<HTMLCanvasElement>>('pageCanvas');
  private readonly secondCanvas = viewChild<ElementRef<HTMLCanvasElement>>('secondPageCanvas');
  private readonly surface = viewChild<ElementRef<HTMLElement>>('bookSurface');
  private readonly viewport = viewChild<ElementRef<HTMLElement>>('pageViewport');
  private readonly mounted = signal(false);
  private http: Subscription | null = null;
  private loadTask: PdfLoadHandle | null = null;
  private pdf: PdfReaderDocument | null = null;
  private page: PdfReaderPage | null = null;
  private extraPage: PdfReaderPage | null = null;
  private renderTask: PdfRenderHandle | null = null;
  private extraRenderTask: PdfRenderHandle | null = null;
  private renderBarrier: Promise<void> = Promise.resolve();
  private documentVersion = 0;
  private renderVersion = 0;
  private finished = false;
  private releaseScroll: (() => void) | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private resizeTimer: ReturnType<typeof setTimeout> | undefined;
  private animation: Animation | null = null;
  private entryPage = 1;
  private entryScroll = { top: 0, left: 0 };
  private movedInFullPage = false;
  private positionVersion = 0;

  constructor() {
    effect(() => {
      const request = this.request();
      if (this.mounted()) untracked(() => this.open(request));
    });
    afterNextRender(() => {
      this.releaseScroll = lockLibraryScroll(this.document);
      this.dialog()?.nativeElement.showModal();
      this.mounted.set(true);
      if (typeof ResizeObserver !== 'undefined') {
        this.resizeObserver = new ResizeObserver(() => {
          clearTimeout(this.resizeTimer);
          this.resizeTimer = setTimeout(() => {
            if (this.pdf) void this.renderPage();
          }, 100);
        });
        const viewport = this.viewport()?.nativeElement;
        if (viewport) this.resizeObserver.observe(viewport);
      }
    });
  }

  retry(): void {
    this.open(this.request());
  }

  private canStep(direction: -1 | 1): boolean {
    return (
      this.status() === 'ready' &&
      !this.rendering() &&
      stepReader(
        { stage: this.fullPage() ? this.stage() : 'pages', page: this.pageNumber() },
        direction,
        this.pageCount(),
        this.pageMode(),
        this.fullPage(),
      ) !== null
    );
  }

  async navigate(delta: number): Promise<void> {
    const direction: -1 | 1 = delta < 0 ? -1 : 1;
    if (!this.canStep(direction)) return;
    const oldPage = this.pageNumber(),
      oldStage = this.stage(),
      version = this.documentVersion,
      position = ++this.positionVersion;
    const next = stepReader(
      { stage: this.fullPage() ? oldStage : 'pages', page: oldPage },
      direction,
      this.pageCount(),
      this.pageMode(),
      this.fullPage(),
    )!;
    this.stage.set(next.stage);
    this.pageNumber.set(next.page);
    this.resetViewport();
    const rendered = await this.renderPage();
    if (position !== this.positionVersion) return;
    if (!rendered || version !== this.documentVersion || this.finished) {
      if (version === this.documentVersion && this.status() === 'error') {
        this.pageNumber.set(oldPage);
        this.stage.set(oldStage);
      }
      return;
    }
    if (this.fullPage()) {
      this.movedInFullPage = true;
      this.animateBook(
        direction,
        next.stage === 'back' ? 'close' : oldStage === 'front' ? 'open' : 'turn',
      );
      if (this.soundEnabled()) this.sound.turn();
    }
  }

  async setPageMode(mode: ReaderPageMode): Promise<void> {
    if (mode === this.pageMode() || this.rendering()) return;
    ++this.positionVersion;
    this.pageMode.set(mode);
    this.resetViewport();
    await this.renderPage();
  }
  async enterFullPage(): Promise<void> {
    if (this.status() !== 'ready' || this.rendering() || this.fullPage()) return;
    this.entryPage = this.pageNumber();
    this.entryScroll = {
      top: this.viewport()?.nativeElement.scrollTop || 0,
      left: this.viewport()?.nativeElement.scrollLeft || 0,
    };
    this.movedInFullPage = false;
    ++this.positionVersion;
    this.soundEnabled.set(false);
    this.sound.disable();
    this.stage.set('front');
    this.fullPage.set(true);
    this.resetViewport();
    this.animateBook(1, 'open');
    afterNextRender(
      () => this.dialog()?.nativeElement.querySelector<HTMLButtonElement>('.sound-toggle')?.focus(),
      { injector: this.injector },
    );
  }
  async exitFullPage(): Promise<void> {
    if (!this.fullPage()) return;
    ++this.positionVersion;
    this.soundEnabled.set(false);
    this.sound.disable();
    this.animation?.cancel();
    this.fullPage.set(false);
    this.stage.set('pages');
    if (!this.movedInFullPage) this.pageNumber.set(this.entryPage);
    this.resetViewport();
    await this.renderPage();
    if (!this.movedInFullPage) {
      const viewport = this.viewport()?.nativeElement;
      if (viewport) {
        viewport.scrollTop = this.entryScroll.top;
        viewport.scrollLeft = this.entryScroll.left;
      }
    }
    afterNextRender(
      () =>
        this.dialog()?.nativeElement.querySelector<HTMLButtonElement>('.reader-full-page')?.focus(),
      { injector: this.injector },
    );
  }
  toggleSound(): void {
    const enabled = !this.soundEnabled();
    this.soundEnabled.set(enabled);
    if (enabled) this.sound.enable();
    else this.sound.disable();
  }
  onCancel(event: Event): void {
    if (this.fullPage()) {
      event.preventDefault();
      void this.exitFullPage();
    }
  }
  private animateBook(direction: -1 | 1, kind: 'open' | 'turn' | 'close'): void {
    const element = this.surface()?.nativeElement;
    if (
      !element?.animate ||
      this.document.defaultView?.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    )
      return;
    this.animation?.cancel();
    const angle = kind === 'close' ? direction * 12 : direction * -10;
    this.animation = element.animate(
      [
        { transform: `perspective(1600px) rotateY(${angle}deg) scale(.985)`, opacity: 0.82 },
        { transform: 'perspective(1600px) rotateY(0deg) scale(1)', opacity: 1 },
      ],
      { duration: kind === 'turn' ? 260 : 400, easing: 'ease-out' },
    );
  }

  changeZoom(delta: number): void {
    this.zoom.set(Math.round(Math.min(3, Math.max(0.5, this.zoom() + delta)) * 100) / 100);
    void this.renderPage();
  }

  fitWidth(): void {
    this.zoom.set(1);
    this.resetViewport();
    void this.renderPage();
  }

  changeTheme(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    if (value !== 'dark' && value !== 'original' && value !== 'inverted') return;
    this.pageTheme.set(value === 'inverted' ? 'dark' : value);
  }

  download(): void {
    const request = this.request();
    if (request.mode === 'book') this.downloads.downloadBook(request.book);
    else if (request.mode === 'topic' && request.topics[0])
      this.downloads.downloadTopic(request.book, request.topics[0]);
    else if (request.mode === 'selected-topics')
      this.downloads.downloadSelectedTopics(request.book, this.orderedTopics());
  }

  close(): void {
    this.stop();
    this.dialog()?.nativeElement.close();
  }

  onClosed(): void {
    if (this.finished) return;
    this.finished = true;
    this.stop();
    this.closed.emit();
  }

  ngOnDestroy(): void {
    this.finished = true;
    this.stop();
    this.dialog()?.nativeElement.close();
  }

  private open(request: ReaderRequest): void {
    if (this.finished) return;
    const version = ++this.documentVersion;
    this.releaseDocument();
    this.status.set('loading');
    this.error.set('');
    this.pageNumber.set(1);
    this.pageCount.set(0);
    this.zoom.set(1);
    this.fullPage.set(false);
    this.stage.set('pages');
    this.soundEnabled.set(false);
    this.sound.disable();
    const ids = [...new Set(request.topics.map((topic) => topic.topicId))];
    const available = new Set(request.book.topics.map((topic) => topic.topicId));
    if (
      request.mode !== 'book' &&
      ((request.mode === 'topic' ? ids.length !== 1 : ids.length < 2) ||
        ids.some((id) => !available.has(id)))
    ) {
      void this.fail(
        new Error(
          'This reading selection is no longer available. Return to the topics and select it again.',
        ),
        version,
      );
      return;
    }
    const response =
      request.mode === 'book'
        ? this.api.readBook(request.book.bookId)
        : this.api.readTopics(
            request.book.bookId,
            sourceOrderedTopics(request.book, request.topics).map((topic) => topic.topicId),
          );
    this.http = response.subscribe({
      next: (response) => void this.load(response, version),
      error: (error: unknown) => void this.fail(error, version),
    });
  }

  private async load(response: HttpResponse<Blob>, version: number): Promise<void> {
    if (version !== this.documentVersion || this.finished) return;
    const blob = response.body;
    if (!blob || blob.size === 0) {
      this.status.set('empty');
      return;
    }
    if (blob.type.includes('json')) {
      await this.fail(new HttpErrorResponse({ error: blob, status: response.status }), version);
      return;
    }
    try {
      this.loadTask = this.engine.load(blob);
      const pdf = await this.loadTask.promise;
      if (version !== this.documentVersion || this.finished) return;
      if (!pdf.pageCount) {
        this.releaseDocument();
        this.status.set('empty');
        return;
      }
      this.pdf = pdf;
      this.pageCount.set(pdf.pageCount);
      this.status.set('ready');
      await this.renderPage();
    } catch (error) {
      await this.fail(error, version);
    }
  }

  private async renderPage(): Promise<boolean> {
    const pdf = this.pdf;
    if (!pdf || this.finished) return false;
    if (this.fullPage() && this.stage() !== 'pages') return true;
    const version = ++this.renderVersion;
    const documentVersion = this.documentVersion;
    const previous = this.renderTask;
    const previousExtra = this.extraRenderTask;
    this.renderTask = null;
    this.extraRenderTask = null;
    previous?.cancel();
    previousExtra?.cancel();
    this.rendering.set(true);
    try {
      await this.renderBarrier;
      await previous?.promise.catch(() => undefined);
      await previousExtra?.promise.catch(() => undefined);
      if (version !== this.renderVersion || this.finished) return false;
      this.page?.cleanup();
      this.extraPage?.cleanup();
      this.extraPage = null;
      this.page = null;
      const numbers = this.visiblePages();
      if (numbers.length === 1) {
        const secondary = this.secondCanvas()?.nativeElement;
        if (secondary) {
          secondary.width = 0;
          secondary.height = 0;
        }
      }
      const page = await pdf.getPage(numbers[0]);
      if (version !== this.renderVersion || this.finished) {
        page.cleanup();
        return false;
      }
      this.page = page;
      const canvas = this.canvas()?.nativeElement;
      if (!canvas) throw new Error('The PDF page canvas is unavailable.');
      const availableWidth = Math.max(
        120,
        ((this.viewport()?.nativeElement.clientWidth || 800) - 40) / numbers.length,
      );
      const availableHeight = Math.max(
        120,
        (this.viewport()?.nativeElement.clientHeight || 650) - 40,
      );
      const widthFor = (value: PdfReaderPage) =>
        this.fullPage()
          ? Math.min(
              availableWidth,
              (availableHeight * value.width) / (value.height || value.width * 1.414),
            )
          : availableWidth * this.zoom();
      const task = page.render(canvas, widthFor(page), 'original');
      this.renderTask = task;
      await task.promise;
      if (version !== this.renderVersion || this.finished) return false;
      if (numbers.length === 2) {
        const second = await pdf.getPage(numbers[1]);
        if (version !== this.renderVersion || this.finished) {
          second.cleanup();
          return false;
        }
        this.extraPage = second;
        const secondCanvas = this.secondCanvas()?.nativeElement;
        if (!secondCanvas) throw new Error('The facing PDF page canvas is unavailable.');
        const extra = second.render(secondCanvas, widthFor(second), 'original');
        this.extraRenderTask = extra;
        await extra.promise;
      }
      return version === this.renderVersion && !this.finished;
    } catch (error) {
      if (version === this.renderVersion && !this.finished) await this.fail(error, documentVersion);
      return false;
    } finally {
      if (version === this.renderVersion) {
        this.renderTask = null;
        this.extraRenderTask = null;
        this.rendering.set(false);
      }
    }
  }

  private async fail(error: unknown, version: number): Promise<void> {
    const message = await apiErrorMessage(error);
    if (version !== this.documentVersion || this.finished) return;
    this.releaseDocument();
    this.error.set(message);
    this.status.set('error');
  }

  private releaseDocument(): void {
    ++this.renderVersion;
    this.http?.unsubscribe();
    this.http = null;
    const render = this.renderTask;
    const extraRender = this.extraRenderTask;
    this.extraRenderTask = null;
    extraRender?.cancel();
    this.renderTask = null;
    render?.cancel();
    const page = this.page;
    const extraPage = this.extraPage;
    this.extraPage = null;
    this.page = null;
    const canvas = this.canvas()?.nativeElement;
    this.renderBarrier = Promise.all([
      render ? render.promise.catch(() => undefined) : this.renderBarrier,
      extraRender?.promise.catch(() => undefined),
    ]).then(() => {
      page?.cleanup();
      extraPage?.cleanup();
      if (canvas) {
        canvas.width = 0;
        canvas.height = 0;
      }
      const second = this.secondCanvas()?.nativeElement;
      if (second) {
        second.width = 0;
        second.height = 0;
      }
    });
    const load = this.loadTask;
    this.loadTask = null;
    if (load) void load.destroy().catch(() => undefined);
    this.pdf = null;
    this.rendering.set(false);
  }

  private stop(): void {
    ++this.documentVersion;
    this.releaseDocument();
    this.animation?.cancel();
    this.animation = null;
    this.soundEnabled.set(false);
    this.sound.disable();
    clearTimeout(this.resizeTimer);
    this.resizeObserver?.disconnect();
    this.resizeObserver = null;
    this.releaseScroll?.();
    this.releaseScroll = null;
  }

  private resetViewport(): void {
    const viewport = this.viewport()?.nativeElement;
    if (viewport) {
      viewport.scrollTop = 0;
      viewport.scrollLeft = 0;
    }
  }
}
