import { TestBed } from '@angular/core/testing';
import { BookSearchComponent } from './book-search.component';
import type { BookSearchCriteria } from '../../models/book.models';

describe('BookSearchComponent', () => {
  it('emits only entered criteria without changing backend matching values', () => {
    const component = TestBed.runInInjectionContext(() => new BookSearchComponent());
    let result: BookSearchCriteria | undefined;
    component.searchSubmitted.subscribe((value) => (result = value));
    component.form.patchValue({
      query: '  Java  ',
      bookName: '',
      authorName: '   ',
      topicDetailsContains: '100%_literal',
      bookDetailsContains: '0',
    });
    component.submit();
    expect(result).toEqual({
      query: 'Java',
      bookDetailsContains: '0',
      topicDetailsContains: '100%_literal',
    });
  });

  it('clears every filter and requests one default reload on reset', () => {
    const component = TestBed.runInInjectionContext(() => new BookSearchComponent());
    const reset = vi.fn();
    const search = vi.fn();
    component.resetRequested.subscribe(reset);
    component.searchSubmitted.subscribe(search);
    component.form.patchValue({ query: 'Java', topicName: 'Concurrency', authorName: 'Someone' });
    component.reset();
    expect(Object.values(component.form.getRawValue())).toEqual(['', '', '', '', '', '']);
    expect(reset).toHaveBeenCalledTimes(1);
    expect(search).not.toHaveBeenCalled();
  });

  it('requests minimization without submitting, resetting, or changing entered filters', () => {
    TestBed.configureTestingModule({ imports: [BookSearchComponent] });
    const fixture = TestBed.createComponent(BookSearchComponent);
    const minimize = vi.fn();
    const search = vi.fn();
    const reset = vi.fn();
    fixture.componentInstance.minimizeRequested.subscribe(minimize);
    fixture.componentInstance.searchSubmitted.subscribe(search);
    fixture.componentInstance.resetRequested.subscribe(reset);
    fixture.componentInstance.form.patchValue({ query: 'Keep my query', authorName: 'Writer' });
    const before = fixture.componentInstance.form.getRawValue();
    fixture.detectChanges();
    const details = fixture.nativeElement.querySelector('details') as HTMLDetailsElement;
    details.open = true;
    const button = fixture.nativeElement.querySelector('.minimize-search') as HTMLButtonElement;
    expect(button.type).toBe('button');
    expect(button.getAttribute('aria-label')).toBe('Minimize Search');
    expect(button.disabled).toBe(false);
    button.click();
    expect(minimize).toHaveBeenCalledTimes(1);
    expect(search).not.toHaveBeenCalled();
    expect(reset).not.toHaveBeenCalled();
    expect(fixture.componentInstance.form.getRawValue()).toEqual(before);
    expect(details.open).toBe(true);
  });
});
