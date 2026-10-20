import { Component, inject } from '@angular/core';
import { CartStore } from './cart.store';
import { Button } from '../../shared/ui/button';
import { money, variantLabel } from '../../shared/utils/display';
@Component({
  selector: 'g-cart-lines',
  imports: [Button],
  template: ` @for (item of cart.items(); track item.offer.offerId) {
    <article>
      <div class="line-head">
        @if (item.offer.images?.[0]; as image) {
          <img
            class="cart-image"
            [src]="image.thumbnailUrl || image.displayUrl"
            [alt]="item.offer.commercialLabel || item.offer.catalog?.productName || 'Produit'"
            loading="lazy"
            decoding="async"
          />
        } @else {
          <div class="cart-image cart-image--placeholder" aria-hidden="true">Photo à venir</div>
        }
        <div class="line-copy">
          <h2>{{ item.offer.commercialLabel || item.offer.catalog?.productName || 'Produit' }}</h2>
          <p class="muted">{{ variantLabel(item.offer.catalog?.attributes) }}</p>
          <p>{{ money(item.offer.unitPrice, item.offer.currency) }} / unité</p>
        </div>
      </div>
      <div class="quantity">
        <button
          gButton
          variant="secondary"
          [attr.aria-label]="
            'Diminuer la quantité de ' + (item.offer.catalog?.productName || 'ce produit')
          "
          (click)="cart.remove(item.offer.offerId!)"
        >
          −
        </button>
        <span aria-live="polite">Quantité : {{ item.quantity }}</span>
        <button
          gButton
          variant="secondary"
          [disabled]="item.quantity >= 1000000"
          [attr.aria-label]="
            'Augmenter la quantité de ' + (item.offer.catalog?.productName || 'ce produit')
          "
          (click)="cart.quantity(item.offer.offerId!, item.quantity + 1)"
        >
          +
        </button>
        <button gButton variant="ghost" (click)="cart.delete(item.offer.offerId!)">
          Supprimer
        </button>
      </div>
      <p>
        <strong
          >Total : {{ money(item.quantity * item.offer.unitPrice!, item.offer.currency) }}</strong
        >
      </p>
      @if (cart.changes().includes(item)) {
        <div class="notice" role="alert">
          @if (cart.currentOffer(item.offer.offerId!); as next) {
            @if (next.active && next.catalog?.usable) {
              <p>
                Le prix a changé. Nouveau prix :
                <strong>{{ money(next.unitPrice, next.currency) }}</strong
                >.
              </p>
              <button gButton variant="secondary" (click)="cart.acceptPrice(item.offer.offerId!)">
                Accepter le nouveau prix
              </button>
            } @else {
              <p>Ce produit n’est plus disponible. Retirez-le du panier.</p>
            }
          } @else {
            <p>Ce produit n’est plus proposé. Retirez-le du panier.</p>
          }
        </div>
      }
    </article>
  }`,
  styles: `
    article {
      padding: var(--s5);
      margin: var(--s4) 0;
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      background: var(--surface);
    }
    .line-head {
      display: grid;
      grid-template-columns: 88px minmax(0, 1fr);
      gap: var(--s4);
      align-items: center;
    }
    .cart-image {
      width: 88px;
      height: 88px;
      display: block;
      object-fit: contain;
      border: 1px solid var(--border);
      border-radius: var(--radius-md);
      background: var(--surface-subtle, var(--surface));
    }
    .cart-image--placeholder {
      display: grid;
      place-items: center;
      padding: var(--s2);
      text-align: center;
      color: var(--text-muted);
      font-size: var(--text-xs);
    }
    .line-copy {
      min-width: 0;
    }
    h2 {
      font-size: var(--text-lg);
      overflow-wrap: anywhere;
    }
    p {
      margin: var(--s3) 0;
    }
    .quantity {
      display: flex;
      align-items: center;
      gap: var(--s3);
      flex-wrap: wrap;
    }
    .quantity button {
      min-width: 44px;
      min-height: 44px;
    }
  `,
})
export class CartLines {
  readonly cart = inject(CartStore);
  readonly money = money;
  readonly variantLabel = variantLabel;
}
