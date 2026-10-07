import { Component, input } from '@angular/core';
import type { MerchantBalance, CampaignSettlement } from '../../api/generated/models';
import { money } from '../../shared/utils/display';
@Component({
  selector: 'g-merchant-balance-summary',
  template: `
    <dl>
      <div>
        <dt>Montant marchand collecté</dt>
        <dd>{{ money(value().grossMerchantCollections) }}</dd>
      </div>
      <div>
        <dt>Frais Groupia (hors montant marchand)</dt>
        <dd>{{ money(value().platformFees) }}</dd>
      </div>
      <div>
        <dt>Remboursements financés par Groupia</dt>
        <dd>{{ money(value().platformFundedRefunds) }}</dd>
      </div>
      <div>
        <dt>Déjà reversé</dt>
        <dd>{{ money(value().merchantPayouts) }}</dd>
      </div>
      <div>
        <dt>En cours de versement</dt>
        <dd>{{ money(value().reservedForPayout) }}</dd>
      </div>
      <div class="available">
        <dt>Disponible</dt>
        <dd>{{ money(value().availableForPayout) }}</dd>
      </div>
    </dl>
    @if (value().deficit! > 0) {
      <p class="notice">Solde à compenser : {{ money(value().deficit) }}</p>
    }
  `,
  styles: `
    dl {
      display: grid;
      gap: var(--s3);
      margin: var(--s4) 0;
    }
    dl div {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: var(--s2);
    }
    dt {
      max-width: 100%;
      overflow-wrap: anywhere;
    }
    dd {
      margin: 0;
      font-weight: 600;
    }
    .available {
      border-top: 1px solid var(--border);
      padding-top: var(--s3);
      color: var(--primary);
    }
  `,
})
export class MerchantBalanceSummary {
  readonly value = input.required<MerchantBalance | CampaignSettlement>();
  readonly money = money;
}
