import { Component, input } from '@angular/core';
import { OrderLineView } from '../../api/generated/models';
import { ProductThumbnail } from '../../shared/ui/product-thumbnail';
import { money, variantLabel } from '../../shared/utils/display';

/** Shared compact rendering of an immutable order-item snapshot. */
@Component({
  selector: 'g-order-line-card',
  imports: [ProductThumbnail],
  template: `
    <article class="line-card">
      <g-product-thumbnail [src]="image()" [alt]="item().productNameSnapshot || 'Produit commandé'"
        [size]="56" />
      <div class="line-content">
        <div class="line-head">
          <h3 [title]="item().productNameSnapshot || item().commercialLabelSnapshot || 'Produit'">
            {{ item().productNameSnapshot || item().commercialLabelSnapshot || 'Produit' }}
          </h3>
          <strong class="line-total">{{ money(item().lineTotal, currency()) }}</strong>
        </div>
        @if (item().variantAttributesSnapshot && variant(item().variantAttributesSnapshot) !== 'Standard') {
          <p class="variant">{{ variant(item().variantAttributesSnapshot) }}</p>
        }
        <p class="quantity">{{ item().quantity ?? '—' }} ×
          {{ money(item().unitPriceSnapshot, currency()) }}</p>
        @if (item().productDescriptionSnapshot ||
          (item().commercialLabelSnapshot && item().commercialLabelSnapshot !== item().productNameSnapshot) ||
          item().skuSnapshot) {
          <details class="extra">
            <summary>Voir les détails du produit</summary>
            @if (item().commercialLabelSnapshot &&
              item().commercialLabelSnapshot !== item().productNameSnapshot) {
              <p>Offre : {{ item().commercialLabelSnapshot }}</p>
            }
            @if (item().productDescriptionSnapshot) {
              <p class="preserve">{{ item().productDescriptionSnapshot }}</p>
            }
            @if (item().skuSnapshot) { <p>Référence : {{ item().skuSnapshot }}</p> }
          </details>
        }
      </div>
    </article>
  `,
  styles: `
    :host { display:block; min-width:0; }
    .line-card { display:flex; align-items:flex-start; gap:var(--s3); min-width:0; padding:var(--s3);
      border:1px solid var(--border); background:var(--surface-alt); border-radius:var(--radius-md); }
    .line-content { flex:1; min-width:0; }
    .line-head { display:flex; align-items:flex-start; justify-content:space-between; gap:var(--s2); }
    h3 { font-size:var(--text-sm); font-weight:650; min-width:0; margin:0;
      line-height:1.4; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .line-total { font-size:var(--text-sm); font-variant-numeric:tabular-nums; white-space:nowrap; }
    .variant,.quantity { font-size:var(--text-xs); color:var(--muted); margin:var(--s1) 0 0;
      overflow-wrap:anywhere; }
    .extra { margin-top:var(--s2); font-size:var(--text-xs); overflow-wrap:anywhere; }
    .extra summary { display:flex; align-items:center; min-height:36px; cursor:pointer;
      color:var(--primary); font-size:var(--text-xs); font-weight:600; list-style:none; }
    .extra summary:after { content:'⌄'; font-size:19px; margin-left:var(--s2); }
    .extra summary::-webkit-details-marker { display:none; }
    .extra[open] summary:after { transform:rotate(180deg); }
    .extra summary:focus-visible { outline:2px solid var(--primary); outline-offset:2px; }
    .extra p { margin:var(--s2) 0 0; }
    @media(max-width:400px) { .line-card { padding:var(--s2); gap:var(--s2); } }
  `,
})
export class OrderLineCard {
  readonly item = input.required<OrderLineView>();
  readonly currency = input('XOF');
  readonly image = input('');
  readonly money = money;
  readonly variant = variantLabel;
}
