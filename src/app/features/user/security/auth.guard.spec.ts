import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthSessionService } from '../services/auth-session.service';
import { authGuard } from './auth.guard';

@Component({ template: 'Protected books' })
class ProtectedPage {}
@Component({ template: 'Login' })
class LoginPage {}

describe('Book route guard', () => {
  it('redirects an unauthenticated visitor before creating protected Home', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'user/login', component: LoginPage },
          { path: '', component: ProtectedPage, canMatch: [authGuard] },
        ]),
        {
          provide: AuthSessionService,
          useValue: { token: () => Promise.reject(new Error('Not authenticated')) },
        },
      ],
    });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/', LoginPage);
    expect(TestBed.inject(Router).url).toBe('/user/login');
    expect(harness.routeNativeElement?.textContent).toBe('Login');
  });
  it('allows current session navigation', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '', component: ProtectedPage, canMatch: [authGuard] }]),
        { provide: AuthSessionService, useValue: { token: () => Promise.resolve('access') } },
      ],
    });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/', ProtectedPage);
    expect(harness.routeNativeElement?.textContent).toBe('Protected books');
  });
});
