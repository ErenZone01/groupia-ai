import { Component, inject } from '@angular/core';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { AuthService } from '../../core/auth/auth.service';
import { Card } from '../../shared/ui/card';
import { Button } from '../../shared/ui/button';
import { Select } from '../../shared/forms/select';
import { Router, RouterLink } from '@angular/router';
import { statusLabel } from '../../shared/utils/display';
@Component({
  selector: 'g-boutique-page',
  imports: [Card, Button, Select, RouterLink],
  template: ` <div class="page-heading">
      <div>
        <p class="eyebrow">Votre espace</p>
        <h1>Ma boutique</h1>
      </div>
    </div>
    <g-card
      ><h2>{{ context.selected()?.name }}</h2>
      <p>{{ statusLabel(context.selected()?.status) }}</p>
      @if (context.boutiques().length > 1) {
        <label class="form-stack" for="shop-select"
          >Changer de boutique<select
            gSelect
            id="shop-select"
            [value]="context.selectedId()"
            (change)="select($any($event.target).value)"
          >
            @for (shop of context.boutiques(); track shop.id) {
              <option [value]="shop.id">{{ shop.name }}</option>
            }
          </select></label
        >
      }
      <div class="actions">
        <a gButton variant="secondary" routerLink="/app/versements">Mes versements</a>
        <a gButton variant="secondary" routerLink="/app/abonnement">Mon abonnement</a>
        <a gButton routerLink="/app/products">Voir les produits</a
        ><a gButton variant="secondary" routerLink="/app/vente-directe">Gérer la vente directe</a
        ><button gButton variant="secondary" (click)="auth.logout()">Se déconnecter</button>
      </div></g-card
    >`,
})
export class BoutiquePage {
  readonly context = inject(BoutiqueContextService);
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly statusLabel = statusLabel;
  select(id: string) {
    this.context.select(id);
    void this.router.navigateByUrl('/app/accueil');
  }
}
