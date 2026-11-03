import { Component, DestroyRef, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DirectSalesApi, DirectTrackingView, DirectCustomerFinancialStatus } from './direct-sales.api';
import { PublicShell } from '../checkout/public-shell';
import { money } from '../../shared/utils/display';

/**
 * A private capability link is held only in the URL fragment, never passed in a GET
 * URL or stored in the local order cart. POST the token only to the lookup endpoint.
 */
@Component({
  selector: 'g-direct-order-tracking',
  imports: [PublicShell, RouterLink],
  template: `
    <g-public-shell [wide]="false">
      <section class="direct-tracking" aria-labelledby="tracking-heading">
        <p class="eyebrow">Groupia · Vente directe</p>
        <h1 id="tracking-heading">Suivi de ma commande</h1>
        @if (busy()) {
          <p role="status">Chargement sécurisé du statut…</p>
        } @else if (order(); as data) {
          <p class="tracking-ref">Référence : <strong>{{ data.orderNumber }}</strong></p>
          <h2>État de ma commande</h2>
          <p>Le paiement et le suivi de la commande sont vérifiés côté serveur. Aucun paiement ne peut être lancé depuis cette page pour le moment.</p>
          <dl class="tracking-data">
            <div><dt>Statut</dt><dd>{{ orderLabel(data.status) }}</dd></div>
            <div><dt>Remise</dt><dd>{{ data.fulfillmentMethod === 'PICKUP'
              ? 'Retrait à convenir' : 'Livraison à confirmer' }}</dd></div>
            <div><dt>Articles</dt><dd>{{ money(data.goodsSubtotalXof, data.currency) }}</dd></div>
            <div><dt>Frais de plateforme</dt><dd>{{ money(data.platformFeeXof, data.currency) }}</dd></div>
            @if (data.shippingFeeXof !== null) {
              <div><dt>Livraison</dt><dd>{{ money(data.shippingFeeXof, data.currency) }}</dd></div>
            }
            @if (data.acceptedTotalXof !== null) {
              <div><dt>Total accepté</dt><dd>{{ money(data.acceptedTotalXof, data.currency) }}</dd></div>
            }
            <div><dt>Paiement</dt><dd>{{ paymentLabel() }}</dd></div>
          </dl>
          @if (paymentProblem() || financial()?.financialStatus === 'REVIEW_REQUIRED') {
            <p class="notice" role="alert">Le paiement nécessite une vérification. Ne retirez pas les articles et n'organisez pas de livraison sur la base de cette page.</p>
          }
          <p class="notice">Conservez ce lien privé pour revenir consulter votre commande.
            Toute personne qui possède ce lien peut voir ce résumé.</p>
          <button type="button" class="action-link" (click)="reload()">Actualiser le statut</button>
        } @else {
          <p role="alert">{{ message() || 'Lien de commande indisponible.' }}</p>
          <p>Le numéro de commande seul ne donne pas accès au suivi.
            Utilisez le lien privé reçu lors de la confirmation.</p>
        }
        <a class="back-link" [routerLink]="['/v', publicId(), 'recuperation']">
          J'ai perdu mon lien : vérifier mon numéro
        </a>
        <a class="back-link" [routerLink]="['/v', publicId()]">Retour à la boutique</a>
      </section>
    </g-public-shell>
  `,
  styles: [`
    :host {display:block;min-width:0}
    .direct-tracking {max-width:740px;margin:24px auto;padding:clamp(20px,4vw,36px);
      border:1px solid var(--border);border-radius:18px;background:var(--surface);
      display:grid;gap:18px;min-width:0}
    .eyebrow {font-size:11px;text-transform:uppercase;color:var(--primary);font-weight:700}
    h1 {font-size:clamp(24px,4vw,34px);letter-spacing:-.035em}
    h2 {font-size:18px}
    p {font-size:14px;line-height:1.6;color:var(--muted)}
    .tracking-ref strong {color:var(--text);overflow-wrap:anywhere}
    .tracking-data {display:grid;grid-template-columns:repeat(2,minmax(0,1fr));
      gap:16px;margin:0}
    .tracking-data div {min-width:0}
    .tracking-data dt {font-size:12px;color:var(--muted)}
    .tracking-data dd {margin:5px 0 0;font-size:14px;font-weight:700;overflow-wrap:anywhere}
    .notice {padding:14px;border:1px solid var(--hero-border);border-radius:12px;
      background:var(--primary-soft)}
    .action-link {display:inline-flex;align-items:center;justify-content:center;
      width:fit-content;min-height:44px;padding:10px 16px;border:0;border-radius:10px;
      background:var(--primary);color:var(--on-primary);cursor:pointer;font:inherit;font-weight:650}
    .back-link {font-size:13px;color:var(--primary);font-weight:650}
    @media(max-width:620px){.tracking-data{grid-template-columns:1fr}}
  `],
})
export class DirectOrderTrackingPage {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(DirectSalesApi);
  readonly publicId = signal('');
  readonly money = money;
  readonly busy = signal(false);
  readonly order = signal<DirectTrackingView | null>(null);
  readonly financial = signal<DirectCustomerFinancialStatus | null>(null);
  readonly paymentProblem = signal(false);
  readonly message = signal('');
  private token: string | null = null;
  private sequence = 0;

