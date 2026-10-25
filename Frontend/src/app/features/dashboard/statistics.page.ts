import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { BusinessOverview } from './business-overview';
@Component({
  selector: 'g-statistics-page',
  imports: [RouterLink, BusinessOverview],
  template: `<header class="welcome">
      <div>
        <p class="shop-name">{{ context.selected()?.name }}</p>
        <h1>Statistiques</h1>
        <p>Comprendre l’évolution de vos ventes d’articles.</p>
      </div>
      <a class="text-link" routerLink="/app/accueil">Retour à l’accueil</a>
    </header>
    <g-business-overview />`,
  styleUrl: './dashboard.page.scss',
})
export class StatisticsPage {
  readonly context = inject(BoutiqueContextService);
}
