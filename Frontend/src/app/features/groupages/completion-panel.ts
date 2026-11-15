import {
  Component,
  DestroyRef,
  ElementRef,
  ViewChild,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription, timeout } from 'rxjs';
import { GroupingCompletionControllerService } from '../../api/generated/services';
import { CampaignView, GroupingCompletionView } from '../../api/generated/models';
import { Button } from '../../shared/ui/button';
import { apiMessage } from '../../core/http/api-error';
import { dateLabel } from '../../shared/utils/display';
@Component({
  selector: 'g-completion-panel',
  imports: [Button, RouterLink],
  templateUrl: './completion-panel.html',
  styles: `
    :host {
      display: block;
      margin-block: var(--s5);
    }
    section,
    dialog {
      background: var(--surface);
      color: var(--text);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      padding: var(--s5);
    }
    dialog {
      max-width: min(32rem, calc(100vw - var(--s6)));
    }
    p,
    li {
      margin-block: var(--s3);
      overflow-wrap: anywhere;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--s3);
    }
  `,
})
export class CompletionPanel {
  readonly campaign = input.required<CampaignView>();
  readonly organizationId = input.required<string>();
  readonly refreshCampaign = output<void>();
  readonly diagnostic = signal<GroupingCompletionView | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly forbidden = signal(false);
  readonly dateLabel = dateLabel;
  private readonly api = inject(GroupingCompletionControllerService);
  private readonly destroy = inject(DestroyRef);
  private request?: Subscription;
  private mutation?: Subscription;
  @ViewChild('confirmation') private confirmation?: ElementRef<HTMLDialogElement>;
  constructor() {
    effect(() => {
      this.campaign();
      this.organizationId();
      this.reload();
    });
    this.destroy.onDestroy(() => {
      this.request?.unsubscribe();
      this.mutation?.unsubscribe();
    });
  }
  reload() {
    this.request?.unsubscribe();
    this.confirmation?.nativeElement.close();
    this.diagnostic.set(null);
    this.loading.set(true);
    this.forbidden.set(false);
    this.request = this.api
      .groupingCompletion({
        organizationId: this.organizationId(),
        campaignId: this.campaign().id!,
      })
      .pipe(timeout(15000))
      .subscribe({
        next: (value) => {
          this.diagnostic.set(value);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.forbidden.set((error as { status?: number })?.status === 403);
          if (!this.forbidden()) this.error.set(apiMessage(error));
        },
      });
  }
  ask() {
    if (this.diagnostic()?.eligible && this.diagnostic()?.canComplete && !this.busy())
      this.confirmation?.nativeElement.showModal();
  }
  cancel() {
    if (!this.busy()) this.confirmation?.nativeElement.close();
  }
  submit() {
    const value = this.diagnostic();
    if (
      !value?.eligible ||
      !value.canComplete ||
      this.busy() ||
      value.campaignVersion === undefined
    )
      return;
    this.busy.set(true);
    this.error.set('');
    this.mutation = this.api
      .completeGrouping({
        organizationId: this.organizationId(),
        campaignId: this.campaign().id!,
        body: { version: value.campaignVersion, confirmed: true },
      })
      .pipe(timeout(20000))
      .subscribe({
        next: (result) => {
          this.diagnostic.set(result);
          this.busy.set(false);
          this.confirmation?.nativeElement.close();
          this.refreshCampaign.emit();
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.confirmation?.nativeElement.close();
          this.error.set(apiMessage(error));
          this.refreshCampaign.emit();
          this.reload();
        },
      });
  }
  visibleBlockers(value: GroupingCompletionView) {
    return (value.blockers ?? []).filter(
      (b) => value.campaignStatus !== 'COMPLETED' || b.code !== 'INVALID_GROUPING_TRANSITION',
    );
  }
  label(code: string | undefined) {
    const labels: Record<string, string> = {
      ORDER_NOT_COMPLETED: 'Commande à terminer',
      ORDER_PICKUP_REQUIRED: 'Commande encore à retirer',
      SHIPPING_NOT_FINALIZED: 'Transport à finaliser',
      SHIPPING_BALANCE_DUE: 'Solde transport à payer',
      SHIPPING_CREDIT_DUE: 'Crédit transport à rembourser',
      PAYMENT_REVIEW_OPEN: 'Anomalie de paiement à régulariser',
      PAYMENT_IN_PROGRESS: 'Paiement en cours',
      PAYMENT_RECONCILIATION_REQUIRED: 'Paiement à vérifier',
      PAYMENT_PROVIDER_REVERSAL: 'Annulation financière du prestataire à vérifier',
      PAYMENT_PROVIDER_STATE_UNCERTAIN: 'État du paiement encore incertain',
      PAYMENT_TRANSACTION_INCONSISTENT: 'Transaction de paiement incohérente',
      PAYMENT_UNALLOCATED_SUCCESS: 'Paiement reçu à régulariser',
      PAYMENT_RECEIPT_PENDING: 'Confirmation de paiement en traitement',
      PAYMENT_OBLIGATION_OPEN: 'Paiement encore ouvert',
      REFUND_UNRESOLVED: 'Remboursement à terminer',
      COMMERCIAL_REFUND_DUE: 'Commande annulée : remboursement à traiter',
      COMMERCIAL_REFUND_UNVERIFIED: 'Remboursement de commande à vérifier',
      PLATFORM_FEE_COMPENSATION_DUE: 'Anciens frais Groupia à compenser',
      INITIAL_PAYMENT_NOT_SETTLED: 'Paiement initial non réglé',
      INVALID_GROUPING_TRANSITION: 'Ce groupage ne peut pas être terminé depuis son état actuel',
    };
    return labels[code ?? ''] ?? 'Situation incohérente à vérifier avant la fin du groupage';
  }
}
