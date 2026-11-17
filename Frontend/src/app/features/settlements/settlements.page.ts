import { Component, effect, ElementRef, inject, signal, viewChild } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { RouterLink } from '@angular/router';
import type { CampaignSettlement, MerchantPayoutView } from '../../api/generated/models';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { Card } from '../../shared/ui/card';
import { Button } from '../../shared/ui/button';
import { Skeleton } from '../../shared/feedback/skeleton';
import { money, dateLabel } from '../../shared/utils/display';
import { refundMethod } from '../../shared/ui/refund-summary';
import { MerchantSettlementStore, type DirectSettlement } from './merchant-settlement.store';
import { MerchantBalanceSummary } from './merchant-balance-summary';
import { PayoutDestinationEditor } from './payout-destination-editor';
export function payoutLabel(status?: string): string {
  return (
    (
      {
        CREATED: 'Demande enregistrée',
        PENDING: 'Versement en cours',
        SUCCEEDED: 'Versé',
        FAILED: 'Échec confirmé — nouvelle demande possible si fonds disponibles',
        CANCELLED: 'Annulé',
        NON_EXECUTE: 'Non exécuté — réserve libérée après vérification',
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
  private readonly auth = inject(AuthService);
  private readonly context = inject(BoutiqueContextService);
  readonly selected = signal<CampaignSettlement | null>(null);
  readonly selectedDirect = signal<DirectSettlement | null>(null);
  readonly editing = signal(false);
  readonly requestedAmount = signal(1000);
  readonly modal = viewChild<ElementRef<HTMLDialogElement>>('modal');
  readonly money = money;
  readonly date = dateLabel;
  readonly method = refundMethod;
  readonly status = payoutLabel;
  readonly payoutSource = (p: MerchantPayoutView) =>
    (p as MerchantPayoutView & { directOrderId?: string | null }).directOrderId
      ? 'Vente directe'
      : 'Groupage';
  constructor() {
    effect(() => {
      this.auth.generation();
      const org = this.context.selectedId();
      this.selected.set(null);
      this.selectedDirect.set(null);
      this.editing.set(false);
      if (!org) this.store.clear();
      else void this.store.load(org);
      this.modal()?.nativeElement.close();
    });
  }
  ask(row: CampaignSettlement) {
    if (!this.store.deductedFees) {
      this.selectedDirect.set(null);
      this.selected.set(row);
      this.modal()?.nativeElement.showModal();
      return;
    }
    this.store.discardQuote();
    this.requestedAmount.set(Math.min(1000, Math.floor(row.availableForPayout ?? 0)));
    this.selectedDirect.set(null);
    this.selected.set(row);
    this.modal()?.nativeElement.showModal();
  }
  askDirect(row: DirectSettlement) {
    if (!this.store.deductedFees) {
      this.selected.set(null);
      this.selectedDirect.set(row);
      this.modal()?.nativeElement.showModal();
      return;
    }
    this.store.discardQuote();
    this.requestedAmount.set(Math.min(1000, Math.floor(row.availableForPayout)));
    this.selected.set(null);
    this.selectedDirect.set(row);
    this.modal()?.nativeElement.showModal();
  }
  changeAmount(event: Event) {
    const value = Number((event.target as HTMLInputElement).value);
    this.requestedAmount.set(value);
    this.store.discardQuote();
  }
  async calculateQuote() {
    const campaign = this.selected();
    const direct = this.selectedDirect();
    const available = campaign?.availableForPayout ?? direct?.availableForPayout ?? 0;
    const amount = this.requestedAmount();
    if (!Number.isSafeInteger(amount) || amount < 1 || amount > 1000 || amount > available) {
      this.store.error.set(
        'Choisissez un montant entier entre 1 et 1 000 FCFA, dans le solde disponible.',
      );
      return;
    }
    if (direct) await this.store.prepareQuote(direct.directOrderId, true, amount);
    else if (campaign?.campaignId)
      await this.store.prepareQuote(campaign.campaignId, false, amount);
  }
  close() {
    if (!this.store.busy()) {
      this.modal()?.nativeElement.close();
      this.selected.set(null);
      this.selectedDirect.set(null);
    }
  }
  cancel(e: Event) {
    if (this.store.busy()) e.preventDefault();
    else {
      this.selected.set(null);
      this.selectedDirect.set(null);
    }
  }
  async confirm() {
    const campaignId = this.selected()?.campaignId;
    const directOrderId = this.selectedDirect()?.directOrderId;
    if (this.store.busy() || (!campaignId && !directOrderId)) return;
    if (this.store.deductedFees && !this.store.quote()) {
      this.store.error.set('Le devis de retrait est indispensable avant confirmation.');
      return;
    }
    if (directOrderId) await this.store.requestDirect(directOrderId);
    else if (campaignId) await this.store.request(campaignId);
    this.close();
  }
  async save(d: { method: string; phone: string; holder: string; confirmed: boolean }) {
    await this.store.saveDestination(d.method, d.phone, d.holder, d.confirmed);
    if (!this.store.error()) this.editing.set(false);
  }
}
