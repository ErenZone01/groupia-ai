import { Component, effect, inject } from '@angular/core';
import { AuthService } from '../auth/auth.service';
import { SaspayIncidentAccess } from '../auth/saspay-incident-access';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { sidebarNavigation } from '../config/navigation';
import { Brand } from '../../shared/ui/brand';
import { Icon } from '../../shared/ui/icon';
import { Avatar } from '../../shared/ui/avatar';
import { BoutiqueContextService } from '../boutique/boutique-context.service';

@Component({
  selector: 'g-sidebar',
  imports: [RouterLink, RouterLinkActive, Brand, Icon, Avatar],
  template: `<aside>
    <a class="brand" routerLink="/app/accueil" aria-label="Groupia, accueil"><g-brand /></a>
    <div class="navigation-scroll">
      <nav aria-label="Navigation principale">
        @for (section of sections; track section.id) {
          <div class="nav-section">
            <h2 class="eyebrow">{{ section.label }}</h2>
            @for (item of section.items; track item.path) {
              <a
                [routerLink]="item.path"
                routerLinkActive="active"
                [routerLinkActiveOptions]="{ exact: false }"
                ariaCurrentWhenActive="page"
              ><g-icon [name]="item.icon" />{{ item.label }}</a>
            }
          </div>
        }
        @if (incidents.allowed()) {
          <a routerLink="/app/admin/incidents-saspay" routerLinkActive="active" ariaCurrentWhenActive="page"
            class="incident-admin-link"><g-icon name="card" />Administration financière</a>
        }
      </nav>
    </div>
    <a routerLink="/app/boutique" class="shop"
      ><g-avatar name="Ma boutique" /><span
        ><strong>{{ context.selected()?.name || 'Ma boutique' }}</strong
        ><small>Espace gérante</small></span
      ><g-icon name="chevron"
    /></a>
  </aside>`,
  styleUrl: './sidebar.scss',
})
export class Sidebar {
  readonly sections = sidebarNavigation;
  readonly context = inject(BoutiqueContextService);
  readonly incidents = inject(SaspayIncidentAccess);
  private readonly auth = inject(AuthService);
  constructor() {
    effect(() => {
      this.auth.generation();
      if (this.auth.signedIn()) void this.incidents.refresh();
      else this.incidents.reset();
    });
  }
}
