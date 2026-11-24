import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { HeaderComponent } from '../header/header.component';
import { FooterComponent } from '../footer/footer.component';

@Component({
  selector: 'app-application-layout',
  standalone: true,
  imports: [HeaderComponent, FooterComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<a class="skip-link" href="#main-content">Skip to books</a
    ><app-header
      [uploadBusy]="uploadBusy()"
      (fileSelected)="fileSelected.emit($event)"
    /><ng-content /><app-footer />`,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      min-height: 100dvh;
    }
  `,
})
export class ApplicationLayoutComponent {
  readonly uploadBusy = input(false);
  readonly fileSelected = output<File>();
}
