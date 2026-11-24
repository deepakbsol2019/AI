import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MetadataApiService } from './metadata-api.service';

describe('MetadataApiService', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('patches exactly one nullable book field without replacing unrelated metadata', () => {
    TestBed.inject(MetadataApiService).patchBook(17, 'authorName', null).subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne('/api/books/17/metadata');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ authorName: null });
    request.flush({ bookId: 17 });
  });

  it('patches the selected topic and preserves complete paragraph text', () => {
    const paragraphs = '  First paragraph.\n\nSecond paragraph.  ';
    TestBed.inject(MetadataApiService).patchTopic(17, 31, 'topicDetails', paragraphs).subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne(
      '/api/books/17/topics/31/metadata',
    );
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ topicDetails: paragraphs });
    request.flush({ bookId: 17 });
  });
});
