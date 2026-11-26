import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  input,
  output,
  signal,
  DestroyRef,
} from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HeaderComponent } from '../header/header.component';
import { FooterComponent } from '../footer/footer.component';
import { SidebarComponent } from '../sidebar/sidebar.component';
import { AuthSessionService } from '../../features/user/services/auth-session.service';
import { CurrentProfileService } from '../../features/user/services/current-profile.service';

@Component({
  selector: 'app-application-layout',
  standalone: true,
  imports: [HeaderComponent, FooterComponent, SidebarComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<a class="skip-link" href="#main-content">Skip to main content</a
    ><app-header
      [libraryControls]="libraryControls()"
      [sidebarAvailable]="!!session.user()"
      [sidebarExpanded]="sidebarExpanded()"
      [profileUsername]="profiles.profile()?.username || session.user()?.email || ''"
      [profileName]="profiles.profile()?.displayName || session.user()?.displayName || ''"
      (sidebarToggle)="sidebarExpanded.set(!sidebarExpanded())"
      [uploadBusy]="uploadBusy()"
      [searchMinimized]="searchMinimized()"
      [collectionMinimized]="collectionMinimized()"
      (fileSelected)="fileSelected.emit($event)"
      (restoreSearch)="restoreSearch.emit()"
      (restoreCollection)="restoreCollection.emit()"
    />
    <div class="application-middle">
      @if (session.user()) {
        <aside
          id="application-sidebar"
          aria-label="Account and workspace navigation"
          [hidden]="!sidebarExpanded()"
        >
          <app-sidebar (collapse)="collapseSidebar()" (navigate)="sidebarNavigation()" />
        </aside>
      }
      <div
        class="library-scroll-region"
        [class.library-workspace]="libraryControls()"
        data-library-scroll-region
        tabindex="0"
        role="region"
        [attr.aria-label]="
          libraryControls() ? 'Library search and book results' : 'Main page content'
        "
      >
        <ng-content />
      </div>
    </div>
    <app-footer />`,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      height: 100vh;
      height: 100dvh;
      min-height: 0;
      overflow: hidden;
    }
    app-header,
    app-footer {
      flex: 0 0 auto;
    }
    .application-middle {
      display: flex;
      flex: 1 1 0;
      min-height: 0;
      min-width: 0;
      overflow: hidden;
      position: relative;
    }
    aside {
      flex: 0 0 16rem;
      min-height: 0;
      min-width: 0;
      overflow: hidden;
    }
    aside[hidden] {
      display: none;
    }
    .library-scroll-region {
      display: flex;
      flex-direction: column;
      flex: 1 1 0;
      min-height: 0;
      min-width: 0;
      overflow-x: hidden;
      overflow-y: auto;
      overscroll-behavior-y: contain;
    }
    .library-workspace {
      overflow-y: auto;
    }
    @media (max-width: 767px) {
      aside {
        position: absolute;
        inset-block: 0;
        left: 0;
        width: min(18rem, 88%);
        z-index: 700;
        box-shadow: 5px 0 25px #102d5230;
      }
    }
    @media (max-height: 359px) {
      .library-scroll-region {
        overflow-y: auto;
      }
    }
  `,
})
export class ApplicationLayoutComponent {
  protected readonly session = inject(AuthSessionService);
  protected readonly profiles = inject(CurrentProfileService);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  readonly libraryControls = input(true);
  readonly sidebarExpanded = signal(false);
  readonly uploadBusy = input(false);
  readonly searchMinimized = input(false);
  readonly collectionMinimized = input(false);
  readonly fileSelected = output<File>();
  readonly restoreSearch = output<void>();
  readonly restoreCollection = output<void>();
  constructor() {
    inject(ActivatedRoute)
      .queryParamMap.pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe((params) => {
        if (params.get('profile') === 'open') this.sidebarExpanded.set(true);
      });
  }
  collapseSidebar() {
    this.sidebarExpanded.set(false);
    queueMicrotask(() =>
      this.element.nativeElement.querySelector<HTMLButtonElement>('.sidebar-toggle')?.focus(),
    );
  }
  sidebarNavigation() {
    if (typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 767px)').matches)
      this.collapseSidebar();
  }
}
