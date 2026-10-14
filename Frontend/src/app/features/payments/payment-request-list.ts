import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Badge } from '../../shared/ui/badge';
import type { PaymentRequestSummaryView } from '../../api/generated/models';
import { money, paymentLabel, paymentDate, paymentTone } from './payment-display';
@Component({
  selector: 'g-payment-request-list',
  imports: [RouterLink, Badge],
  styleUrl: './payments.scss',
  template: `<table class="responsive-table">
    <caption class="sr-only">
      Obligations de paiement
    </caption>
    <thead>
      <tr>
        <th scope="col">Type</th>
        <th scope="col">Montant</th>
        <th scope="col">État</th>
        <th scope="col">Échéance</th>
        <th scope="col">Détail</th>
      </tr>
    </thead>
    <tbody>
      @for (item of items(); track item.id) {
        <tr>
          <td data-label="Type">{{ label(item.purpose) }}</td>
          <td data-label="Montant">
            <strong>{{ money(item.expectedAmount, item.currency) }}</strong>
          </td>
          <td data-label="État">
            <g-badge [tone]="tone(item.operationalStatus)">{{
              label(item.operationalStatus)
            }}</g-badge>
            @if (item.openReviewCount) {
              <small>{{ item.openReviewCount }} incident(s) à examiner</small>
            }
            @if (item.terminalReason) {
              <small>{{ label(item.terminalReason) }}</small>
            }
          </td>
          <td data-label="Échéance">
            {{ item.expiresAt ? date(item.expiresAt) : 'Sans échéance' }}
          </td>
          <td>
            <a [routerLink]="['/app/paiements/requests', item.id]"
              >Voir le détail<span class="sr-only">
                {{ label(item.purpose) }} {{ money(item.expectedAmount, item.currency) }}</span
              ></a
            >
          </td>
        </tr>
      }
    </tbody>
  </table>`,
})
export class PaymentRequestList {
  readonly items = input<PaymentRequestSummaryView[]>([]);
  readonly label = paymentLabel;
  readonly date = paymentDate;
  readonly money = money;
  readonly tone = paymentTone;
}
