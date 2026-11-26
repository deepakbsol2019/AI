import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AuthSessionService } from '../services/auth-session.service';
import { authInterceptor } from './auth.interceptor';

describe('Auth interceptor', () => {
  let client: HttpClient;
  let http: HttpTestingController;
  let session: { token: ReturnType<typeof vi.fn>; invalidate: ReturnType<typeof vi.fn> };
  beforeEach(() => {
    session = { token: vi.fn(() => Promise.resolve('access-1')), invalidate: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthSessionService, useValue: session },
      ],
    });
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('adds bearer only to the configured API and retries a protected 401 exactly once', async () => {
    const result = firstValueFrom(client.get('/api/search/books'));
    await Promise.resolve();
    const first = http.expectOne('/api/search/books');
    expect(first.request.headers.get('Authorization')).toBe('Bearer access-1');
    expect(first.request.withCredentials).toBe(true);
    session.token.mockResolvedValue('access-2');
    first.flush({}, { status: 401, statusText: 'Unauthorized' });
    await Promise.resolve();
    const second = http.expectOne('/api/search/books');
    expect(second.request.headers.get('Authorization')).toBe('Bearer access-2');
    second.flush({ ok: true });
    expect(await result).toEqual({ ok: true });
    expect(session.token).toHaveBeenLastCalledWith(true, 'access-1');
  });
  it('does not refresh for permission denied or a second 401', async () => {
    const forbidden = firstValueFrom(client.get('/api/search/books')).catch(
      (error) => error as unknown,
    );
    await Promise.resolve();
    http.expectOne('/api/search/books').flush({}, { status: 403, statusText: 'Forbidden' });
    await forbidden;
    expect(session.token).toHaveBeenCalledTimes(1);
    expect(session.invalidate).not.toHaveBeenCalled();
    const unauthorized = firstValueFrom(client.get('/api/search/books')).catch(
      (error) => error as unknown,
    );
    await Promise.resolve();
    http.expectOne('/api/search/books').flush({}, { status: 401, statusText: 'Unauthorized' });
    await Promise.resolve();
    http.expectOne('/api/search/books').flush({}, { status: 401, statusText: 'Unauthorized' });
    await unauthorized;
    expect(session.token).toHaveBeenCalledTimes(3);
    expect(session.invalidate).toHaveBeenCalledTimes(1);
  });
  it('never intercepts auth endpoints or external assets', async () => {
    for (const url of [
      '/api/user/auth/login',
      '/assets/pdfjs/pdf.worker.min.mjs',
      'https://example.com/api/search/books',
      '/api-other/books',
    ]) {
      const result = firstValueFrom(client.get(url));
      const request = http.expectOne(url);
      expect(request.request.headers.has('Authorization')).toBe(false);
      request.flush({});
      await result;
    }
    expect(session.token).not.toHaveBeenCalled();
  });
  it('includes fresh CSRF on a protected mutation', async () => {
    const result = firstValueFrom(client.patch('/api/books/7/metadata', { bookType: 'Physics' }));
    await Promise.resolve();
    http
      .expectOne('/api/user/auth/csrf')
      .flush({ token: 'csrf-value', headerName: 'X-XSRF-TOKEN' });
    const request = http.expectOne('/api/books/7/metadata');
    expect(request.request.headers.get('X-XSRF-TOKEN')).toBe('csrf-value');
    expect(request.request.body).toEqual({ bookType: 'Physics' });
    request.flush({});
    await result;
  });
});
