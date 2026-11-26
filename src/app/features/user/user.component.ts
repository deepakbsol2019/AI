import { ChangeDetectionStrategy, Component, ViewEncapsulation, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ApplicationLayoutComponent } from '../../layout/application-layout/application-layout.component';
import { Router } from '@angular/router';

@Component({
  selector: 'app-user',
  standalone: true,
  imports: [RouterOutlet, ApplicationLayoutComponent],
  template: `<app-application-layout [libraryControls]="false"
    ><main
      id="main-content"
      tabindex="-1"
      class="auth-card"
      [class.management-page]="managementPage()"
    >
      <router-outlet /></main
  ></app-application-layout>`,
  styleUrl: './user.component.css',
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserComponent {
  private readonly router = inject(Router);
  managementPage(): boolean {
    return /^\/user\/(users|roles)(\/|\?|$)/.test(this.router.url);
  }
}
