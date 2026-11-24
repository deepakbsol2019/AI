import { ChangeDetectionStrategy, Component } from '@angular/core';
import { IconComponent } from '../../shared/ui/icon.component';

@Component({
  selector: 'app-footer',
  standalone: true,
  imports: [IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<footer class="site-footer">
    <div class="container-xl footer-content">
      <span class="footer-brand"
        ><app-icon name="book" [size]="16" /> Bookhaven <span class="footer-separator">/</span> A
        little curiosity goes a long way.</span
      ><span class="footer-note">Search. Discover. Keep learning.</span>
    </div>
  </footer>`,
  styles: `
    :host {
      display: block;
      margin-top: auto;
    }
    .site-footer {
      background: var(--panel-background);
      border-top: 1px solid #ffffffb3;
    }
    .footer-content {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      min-height: 65px;
      padding: 1rem 1.75rem;
      font-size: 0.69rem;
      color: #66758a;
    }
    .footer-brand {
      display: flex;
      align-items: center;
      gap: 0.55rem;
    }
    .footer-brand app-icon {
      color: #33557e;
    }
    .footer-separator {
      margin-inline: 0.35rem;
      color: #bbc5d1;
    }
    .footer-note {
      color: #768297;
      font-size: 0.66rem;
      letter-spacing: 0.015em;
    }
    @media (max-width: 650px) {
      .footer-content {
        padding: 1rem;
        flex-direction: column;
        justify-content: center;
        gap: 0.5rem;
      }
      .footer-brand {
        font-size: 0.63rem;
      }
      .footer-note {
        font-size: 0.6rem;
      }
    }
  `,
})
export class FooterComponent {}
