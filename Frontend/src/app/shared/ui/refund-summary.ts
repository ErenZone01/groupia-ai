import { Component, input } from '@angular/core';
import type { RefundSummary as RefundInfo } from '../../api/generated/models';
import { dateLabel, money } from '../utils/display';
export function refundMethod(method?: string): string {
  return (
    (
      {
        WAVE: 'Wave',
        ORANGE_MONEY: 'Orange Money',
        CASH: 'Espèces',
        BANK_TRANSFER: 'Virement',
        OTHER: 'Autre',
      } as Record<string, string>
    )[method ?? ''] ?? 'Restitution'
  );
}
@Component({
  selector: 'g-refund-summary',
  template: `
    @for (r of refunds(); track $index) {
      <div class="refund" role="status">
        @if (r.status === 'SUCCEEDED') {
          <strong
            >{{ money(r.amount, r.currency) }}
            {{
              r.method === 'MANUAL_EXTERNAL' ? 'régularisés par la boutique' : 'remboursés'
            }}</strong
          >
          <p>{{ refundMethod(r.externalMethod) }} · {{ dateLabel(r.completedAt) }}</p>
          @if (r.method === 'MANUAL_EXTERNAL') {
            <p>Régularisation déclarée par la boutique.</p>
          }
        } @else if (r.reconciliationRequired || r.status === 'REVIEW_REQUIRED') {
          <strong>Remboursement de {{ money(r.amount, r.currency) }} à vérifier</strong>
          <p>La boutique vérifie le résultat. Votre crédit reste ouvert.</p>
        } @else if (r.status === 'CREATED' || r.status === 'PENDING') {
          <strong>Remboursement de {{ money(r.amount, r.currency) }} en cours</strong>
        } @else {
          <strong>Remboursement non abouti</strong>
          <p>Votre crédit reste ouvert. La boutique doit vérifier la régularisation.</p>
        }
      </div>
    }
  `,
  styles: `
    .refund {
      margin-top: var(--s3);
      padding-top: var(--s3);
      border-top: 1px solid var(--border);
      overflow-wrap: anywhere;
    }
    p {
      margin-top: var(--s2);
    }
  `,
})
export class RefundSummary {
  readonly refunds = input<RefundInfo[]>([]);
  readonly money = money;
  readonly dateLabel = dateLabel;
  readonly refundMethod = refundMethod;
}
