import { Component, effect, ElementRef, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import type { CampaignSettlement } from '../../api/generated/models';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { Card } from '../../shared/ui/card';
import { Button } from '../../shared/ui/button';
import { Skeleton } from '../../shared/feedback/skeleton';
import { money, dateLabel } from '../../shared/utils/display';
import { refundMethod } from '../../shared/ui/refund-summary';
import { MerchantSettlementStore } from './merchant-settlement.store';
import { MerchantBalanceSummary } from './merchant-balance-summary';
import { PayoutDestinationEditor } from './payout-destination-editor';
export function payoutLabel(status?: string): string {
  return (
    (
      {
        CREATED: 'Demande enregistrée',
        PENDING: 'Versement en cours',
        SUCCEEDED: 'Versé',
        FAILED: 'Échec confirmé',
        CANCELLED: 'Annulé',
        REVIEW_REQUIRED: 'À vérifier',
      } as Record<string, string>
    )[status ?? ''] ?? 'À vérifier'
  );
}
@Component({
  selector: 'g-settlements-page',
  providers: [MerchantSettlementStore],
  imports: [RouterLink, Card, Button, Skeleton, MerchantBalanceSummary, PayoutDestinationEditor],
  templateUrl: './settlements.page.html',
  styles: `
    .sections {
      display: grid;
      gap: var(--s5);
      min-width: 0;
    }
    h2 {
      margin-bottom: var(--s3);
    }
    article {
      padding: var(--s4) 0;
      border-bottom: 1px solid var(--border);
      min-width: 0;
    }
    p,
    h3 {
      overflow-wrap: anywhere;
    }
    p {
      margin: var(--s3) 0;
    }
    dialog {
      margin: auto;
      width: min(32rem, calc(100vw - 2rem));
      max-height: 85dvh;
      overflow-y: auto;
      background: var(--surface);
      color: var(--text);
      padding: var(--s5);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
    }
    dialog::backdrop {
      background: color-mix(in srgb, var(--text) 40%, transparent);
    }
  `,
})
export class SettlementsPage {
  readonly store = inject(MerchantSettlementStore);
  private readonly context = inject(BoutiqueContextService);
  readonly selected = signal<CampaignSettlement | null>(null);
  readonly editing = signal(false);
  readonly modal = viewChild.required<ElementRef<HTMLDialogElement>>('modal');
  readonly money = money;
  readonly date = dateLabel;
  readonly method = refundMethod;
  readonly status = payoutLabel;
  constructor() {
    effect(() => {
      const org = this.context.selectedId();
      this.selected.set(null);
      this.editing.set(false);
      if (org) void this.store.load(org);
    });
  }
  ask(row: CampaignSettlement) {
    this.selected.set(row);
    this.modal().nativeElement.showModal();
  }
  close() {
    if (!this.store.busy()) {
      this.modal().nativeElement.close();
      this.selected.set(null);
    }
  }
  cancel(e: Event) {
    if (this.store.busy()) e.preventDefault();
    else this.selected.set(null);
  }
  async confirm() {
    const id = this.selected()?.campaignId;
    if (!id || this.store.busy()) return;
    await this.store.request(id);
    this.close();
  }
  async save(d: { method: string; phone: string; holder: string; confirmed: boolean }) {
    await this.store.saveDestination(d.method, d.phone, d.holder, d.confirmed);
    if (!this.store.error()) this.editing.set(false);
  }
}
