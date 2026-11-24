import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { HeaderComponent } from '../header/header.component';
import { FooterComponent } from '../footer/footer.component';

@Component({
  selector: 'app-application-layout',
  standalone: true,
  imports: [HeaderComponent, FooterComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<a class="skip-link" href="#main-content">Skip to books</a
    ><app-header [uploadBusy]="uploadBusy()" (fileSelected)="fileSelected.emit($event)" />
    <div
      class="library-scroll-region"
      data-library-scroll-region
      tabindex="0"
      role="region"
      aria-label="Library search and book results"
    >
      <ng-content />
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
    .library-scroll-region {
      flex: 1 1 0;
      min-height: 0;
      min-width: 0;
      overflow-x: hidden;
      overflow-y: auto;
      overscroll-behavior-y: contain;
      scrollbar-gutter: stable;
    }
  `,
})
export class ApplicationLayoutComponent {
  readonly uploadBusy = input(false);
  readonly fileSelected = output<File>();
}
