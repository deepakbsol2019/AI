import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
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
  readonly documentType = input<'pdf' | 'spreadsheet'>('pdf');
  readonly searchSubmitted = output<BookSearchCriteria>();
  readonly searchTextChanged = output<string>();
  readonly resetRequested = output<void>();
  readonly minimizeRequested = output<void>();
  readonly form = new FormGroup({
    query: new FormControl('', { nonNullable: true }),
    bookName: new FormControl('', { nonNullable: true }),
    authorName: new FormControl('', { nonNullable: true }),
    topicName: new FormControl('', { nonNullable: true }),
    bookDetailsContains: new FormControl('', { nonNullable: true }),
    topicDetailsContains: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.controls.query.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe((value) => this.searchTextChanged.emit(value));
  }

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
