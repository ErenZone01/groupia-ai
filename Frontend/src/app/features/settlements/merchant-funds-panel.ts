import { Component, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom, timeout } from 'rxjs';
import { MerchantSettlementControllerService } from '../../api/generated/services';
import type { CampaignSettlement } from '../../api/generated/models';
import { money } from '../../shared/utils/display';
@Component({
  selector: 'g-merchant-funds-panel',
  imports: [RouterLink],
  template: `@if (value(); as v) {
    <section aria-label="Fonds boutique">
      <h2>Fonds boutique</h2>
      <p>Collectés : {{ money(v.grossMerchantCollections) }}</p>
      <p>Disponibles : {{ money(v.availableForPayout) }}</p>
      <p>En versement : {{ money(v.reservedForPayout) }}</p>
      <p>Reversés : {{ money(v.merchantPayouts) }}</p>
      <a routerLink="/app/versements">Mes versements</a>
    </section>
  }`,
  styles: `
    section {
      padding: var(--s4);
      margin-bottom: var(--s4);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
    }
    p {
      margin: var(--s2) 0;
      overflow-wrap: anywhere;
    }
  `,
})
export class MerchantFundsPanel {
  readonly organizationId = input.required<string>();
  readonly campaignId = input.required<string>();
  readonly value = signal<CampaignSettlement | null>(null);
  readonly money = money;
  private readonly api = inject(MerchantSettlementControllerService);
  private sequence = 0;
  constructor() {
    effect(() => {
      void this.load(this.organizationId(), this.campaignId());
    });
  }
  private async load(org: string, campaign: string) {
    const seq = ++this.sequence;
    this.value.set(null);
    try {
      const b = await firstValueFrom(
        this.api
          .campaignMerchantSettlement({ organizationId: org, campaignId: campaign })
          .pipe(timeout(15000)),
      );
      if (seq === this.sequence)
        this.value.set(b.campaigns?.find((c) => c.campaignId === campaign) ?? null);
    } catch {
      if (seq === this.sequence) this.value.set(null);
    } // Operational users do not have financial read access.
  }
}