  constructor() {
    const params = this.route.paramMap.subscribe((map) => {
      this.publicId.set(map.get('publicIdentifier') ?? '');
    });
    const fragment = this.route.fragment.subscribe((value) => {
      const token = value?.startsWith('access=') ? value.slice('access='.length) : '';
      this.token = /^[A-Za-z0-9_-]{43}$/.test(token) ? token : null;
      void this.reload();
    });
    inject(DestroyRef).onDestroy(() => {
      this.sequence++;
      this.token = null;
      params.unsubscribe();
      fragment.unsubscribe();
    });
  }

  orderLabel(value: string) {
    if (value === 'CANCELLED') return 'Commande annulée';
    if (value === 'PENDING_PAYMENT_INTEGRATION') return 'En attente de règlement';
    if (this.financial()?.financialStatus !== 'SETTLEMENT_VERIFIED') {
      return 'Traitement suspendu : règlement à vérifier';
    }
    switch (value) {
      case 'PAYMENT_CONFIRMED': return 'Règlement confirmé';
      case 'PREPARING': return 'Commande en préparation';
      case 'READY_FOR_PICKUP': return 'Prête pour le retrait (à confirmer avec la boutique)';
      case 'READY_FOR_DISPATCH': return 'Prête pour l’expédition (à confirmer avec la boutique)';
      default: return 'Statut à vérifier';
    }
  }

  paymentLabel() {
    if (this.paymentProblem()) return 'État financier temporairement indisponible';
    switch (this.financial()?.financialStatus) {
      case 'SETTLEMENT_VERIFIED': return 'Règlement vérifié';
      case 'SETTLEMENT_PROCESSING': return 'Règlement en cours de confirmation';
      case 'REVIEW_REQUIRED': return 'Vérification manuelle nécessaire';
      case 'PREPARED': return 'Paiement non encore confirmé';
      case 'NOT_PREPARED': return 'Paiement non préparé';
      case 'EXPIRED': return 'Délai de règlement expiré';
      case 'UNAVAILABLE': return 'Paiement indisponible';
      default: return 'État à vérifier';
    }
  }

  async reload() {
    const sequence = ++this.sequence;
    const token = this.token;
    this.order.set(null);
    this.financial.set(null);
    this.paymentProblem.set(false);
    this.message.set('');
    if (!token) {
      this.busy.set(false);
      this.message.set('Ce lien ne contient pas de jeton de suivi valide.');
      return;
    }
    this.busy.set(true);
    try {
      const [order, payment] = await Promise.allSettled([
        this.api.trackDirectOrder(token),
        this.api.directPaymentStatus(token),
      ]);
      if (sequence !== this.sequence) return;
      if (order.status === 'rejected') throw order.reason;
      this.order.set(order.value);
      if (payment.status === 'fulfilled'
        && payment.value.orderNumber === order.value.orderNumber
        && payment.value.currency === order.value.currency
        && payment.value.acceptedTotalXof === order.value.acceptedTotalXof) {
        this.financial.set(payment.value);
      } else {
        this.paymentProblem.set(true);
      }
    } catch {
      if (sequence === this.sequence) {
        this.message.set('Ce lien est invalide ou cette commande n’est pas accessible.');
      }
    } finally {
      if (sequence === this.sequence) this.busy.set(false);
    }
  }
}
