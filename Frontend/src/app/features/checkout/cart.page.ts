import { Component, DestroyRef, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { GroupiaApi } from '../../api/groupia-api';
import { CartStore } from './cart.store';
import { CartLines } from './cart-lines';
import { PublicShell } from './public-shell';
import { Button } from '../../shared/ui/button';
import { Skeleton } from '../../shared/feedback/skeleton';
import { money } from '../../shared/utils/display';
import { publicMessage } from './public-messages';
@Component({
  selector: 'g-cart-page',
  imports: [PublicShell, CartLines, Button, RouterLink, Skeleton],
  template: ` <g-public-shell
    ><a [routerLink]="['/public/groupages', id]">← Continuer ma sélection</a>
    <h1>Mon panier</h1>
    @if (loading()) {
      <g-skeleton />
    }
    @if (error()) {
      <p class="form-error" role="alert">{{ error() }}</p>
      <button gButton (click)="load()">Réessayer</button>
    }
    @if (cart.count()) {
      <g-cart-lines />
      <p class="cart-total">
        Sous-total articles <strong>{{ money(cart.subtotal(), cart.group()?.currency) }}</strong>
      </p>
      <p class="muted">
        Les frais de service et le transport à payer maintenant seront précisés avant le paiement.
      </p>
      @if (!loading() && cart.group() && !cart.group()?.orderable) {
        <p class="notice" role="alert">Ce groupage ne reçoit plus de commandes.</p>
      }
      <div class="actions">
        <a
          gButton
          [disabled]="loading() || !!error() || !cart.ready()"
          [routerLink]="['/public/groupages', id, 'checkout']"
          >Continuer vers mes coordonnées</a
        >
      </div>
    } @else {
      <p class="notice">Votre panier est vide.</p>
    }
  </g-public-shell>`,
  styles: `
    h1 {
      margin: var(--s5) 0;
    }
    .cart-total {
      display: flex;
      justify-content: space-between;
      gap: var(--s4);
      flex-wrap: wrap;
      margin: var(--s5) 0;
    }
  `,
})
export class CartPage {
  readonly cart = inject(CartStore);
  private readonly api = inject(GroupiaApi);
  readonly id = inject(ActivatedRoute).snapshot.paramMap.get('publicIdentifier')!;
  readonly loading = signal(true);
  readonly error = signal('');
  readonly money = money;
  private alive = true;
  constructor() {
    this.cart.select(this.id);
    inject(DestroyRef).onDestroy(() => (this.alive = false));
    void this.load();
  }
  async load() {
    this.loading.set(true);
    this.error.set('');
    try {
      const g = await this.api.publicGrouping(this.id);
      if (this.alive) this.cart.reconcile(g);
    } catch (e) {
      if (this.alive) this.error.set(publicMessage(e));
    } finally {
      if (this.alive) this.loading.set(false);
    }
  }
}
