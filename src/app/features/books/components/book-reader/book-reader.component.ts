import { DOCUMENT, NgTemplateOutlet } from '@angular/common';
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

interface CapturedTurn {
  direction: -1 | 1;
  mode: ReaderPageMode;
  front: HTMLCanvasElement;
  fixed: HTMLCanvasElement | null;
}

@Component({
  selector: 'app-book-reader',
  imports: [IconComponent, NotificationOutletComponent, NgTemplateOutlet],
  providers: [PageTurnSoundService],
  templateUrl: './book-reader.component.html',
  styleUrl: './book-reader.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BookReaderComponent implements OnDestroy {
  private static nextColorFilter = 0;
  readonly colorFilterId = `reader-colors-${++BookReaderComponent.nextColorFilter}`;
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
  readonly backgroundColor = signal('#ffffff');
  readonly textColor = signal('#172b46');
  readonly colorChannels = computed(() =>
    [1, 3, 5].map(
      (start) =>
        `${parseInt(this.textColor().slice(start, start + 2), 16) / 255} ${parseInt(this.backgroundColor().slice(start, start + 2), 16) / 255}`,
    ),
  );
  readonly colorfulFilter = computed(() =>
    this.pageTheme() === 'colorful' ? `url(#${this.colorFilterId})` : null,
  );
  readonly fullPage = signal(false);
  readonly pageMode = signal<ReaderPageMode>(
    typeof window !== 'undefined' && window.innerWidth > 700 ? 'double' : 'single',
  );
  readonly stage = signal<ReaderStage>('pages');
  readonly displayStage = signal<ReaderStage>('pages');
  readonly displayedPages = signal<readonly number[]>([1]);
  readonly blankPageSize = signal({ width: 0, height: 0 });
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
  private turnSnapshot: CapturedTurn | null = null;
  private lastRenderKey = '';
  private observedSize = '';
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
          // Observe the fixed viewport box, not canvas/scrollbar-driven content sizes.
          const size = this.viewportSize().join('x');
          if (size === this.observedSize) return;
          this.observedSize = size;
          clearTimeout(this.resizeTimer);
          this.resizeTimer = setTimeout(() => {
            this.animation?.cancel();
            this.clearTurn();
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
    if (oldStage === 'pages' && next.stage === 'pages') this.captureTurn(direction);
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
      if (this.soundEnabled()) this.sound.turn();
    }
    this.animateBook(
      direction,
      next.stage === 'back' ? 'close' : oldStage === 'front' ? 'open' : 'turn',
    );
  }

  async setPageMode(mode: ReaderPageMode): Promise<void> {
    if (mode === this.pageMode() || this.rendering()) return;
    this.animation?.cancel();
    this.clearTurn();
    ++this.positionVersion;
    this.pageMode.set(mode);
    this.resetViewport();
    await this.renderPage();
  }
  async enterFullPage(): Promise<void> {
    if (this.status() !== 'ready' || this.rendering() || this.fullPage()) return;
    this.animation?.cancel();
    this.clearTurn();
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
    this.displayStage.set('front');
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
    this.clearTurn();
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
    ) {
      this.clearTurn();
      return;
    }
    this.animation?.cancel();
    if (kind === 'turn' && this.turnSnapshot) {
      const turn = this.turnSnapshot;
      const layer = this.turnElement('div', 'turn-layer');
      layer.setAttribute('aria-hidden', 'true');
      const first = this.canvas()?.nativeElement;
      const second = this.secondCanvas()?.nativeElement;
      let sheet: HTMLElement;
      let frames: Keyframe[];
      if (turn.mode === 'double' && first) {
        // Both directions share the seam between the two slots, including an odd last spread.
        const spine = first.offsetLeft + this.canvasWidth(first) + 1;
        layer.style.perspectiveOrigin = `${spine}px 50%`;
        const backSource = direction === 1 ? first : second;
        const leaf = this.turnElement('div', 'turning-leaf');
        leaf.dataset['direction'] = direction === 1 ? 'next' : 'previous';
        leaf.style.width = turn.front.style.width || `${turn.front.width}px`;
        leaf.style.height = turn.front.style.height || `${turn.front.height}px`;
        leaf.style.top = `${first.offsetTop}px`;
        leaf.style.left = `${direction === 1 ? spine : spine - this.canvasWidth(turn.front)}px`;
        leaf.style.transformOrigin = direction === 1 ? 'left center' : 'right center';
        turn.front.className += ' turn-front';
        const back =
          backSource && (direction === 1 || this.displayedPages().length === 2)
            ? this.copyCanvas(backSource)
            : this.blankCanvas(turn.front);
        back.className += ' turn-back';
        for (const face of [turn.front, back]) {
          face.style.left = '0';
          face.style.top = '0';
          face.style.width = '100%';
          face.style.height = '100%';
        }
        leaf.append(turn.front, back);
        if (turn.fixed) layer.appendChild(turn.fixed);
        layer.appendChild(leaf);
        sheet = leaf;
        frames = [
          { transform: 'rotateY(0deg)' },
          { transform: `rotateY(${direction * -90}deg)`, offset: 0.5 },
          { transform: `rotateY(${direction * -180}deg)` },
        ];
      } else if (direction === -1 && first) {
        // Bring the previous page into the page box; never rotate the current page outwards.
        layer.appendChild(turn.front);
        sheet = this.copyCanvas(first);
        sheet.className += ' turning-sheet incoming-previous';
        sheet.style.left = `${first.offsetLeft}px`;
        sheet.style.top = `${first.offsetTop}px`;
        sheet.style.transformOrigin = 'left center';
        layer.appendChild(sheet);
        frames = [{ transform: 'rotateY(-90deg)' }, { transform: 'rotateY(0deg)' }];
      } else {
        // Preserve the established single-page Next motion.
        sheet = turn.front;
        sheet.style.transformOrigin = 'left center';
        layer.appendChild(sheet);
        frames = [
          { transform: 'perspective(1600px) rotateY(0deg)', opacity: 1 },
          { transform: 'perspective(1600px) rotateY(-65deg)', opacity: 1, offset: 0.8 },
          { transform: 'perspective(1600px) rotateY(-95deg)', opacity: 0 },
        ];
      }
      element.appendChild(layer);
      this.animation = sheet.animate(frames, {
        duration: 580,
        easing: 'cubic-bezier(.22,.61,.36,1)',
        fill: 'forwards',
      });
      void this.animation.finished?.then(
        () => layer.remove(),
        () => layer.remove(),
      );
      this.turnSnapshot = null;
      return;
    }
    const angle = kind === 'close' ? direction * 12 : direction * -10;
    this.animation = element.animate(
      [
        { transform: `perspective(1600px) rotateY(${angle}deg) scale(.985)`, opacity: 0.82 },
        { transform: 'perspective(1600px) rotateY(0deg) scale(1)', opacity: 1 },
      ],
      { duration: kind === 'turn' ? 580 : 520, easing: 'ease-in-out' },
    );
  }

  private captureTurn(direction: -1 | 1): void {
    this.animation?.cancel();
    this.clearTurn();
    const first = this.canvas()?.nativeElement;
    const second = this.secondCanvas()?.nativeElement;
    const source = direction === 1 && this.displayedPages().length === 2 ? second : first;
    if (!source || !source.width || !this.surface()?.nativeElement.animate) return;
    const fixedSource = this.pageMode() === 'double' ? (direction === 1 ? first : second) : null;
    this.turnSnapshot = {
      direction,
      mode: this.pageMode(),
      front: this.copyCanvas(source),
      fixed:
        fixedSource && (direction === 1 || this.displayedPages().length === 2)
          ? this.copyCanvas(fixedSource)
          : null,
    };
  }

  private canvasWidth(canvas: HTMLCanvasElement): number {
    return Number.parseFloat(canvas.style.width) || canvas.offsetWidth || canvas.width;
  }

  private turnElement<K extends keyof HTMLElementTagNameMap>(
    tag: K,
    className: string,
  ): HTMLElementTagNameMap[K] {
    const element = this.document.createElement(tag);
    element.className = className;
    // Manually created animation nodes need the same Angular style scope as the live pages.
    for (const name of this.surface()?.nativeElement.getAttributeNames() || [])
      if (name.startsWith('_ngcontent-')) element.setAttribute(name, '');
    return element;
  }

  private copyCanvas(source: HTMLCanvasElement): HTMLCanvasElement {
    const copy = this.turnElement('canvas', `${source.className} turning-sheet`);
    copy.width = source.width;
    copy.height = source.height;
    copy.getContext('2d')?.drawImage(source, 0, 0);
    copy.style.cssText = source.style.cssText;
    copy.style.left = `${source.offsetLeft}px`;
    copy.style.top = `${source.offsetTop}px`;
    copy.setAttribute('aria-hidden', 'true');
    return copy;
  }

  private blankCanvas(source: HTMLCanvasElement): HTMLCanvasElement {
    const blank = this.turnElement('canvas', source.className);
    blank.width = source.width;
    blank.height = source.height;
    blank.style.cssText = source.style.cssText;
    return blank;
  }

  private clearTurn(): void {
    this.turnSnapshot = null;
    this.surface()
      ?.nativeElement.querySelectorAll('.turn-layer')
      .forEach((sheet) => sheet.remove());
  }

  changeZoom(delta: number): void {
    const zoom = Math.round(Math.min(1.25, Math.max(0.5, this.zoom() + delta)) * 100) / 100;
    if (zoom === this.zoom()) return;
    this.animation?.cancel();
    this.clearTurn();
    this.zoom.set(zoom);
    void this.renderPage();
  }

  fitWidth(): void {
    this.animation?.cancel();
    this.clearTurn();
    this.zoom.set(1);
    this.resetViewport();
    void this.renderPage();
  }

  changeTheme(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    if (value !== 'dark' && value !== 'original' && value !== 'inverted' && value !== 'colorful')
      return;
    this.pageTheme.set(value === 'inverted' ? 'dark' : value);
    this.animation?.cancel();
    this.clearTurn();
  }

  changeColor(event: Event, target: 'background' | 'text'): void {
    const color = (event.target as HTMLInputElement).value;
    if (!/^#[0-9a-f]{6}$/i.test(color)) return;
    this.animation?.cancel();
    this.clearTurn();
    (target === 'background' ? this.backgroundColor : this.textColor).set(color);
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
    if (this.fullPage() && this.stage() !== 'pages') {
      this.displayStage.set(this.stage());
      return true;
    }
    const numbers = this.visiblePages();
    const [viewportWidth, viewportHeight] = this.viewportSize();
    const key = [
      this.documentVersion,
      this.fullPage(),
      this.pageMode(),
      numbers.join(','),
      this.zoom(),
      viewportWidth,
      viewportHeight,
    ].join('/');
    if (key === this.lastRenderKey && !this.rendering()) {
      this.displayStage.set('pages');
      return true;
    }
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
      const pages = await Promise.all(numbers.map((number) => pdf.getPage(number)));
      if (version !== this.renderVersion || this.finished) {
        pages.forEach((page) => page.cleanup());
        return false;
      }
      this.page = pages[0];
      this.extraPage = pages[1] || null;
      const availableWidth = Math.max(40, (viewportWidth - 56) / numbers.length);
      const availableHeight = Math.max(40, viewportHeight - 56);
      const widthFor = (value: PdfReaderPage) =>
        (this.fullPage()
          ? Math.min(
              availableWidth,
              (availableHeight * value.width) / (value.height || value.width * 1.414),
            )
          : availableWidth) * this.zoom();
      // PDF.js resets a target bitmap before painting. Stage the entire spread so the
      // displayed pages and their geometry stay intact until every facing page is ready.
      const staged = pages.map(() => this.document.createElement('canvas'));
      const tasks = pages.map((page, index) => {
        const task = page.render(staged[index], widthFor(page), 'original');
        if (index === 0) this.renderTask = task;
        else this.extraRenderTask = task;
        // A second render may throw synchronously before Promise.all is constructed.
        void task.promise.catch(() => undefined);
        return task;
      });
      await Promise.all(tasks.map((task) => task.promise));
      if (version !== this.renderVersion || this.finished) return false;
      const targets = [this.canvas()?.nativeElement, this.secondCanvas()?.nativeElement];
      const contexts = staged.map((_, index) => targets[index]?.getContext('2d'));
      if (contexts.some((context) => !context))
        throw new Error('This browser could not display the PDF canvas.');
      staged.forEach((source, index) => {
        const target = targets[index]!;
        target.width = source.width;
        target.height = source.height;
        target.style.width = source.style.width;
        target.style.height = source.style.height;
        contexts[index]!.drawImage(source, 0, 0);
      });
      this.displayedPages.set(numbers);
      this.blankPageSize.set({
        width: Number.parseFloat(targets[0]?.style.width || '0'),
        height: Number.parseFloat(targets[0]?.style.height || '0'),
      });
      this.displayStage.set('pages');
      this.lastRenderKey = key;
      this.observedSize = [viewportWidth, viewportHeight].join('x');
      return true;
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

  private viewportSize(): [number, number] {
    const viewport = this.viewport()?.nativeElement;
    // The outer box does not oscillate as inner scrollbars appear/disappear.
    return [
      viewport?.offsetWidth || viewport?.clientWidth || 800,
      viewport?.offsetHeight || viewport?.clientHeight || 650,
    ];
  }

  private async fail(error: unknown, version: number): Promise<void> {
    const message = await apiErrorMessage(error);
    if (version !== this.documentVersion || this.finished) return;
    this.releaseDocument();
    this.error.set(message);
    this.status.set('error');
  }

  private releaseDocument(): void {
    this.animation?.cancel();
    this.clearTurn();
    ++this.renderVersion;
    this.lastRenderKey = '';
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
    this.clearTurn();
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
