import { ShippingExplanation } from '../../shared/ui/shipping-explanation';
import { Component, computed, input } from '@angular/core';
import { OrderTrackingView } from '../../api/generated/models';
import { dateLabel } from '../../shared/utils/display';
@Component({
  selector: 'g-order-timeline',
  imports: [ShippingExplanation],
  template: `
    <section aria-labelledby="order-progress-title">
      <h2 id="order-progress-title">Votre commande, étape par étape</h2>
      @if (order().groupingProgress?.status === 'CANCELLED') {
        <p role="status">Le groupage a été annulé. Contactez votre boutique pour la suite.</p>
      } @else {
        <ol>
          @for (step of steps(); track step.label; let i = $index) {
            <li
              [class.done]="i + 1 <= stage()"
              [attr.aria-current]="i + 1 === stage() ? 'step' : null"
            >
              <span class="marker" aria-hidden="true">{{
                i + 1 < stage() ? '✓' : i + 1 === stage() ? '●' : '○'
              }}</span>
              <div>
                <strong>{{ step.label }}</strong
                ><span class="muted">{{
                  i + 1 < stage()
                    ? 'Étape franchie'
                    : i + 1 === stage()
                      ? 'Étape actuelle'
                      : 'En attente'
                }}</span>
                @if (step.date) {
                  <span>{{ dateLabel(step.date) }}</span>
                }
              </div>
            </li>
          }
        </ol>
        @if (order().groupingProgress?.expectedArrivalAt && stage() === 3) {
          <p>Arrivée prévue : {{ dateLabel(order().groupingProgress?.expectedArrivalAt) }}</p>
        }
        @if (stage() === 4) {
          <p class="muted">
            Votre boutique vous indiquera quand votre commande sera prête à récupérer.
          </p>
        }
      }
    </section>
    @if (order().shippingFinal; as shipping) {
      <section aria-label="Transport final">
        <h2>Transport final</h2>
        <g-shipping-explanation [value]="shipping" />
      </section>
    }
  `,
  styles: `
    section {
      margin: var(--s6) 0;
      padding: var(--s5);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
    }
    h2 {
      font-size: var(--text-lg);
      margin-bottom: var(--s5);
    }
    ol {
      list-style: none;
      padding: 0;
      margin: 0;
    }
    li {
      display: flex;
      gap: var(--s3);
      padding: var(--s3) 0;
    }
    li > div {
      min-width: 0;
    }
    .marker {
      color: var(--muted);
      flex: 0 0 var(--s5);
    }
    .done .marker {
      color: var(--primary);
    }
    span:not(.marker) {
      display: block;
      font-size: var(--text-sm);
    }
    strong {
      overflow-wrap: anywhere;
    }
    p {
      margin-top: var(--s4);
    }
  `,
})
export class OrderTimeline {
  readonly order = input.required<OrderTrackingView>();
  readonly dateLabel = dateLabel;
  readonly stage = computed(() => {
    const o = this.order();
    if (!['CONFIRMED', 'COMPLETED'].includes(o.status ?? '')) return 0;
    if (['READY_FOR_PICKUP', 'PICKED_UP'].includes(o.fulfillmentStatus ?? '')) return 5;
    const status = o.groupingProgress?.status;
    if (['ARRIVED', 'DISTRIBUTING', 'COMPLETED'].includes(status ?? '')) return 4;
    if (status === 'IN_TRANSIT') return 3;
    if (status === 'SUPPLIER_ORDERED') return 2;
    return 1;
  });
  readonly steps = computed(() => [
    { label: 'Commande confirmée', date: undefined },
    { label: 'Commandée au fournisseur', date: undefined },
    { label: 'En transport', date: this.order().groupingProgress?.departedAt },
    { label: 'Arrivée', date: this.order().groupingProgress?.arrivedAt },
    { label: 'Prête à récupérer', date: undefined },
  ]);
}
