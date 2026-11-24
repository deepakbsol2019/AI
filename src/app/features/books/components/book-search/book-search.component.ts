import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { IconComponent } from '../../../../shared/ui/icon.component';
import type { BookSearchCriteria } from '../../models/book.models';

@Component({
  selector: 'app-book-search',
  imports: [ReactiveFormsModule, IconComponent],
  templateUrl: './book-search.component.html',
  styleUrl: './book-search.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BookSearchComponent {
  readonly busy = input(false);
  readonly searchSubmitted = output<BookSearchCriteria>();
  readonly resetRequested = output<void>();
  readonly form = new FormGroup({
    query: new FormControl('', { nonNullable: true }),
    bookName: new FormControl('', { nonNullable: true }),
    authorName: new FormControl('', { nonNullable: true }),
    topicName: new FormControl('', { nonNullable: true }),
    bookDetailsContains: new FormControl('', { nonNullable: true }),
    topicDetailsContains: new FormControl('', { nonNullable: true }),
  });

  submit(): void {
    const values = this.form.getRawValue();
    const criteria: BookSearchCriteria = {};
    for (const key of Object.keys(values) as (keyof typeof values)[]) {
      const value = values[key].trim();
      if (value !== '') criteria[key] = value;
    }
    this.searchSubmitted.emit(criteria);
  }

  reset(): void {
    this.form.reset();
    this.resetRequested.emit();
  }
}
