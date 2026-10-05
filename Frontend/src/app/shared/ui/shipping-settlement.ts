import { Component, input } from '@angular/core';
import { ShippingSettlementView } from '../../api/generated/models';
import { money } from '../utils/display';
import { RefundSummary } from './refund-summary';
@Component({
  selector: 'g-shipping-settlement',
  imports: [RefundSummary],
  template: `@if (value(); as v) {
    <section aria-label="Situation financière du transport">
      <h2>Transport</h2>
      @if (v.status === 'NOT_FINALIZED') {
        <p>Le transport reste à finaliser. Aucun paiement transport n’est demandé.</p>
      } @else {
        <dl>
          <div>
            <dt>Transport final</dt>
            <dd>{{ money(v.finalShippingAmount) }}</dd>
          </div>
          @if (manager()) {
            <div>
              <dt>Payé initialement</dt>
              <dd>{{ money(v.initialShippingPaidAmount) }}</dd>
            </div>
            <div>
              <dt>Compléments réglés</dt>
              <dd>{{ money(v.additionalShippingPaidAmount) }}</dd>
            </div>
          }
          <div>
            <dt>Déjà payé</dt>
            <dd>{{ money(v.totalShippingPaidAmount) }}</dd>
          </div>
          @if (v.status === 'BALANCE_DUE') {
            <div>
              <dt>Reste à payer</dt>
              <dd>{{ money(v.amountDue) }}</dd>
            </div>
          }
        </dl>
        @if (v.successfulShippingCreditRefunds) {
          <p>
            Restitué : {{ money(v.successfulShippingCreditRefunds) }} · Transport payé net :
            {{ money(v.netShippingPaid) }}
          </p>
        }
        @switch (v.status) {
          @case ('BALANCE_DUE') {
            <p class="state">À payer : {{ money(v.amountDue) }}</p>
          }
          @case ('SETTLED') {
            <p class="state">Transport réglé</p>
          }
          @case ('CREDIT_DUE') {
            <p class="state">
              {{ manager() ? 'Crédit cliente' : 'Crédit en votre faveur' }} :
              {{ money(v.creditAmount) }}
            </p>
            @if (!v.creditStableForRefund) {
              <p>Il sera régularisé après la remise de votre commande.</p>
            } @else {
              <p>Ce montant doit être régularisé par la boutique.</p>
            }
          }
        }
        @if (v.paymentInProgress) {
          <p role="status">Paiement en cours de confirmation</p>
        }
        @if (manager() && v.currentPaymentRequest; as request) {
          <p class="muted">
            {{
              request.status === 'OPEN'
                ? 'Solde ouvert'
                : request.status === 'CLOSED'
                  ? 'Solde réglé'
                  : 'Ancien solde fermé'
            }}
          </p>
        }
      }
      <ng-content />
      <g-refund-summary [refunds]="v.refunds ?? []" />
    </section>
  }`,
  styles: `
    section {
      margin: var(--s5) 0;
      padding: var(--s4);
      border: 1px solid var(--border);
      border-radius: var(--radius-md);
      background: var(--surface-alt);
    }
    h2 {
      font-size: var(--text-lg);
      margin-bottom: var(--s3);
    }
    dl {
      margin: var(--s3) 0;
    }
    dl div {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: var(--s2);
      padding: var(--s2) 0;
    }
    dd {
      margin: 0;
      font-weight: 600;
    }
    p {
      margin: var(--s3) 0;
      overflow-wrap: anywhere;
    }
    .state {
      font-weight: 600;
      color: var(--text);
    }
  `,
})
export class ShippingSettlement {
  readonly value = input<ShippingSettlementView>();
  readonly manager = input(false);
  readonly money = money;
}
