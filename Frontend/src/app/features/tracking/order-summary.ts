import { Component, input } from '@angular/core';
import { OrderReceipt } from '../../api/generated/models';
import { money, variantLabel, dateLabel } from '../../shared/utils/display';
@Component({
  selector: 'g-order-summary',
  template: ` @if (receipt(); as r) {
    <section aria-label="Récapitulatif de la commande">
      <h2>Votre commande {{ r.orderNumber }}</h2>
      <p class="muted">{{ dateLabel(r.placedAt) }}</p>
      @for (item of r.items; track item.lineNumber) {
        <article>
          <strong>{{ item.commercialLabelSnapshot || item.productNameSnapshot }}</strong>
          <p class="muted">{{ variantLabel(item.variantAttributesSnapshot) }}</p>
          <p class="line-price">
            <span>{{ item.quantity }} × {{ money(item.unitPriceSnapshot, r.currency) }}</span
            ><strong>{{ money(item.lineTotal, r.currency) }}</strong>
          </p>
        </article>
      }
      <dl>
        <div>
          <dt>Articles</dt>
          <dd>{{ money(r.goodsSubtotal, r.currency) }}</dd>
        </div>
        <div>
          <dt>Frais de service</dt>
          <dd>{{ money(r.platformFeeAmount, r.currency) }}</dd>
        </div>
        <div>
          <dt>Transport maintenant</dt>
          <dd>{{ money(r.initialShippingChargeAmount, r.currency) }}</dd>
        </div>
        <div class="total">
          <dt>{{ confirmed() ? 'Montant de la commande' : 'À payer maintenant' }}</dt>
          <dd>{{ money(r.initialAmountDue, r.currency) }}</dd>
        </div>
      </dl>
      @if (shippingLater()) {
        <p class="notice">
          Les frais de transport définitifs seront calculés après réception/pesée.
        </p>
      }
    </section>
  }`,
  styles: `
    section {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      padding: var(--s5);
      margin: var(--s5) 0;
    }
    h2 {
      font-size: var(--text-lg);
    }
    article {
      padding: var(--s4) 0;
      border-bottom: 1px solid var(--border);
    }
    .line-price {
      display: flex;
      justify-content: space-between;
      gap: var(--s3);
      flex-wrap: wrap;
    }
    article p {
      margin-top: var(--s2);
    }
    dl > div {
      display: flex;
      justify-content: space-between;
      gap: var(--s3);
      padding: var(--s3) 0;
      flex-wrap: wrap;
    }
    dd {
      margin: 0;
      font-weight: 600;
    }
    .total {
      border-top: 1px solid var(--border);
      font-weight: 600;
      color: var(--primary);
    }
  `,
})
export class OrderSummary {
  readonly receipt = input<OrderReceipt | null>(null);
  readonly shippingLater = input(false);
  readonly confirmed = input(false);
  readonly money = money;
  readonly variantLabel = variantLabel;
  readonly dateLabel = dateLabel;
}
