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
  private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('readerDialog');
  private readonly canvas = viewChild<ElementRef<HTMLCanvasElement>>('pageCanvas');
  private readonly viewport = viewChild<ElementRef<HTMLElement>>('pageViewport');
  private readonly mounted = signal(false);
  private http: Subscription | null = null;
  private loadTask: PdfLoadHandle | null = null;
  private pdf: PdfReaderDocument | null = null;
  private page: PdfReaderPage | null = null;
  private renderTask: PdfRenderHandle | null = null;
  private renderBarrier: Promise<void> = Promise.resolve();
  private documentVersion = 0;
  private renderVersion = 0;
  private finished = false;
  private releaseScroll: (() => void) | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private resizeTimer: ReturnType<typeof setTimeout> | undefined;

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

  navigate(delta: number): void {
    const next = this.pageNumber() + delta;
    if (this.status() !== 'ready' || this.rendering() || next < 1 || next > this.pageCount())
      return;
    this.pageNumber.set(next);
    this.resetViewport();
    void this.renderPage();
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

  private async renderPage(): Promise<void> {
    const pdf = this.pdf;
    if (!pdf || this.finished) return;
    const version = ++this.renderVersion;
    const documentVersion = this.documentVersion;
    const previous = this.renderTask;
    this.renderTask = null;
    previous?.cancel();
    this.rendering.set(true);
    try {
      await this.renderBarrier;
      await previous?.promise.catch(() => undefined);
      if (version !== this.renderVersion || this.finished) return;
      this.page?.cleanup();
      this.page = null;
      const page = await pdf.getPage(this.pageNumber());
      if (version !== this.renderVersion || this.finished) {
        page.cleanup();
        return;
      }
      this.page = page;
      const canvas = this.canvas()?.nativeElement;
      if (!canvas) throw new Error('The PDF page canvas is unavailable.');
      const availableWidth = Math.max(
        200,
        (this.viewport()?.nativeElement.clientWidth || 800) - 32,
      );
      const task = page.render(canvas, availableWidth * this.zoom(), 'original');
      this.renderTask = task;
      await task.promise;
    } catch (error) {
      if (version === this.renderVersion && !this.finished) await this.fail(error, documentVersion);
    } finally {
      if (version === this.renderVersion) {
        this.renderTask = null;
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
    this.renderTask = null;
    render?.cancel();
    const page = this.page;
    this.page = null;
    const canvas = this.canvas()?.nativeElement;
    this.renderBarrier = (render ? render.promise.catch(() => undefined) : this.renderBarrier).then(
      () => {
        page?.cleanup();
        if (canvas) {
          canvas.width = 0;
          canvas.height = 0;
        }
      },
    );
    const load = this.loadTask;
    this.loadTask = null;
    if (load) void load.destroy().catch(() => undefined);
    this.pdf = null;
    this.rendering.set(false);
  }

  private stop(): void {
    ++this.documentVersion;
    this.releaseDocument();
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
