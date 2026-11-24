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
  output,
  signal,
  viewChild,
} from '@angular/core';
import { IconComponent } from '../../../../shared/ui/icon.component';
import { lockLibraryScroll } from '../../../../shared/utils/scroll-lock';
import { BookDownloadService } from '../../data-access/book-download.service';
import type { Book } from '../../models/book.models';

@Component({
  selector: 'app-book-topic-description',
  imports: [IconComponent],
  templateUrl: './book-topic-description.component.html',
  styleUrl: './book-topic-description.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BookTopicDescriptionComponent implements OnDestroy {
  readonly book = input.required<Book>();
  readonly closed = output<void>();
  readonly downloads = inject(BookDownloadService);
  readonly filterText = signal('');
  readonly selectedIds = signal<ReadonlySet<number>>(new Set());
  readonly filteredTopics = computed(() => {
    const query = this.filterText().trim().toLowerCase();
    return this.book().topics.filter(
      (topic) =>
        !query ||
        (topic.topicName ?? '').toLowerCase().includes(query) ||
        (topic.topicDetails ?? '').toLowerCase().includes(query),
    );
  });
  readonly selectedTopics = computed(() =>
    this.book().topics.filter((topic) => this.selectedIds().has(topic.topicId)),
  );
  readonly selectedCount = computed(() => this.selectedTopics().length);
  readonly selectedDownloading = computed(() =>
    this.downloads.isSelectedTopicsDownloading(this.book().bookId),
  );
  readonly anyTopicDownloading = computed(() =>
    this.downloads.isAnyTopicDownloading(this.book().bookId),
  );
  private readonly document = inject(DOCUMENT);
  private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('topicDialog');
  private readonly tableRegion = viewChild<ElementRef<HTMLElement>>('tableRegion');
  private releaseScroll: (() => void) | null = null;
  private finished = false;

  constructor() {
    effect(() => {
      this.book();
      this.resetState();
    });
    afterNextRender(() => {
      this.releaseScroll = lockLibraryScroll(this.document);
      try {
        this.dialog()?.nativeElement.showModal();
      } catch (error) {
        this.releaseScroll();
        this.releaseScroll = null;
        throw error;
      }
    });
  }

  filterChanged(event: Event): void {
    this.filterText.set((event.target as HTMLInputElement).value);
    this.scrollTableToStart();
  }

  applyFilter(event: Event): void {
    event.preventDefault();
    this.filterText.update((value) => value.trim());
    this.scrollTableToStart();
  }

  toggleTopic(topicId: number, event: Event): void {
    if (this.selectedDownloading()) return;
    const checked = (event.target as HTMLInputElement).checked;
    this.selectedIds.update((current) => {
      const next = new Set(current);
      if (checked) next.add(topicId);
      else next.delete(topicId);
      return next;
    });
  }

  downloadSelected(): void {
    if (this.selectedCount() < 2 || this.anyTopicDownloading()) return;
    this.downloads.downloadSelectedTopics(this.book(), this.selectedTopics());
  }

  close(): void {
    this.cleanup();
    this.dialog()?.nativeElement.close();
  }

  onClosed(): void {
    if (this.finished) return;
    this.finished = true;
    this.cleanup();
    this.closed.emit();
  }

  ngOnDestroy(): void {
    this.finished = true;
    this.cleanup();
    this.dialog()?.nativeElement.close();
  }

  private resetState(): void {
    this.filterText.set('');
    this.selectedIds.set(new Set());
  }

  private cleanup(): void {
    this.resetState();
    this.releaseScroll?.();
    this.releaseScroll = null;
  }

  private scrollTableToStart(): void {
    const region = this.tableRegion()?.nativeElement;
    if (region) region.scrollTop = 0;
  }
}
