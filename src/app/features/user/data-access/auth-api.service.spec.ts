import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AuthApiService } from './auth-api.service';

describe('Auth API browser contract', () => {
  let api: AuthApiService;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(AuthApiService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('gets CSRF before password login, sends credentials as a body and includes cookies', async () => {
    const result = firstValueFrom(api.login('reader@example.com', 'a long password', '123456'));
    const csrf = http.expectOne('/api/user/auth/csrf');
    expect(csrf.request.withCredentials).toBe(true);
    csrf.flush({ token: 'csrf-1', headerName: 'X-XSRF-TOKEN' });
    const login = http.expectOne('/api/user/auth/login');
    expect(login.request.body).toEqual({
      email: 'reader@example.com',
      password: 'a long password',
      mfaCode: '123456',
    });
    expect(login.request.headers.get('X-XSRF-TOKEN')).toBe('csrf-1');
    expect(login.request.withCredentials).toBe(true);
    login.flush({});
    await result;
  });
  it('refresh has an empty body and never exposes a refresh secret', async () => {
    const result = firstValueFrom(api.refresh());
    http.expectOne('/api/user/auth/csrf').flush({ token: 'csrf-2', headerName: 'X-XSRF-TOKEN' });
    const refresh = http.expectOne('/api/user/auth/refresh');
    expect(refresh.request.body).toEqual({});
    expect(refresh.request.withCredentials).toBe(true);
    refresh.flush({});
    await result;
  });
});
