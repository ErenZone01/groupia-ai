import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { navigation } from '../config/navigation';
import { Brand } from '../../shared/ui/brand';
import { Icon } from '../../shared/ui/icon';
import { Avatar } from '../../shared/ui/avatar';
@Component({
  selector: 'g-sidebar',
  imports: [RouterLink, RouterLinkActive, Brand, Icon, Avatar],
  template: `<aside>
    <a class="brand" routerLink="/accueil" aria-label="Groupia, accueil"><g-brand /></a>
    <p class="tagline">Les groupages qui rapprochent<br />les opportunités.</p>
    <p class="eyebrow">Votre espace</p>
    <nav aria-label="Navigation principale">
      @for (item of navigation; track item.path) {
        <a [routerLink]="item.path" routerLinkActive="active" ariaCurrentWhenActive="page"
          ><g-icon [name]="item.icon" />{{ item.label }}</a
        >
      }
    </nav>
    <div class="together">
      <g-icon name="spark" />
      <p>Ensemble,<br /><strong>on va plus loin.</strong></p>
      <span>Une boutique. Mille opportunités.</span>
    </div>
    <a routerLink="/boutique" class="shop"
      ><g-avatar name="Ma boutique" /><span
        ><strong>Ma boutique</strong><small>Espace gérante</small></span
      ><g-icon name="chevron"
    /></a>
  </aside>`,
  styleUrl: './sidebar.scss',
})
export class Sidebar {
  readonly navigation = navigation;
}
