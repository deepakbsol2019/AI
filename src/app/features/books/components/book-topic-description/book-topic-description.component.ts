import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  input,
  OnDestroy,
  output,
  viewChild,
} from '@angular/core';
import { IconComponent } from '../../../../shared/ui/icon.component';
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
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('topicDialog');
  private finished = false;

  constructor() {
    afterNextRender(() => this.dialog().nativeElement.showModal());
  }

  close(): void {
    this.dialog().nativeElement.close();
  }

  onClosed(): void {
    if (this.finished) return;
    this.finished = true;
    this.closed.emit();
  }

  ngOnDestroy(): void {
    this.finished = true;
    this.dialog().nativeElement.close();
  }
}
