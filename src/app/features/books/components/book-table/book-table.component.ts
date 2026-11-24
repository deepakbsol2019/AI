import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { IconComponent } from '../../../../shared/ui/icon.component';
import { BookDownloadService } from '../../data-access/book-download.service';
import type { Book } from '../../models/book.models';
import { MetadataFieldComponent } from '../metadata-field/metadata-field.component';
import { TruncatedTextComponent } from '../../../../shared/ui/truncated-text.component';
import type { MetadataSave } from '../../models/metadata-save';

@Component({
  selector: 'app-book-table',
  imports: [IconComponent, MetadataFieldComponent, TruncatedTextComponent],
  templateUrl: './book-table.component.html',
  styleUrl: './book-table.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BookTableComponent {
  readonly books = input<Book[]>([]);
  readonly busy = input(false);
  readonly viewBook = output<Book>();
  readonly viewTopics = output<Book>();
  readonly metadataSaved = output<MetadataSave>();
  readonly downloads = inject(BookDownloadService);
}
