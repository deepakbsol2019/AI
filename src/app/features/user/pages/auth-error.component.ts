import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
@Component({
  selector: 'app-auth-error',
  standalone: true,
  imports: [RouterLink],
  template: `<h1>Login unavailable</h1>
    <p role="alert">
      The provider could not complete login. Your account may require setup, or the provider may be
      disabled. Contact your administrator if this continues.
    </p>
    <a class="btn btn-primary" routerLink="/user/login">Back to login</a>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthErrorComponent {}
