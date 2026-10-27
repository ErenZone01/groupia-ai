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
    <section class="funds" aria-label="Fonds boutique">
      <div class="funds-heading">
        <div>
          <h2>Fonds boutique</h2>
          <p>Suivez les versements de votre boutique.</p>
        </div>
        <a routerLink="/app/versements">Mes versements →</a>
      </div>
      <div class="funds-feature">
        <span>Disponibles</span>
        <strong>{{ money(v.availableForPayout) }}</strong>
      </div>
      <div class="funds-grid">
        <div><span>Collectés</span><strong>{{ money(v.grossMerchantCollections) }}</strong></div>
        <div><span>En versement</span><strong>{{ money(v.reservedForPayout) }}</strong></div>
        <div><span>Reversés</span><strong>{{ money(v.merchantPayouts) }}</strong></div>
      </div>
    </section>
  }`,
  styles: `
    :host { display:block; min-width:0; }
    section { border:1px solid var(--border); border-radius:var(--radius-lg); padding:var(--s4); background:var(--surface); }
    .funds-heading { display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:var(--s2); }
    h2 { margin:0; font-size:var(--text-lg); }
    .funds-heading p { margin:var(--s1) 0 var(--s3); color:var(--muted); font-size:var(--text-sm); }
    a { display:inline-flex; align-items:center; min-height:44px; color:var(--primary); font-size:var(--text-sm); font-weight:650; }
    .funds-feature { background:var(--primary-soft); border-radius:var(--radius-md); padding:var(--s4); margin:var(--s2) 0; }
    .funds-feature span,.funds-grid span { display:block; color:var(--muted); font-size:var(--text-xs); }
    .funds-feature strong { display:block; font-size:clamp(1.4rem,4vw,1.85rem); overflow-wrap:anywhere; }
    .funds-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:var(--s2); }
    .funds-grid div { padding:var(--s3); border:1px solid var(--border); border-radius:var(--radius-sm); min-width:0; }
    .funds-grid strong { display:block; margin-top:var(--s1); font-size:var(--text-sm); overflow-wrap:anywhere; }
    @media(max-width:480px) { .funds-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } }
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
