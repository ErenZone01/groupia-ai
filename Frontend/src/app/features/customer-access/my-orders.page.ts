import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { PublicShell } from '../checkout/public-shell';
import { Button } from '../../shared/ui/button';
import { CustomerAccessService } from './customer-access.service';
import { MyOrdersPage as OrdersResult } from '../../api/generated/models';
import { money, dateLabel } from '../../shared/utils/display';
import { orderStatus } from '../checkout/public-messages';
import { accessError } from './access-errors';
@Component({
  selector: 'g-my-orders-page',
  imports: [PublicShell, Button, RouterLink],
  styleUrl: './access-page.scss',
  template: `<g-public-shell
    ><p class="eyebrow">Votre espace de suivi</p>
    <h1>Mes commandes</h1>
    @if (busy()) {
      <p role="status">Chargement de vos commandes…</p>
    }
    @if (error()) {
      <p role="alert">{{ error() }}</p>
      <div class="actions">
        <button gButton (click)="load()">Réessayer</button
        ><a routerLink="/suivi">Retrouver mes commandes</a>
      </div>
    }
    @if (result(); as list) {
      <p class="muted">Retrouvez l’avancement de vos commandes, dans toutes vos boutiques.</p>
      <div class="stack">
        @for (order of list.items; track order.orderNumber) {
          <article class="card">
            <h2>{{ order.groupage?.title || 'Votre groupage' }}</h2>
            <p class="muted">{{ order.orderNumber }} · {{ dateLabel(order.placedAt) }}</p>
            <p>{{ progress(order.groupage?.progress?.status, order.status) }}</p>
            <p class="amount">{{ money(order.amount, order.currency) }}</p>
            <a gButton variant="secondary" [routerLink]="['/mes-commandes', order.orderNumber]"
              >Voir le suivi</a
            >
          </article>
        } @empty {
          <p class="card">Aucune commande associée à ce numéro pour le moment.</p>
        }
      </div>
      @if ((list.totalPages ?? 0) > 1) {
        <nav aria-label="Pages des commandes">
          <button
            gButton
            variant="secondary"
            [disabled]="busy() || page() === 0"
            (click)="load(page() - 1)"
          >
            Précédent</button
          ><span>Page {{ page() + 1 }} sur {{ list.totalPages }}</span
          ><button
            gButton
            variant="secondary"
            [disabled]="busy() || page() + 1 >= (list.totalPages ?? 0)"
            (click)="load(page() + 1)"
          >
            Suivant
          </button>
        </nav>
      }
      <div class="actions">
        <button gButton variant="ghost" [disabled]="busy()" (click)="logout()">
          Fermer ma session
        </button>
      </div>
    }
  </g-public-shell>`,
})
export class MyOrdersPage {
  private readonly access = inject(CustomerAccessService);
  private readonly router = inject(Router);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly result = signal<OrdersResult | null>(null);
  readonly page = signal(0);
  readonly money = money;
  readonly dateLabel = dateLabel;
  constructor() {
    void this.load();
  }
  progress(status?: string, commercial?: string) {
    if (commercial !== 'CONFIRMED') return orderStatus(commercial);
    return (
      {
        SUPPLIER_ORDERED: 'Commande fournisseur passée',
        IN_TRANSIT: 'En transport',
        ARRIVED: 'Arrivé à destination',
      }[status ?? ''] ?? orderStatus(commercial)
    );
  }
  async load(page = this.page()) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.result.set(null);
    try {
      const session = await this.access.session();
      if (session.scope === 'ORDER_ACCESS') {
        await this.router.navigate(['/mes-commandes', session.orderNumber], { replaceUrl: true });
        return;
      }
      this.result.set(await this.access.orders(page));
      this.page.set(page);
    } catch (error) {
      this.error.set(accessError(error));
    } finally {
      this.busy.set(false);
    }
  }
  async logout() {
    this.busy.set(true);
    try {
      await this.access.logout();
      this.result.set(null);
      await this.router.navigateByUrl('/suivi', { replaceUrl: true });
    } catch (error) {
      this.error.set(accessError(error));
    } finally {
      this.busy.set(false);
    }
  }
}
