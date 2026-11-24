import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { IconComponent } from '../../shared/ui/icon.component';

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [RouterLink, IconComponent],
  templateUrl: './header.component.html',
  styleUrl: './header.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HeaderComponent {
  readonly uploadBusy = input(false);
  readonly searchMinimized = input(false);
  readonly collectionMinimized = input(false);
  readonly fileSelected = output<File>();
  readonly restoreSearch = output<void>();
  readonly restoreCollection = output<void>();

  protected restoreSearchSection(): void {
    if (this.searchMinimized()) this.restoreSearch.emit();
  }

  protected restoreCollectionSection(): void {
    if (this.collectionMinimized()) this.restoreCollection.emit();
  }

  protected chooseFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.item(0);
    input.value = '';
    if (file && !this.uploadBusy()) this.fileSelected.emit(file);
  }
}
