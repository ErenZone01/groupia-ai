import { Component, DestroyRef, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DirectSalesApi, DirectTrackingView, DirectCustomerFinancialStatus } from './direct-sales.api';
import { PublicShell } from '../checkout/public-shell';
import { money } from '../../shared/utils/display';
import { safeBictorysCheckout } from '../customer-access/customer-shipping-payment';

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
          <p>Le paiement et le suivi de la commande sont vérifiés côté serveur. Votre commande n'est payée qu'après confirmation du prestataire.</p>
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
          @if (canPay()) {
            <button type="button" class="action-link" [disabled]="paymentBusy()" (click)="pay()">
              Payer {{ money(data.acceptedTotalXof ?? undefined, data.currency) }} avec Bictorys
            </button>
          }
          @if (paymentBusy()) {
            <p role="status">Préparation du paiement sécurisé…</p>
          }
          @if (checkoutUrl(); as url) {
            <p><a class="action-link" [href]="url" target="_blank" rel="noopener noreferrer"
              referrerpolicy="no-referrer">Ouvrir le paiement sécurisé Bictorys</a></p>
            <p>Le paiement s'ouvre dans un nouvel onglet. Revenez ici pour actualiser sa confirmation.</p>
          }
          @if (paymentWaiting()) {
            <p class="notice" role="status">Initiation enregistrée. Le paiement n'est pas encore confirmé. Consultez le prestataire si un lien sécurisé est affiché, puis actualisez le statut.</p>
          }
          @if (paymentError()) {
            <p role="alert">{{ paymentError() }}</p>
          }
          @if (!financial()?.paymentAvailable && financial()?.financialStatus === 'PREPARED') {
            <p>Le paiement en ligne n'est pas encore ouvert pour cette commande.</p>
          }
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
    .action-link:disabled {opacity:.55;cursor:wait}
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
  readonly paymentBusy = signal(false);
  readonly paymentWaiting = signal(false);
  readonly paymentError = signal('');
  readonly checkoutUrl = signal<string | null>(null);
  private paymentKey: string | null = null;
  private token: string | null = null;
  private sequence = 0;

  constructor() {
    const params = this.route.paramMap.subscribe((map) => {
      this.publicId.set(map.get('publicIdentifier') ?? '');
    });
    const fragment = this.route.fragment.subscribe((value) => {
      const token = value?.startsWith('access=') ? value.slice('access='.length) : '';
      const nextToken = /^[A-Za-z0-9_-]{43}$/.test(token) ? token : null;
      if (nextToken !== this.token) {
        this.paymentKey = null;
        this.paymentBusy.set(false);
        this.paymentWaiting.set(false);
        this.checkoutUrl.set(null);
        this.paymentError.set('');
      }
      this.token = nextToken;
      void this.reload();
    });
    inject(DestroyRef).onDestroy(() => {
      this.sequence++;
      this.token = null;
      this.paymentKey = null;
      this.checkoutUrl.set(null);
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
      case 'PAYMENT_IN_PROGRESS': return 'Paiement en cours chez Bictorys — en attente de confirmation';
      case 'SETTLEMENT_PROCESSING': return 'Règlement en cours de confirmation';
      case 'REVIEW_REQUIRED': return 'Vérification manuelle nécessaire';
      case 'PREPARED': return 'Paiement non encore confirmé';
      case 'NOT_PREPARED': return 'Paiement non préparé';
      case 'EXPIRED': return 'Délai de règlement expiré';
      case 'UNAVAILABLE': return 'Paiement indisponible';
      default: return 'État à vérifier';
    }
  }

  canPay(): boolean {
    const order = this.order();
    const status = this.financial();
    return !this.busy() && !this.paymentBusy() && !this.paymentWaiting()
      && !this.paymentProblem() && !!this.token
      && order?.status === 'PENDING_PAYMENT_INTEGRATION'
      && status?.financialStatus === 'PREPARED' && status.paymentAvailable === true
      && order.currency === 'XOF' && status.currency === 'XOF'
      && Number.isSafeInteger(order.acceptedTotalXof)
      && (order.acceptedTotalXof ?? 0) > 0
      && status.acceptedTotalXof === order.acceptedTotalXof;
  }

  async pay() {
    if (!this.canPay() || !this.token) return;
    const token = this.token;
    const sequence = this.sequence;
    this.paymentKey ??= crypto.randomUUID();
    this.paymentBusy.set(true);
    this.paymentError.set('');
    this.checkoutUrl.set(null);
    try {
      const receipt = await this.api.initiateDirectPayment(token, this.paymentKey);
      if (sequence !== this.sequence || token !== this.token) return;
      if (receipt.currency !== 'XOF' || receipt.expectedAmount !== this.order()?.acceptedTotalXof) {
        this.paymentWaiting.set(true);
        this.paymentError.set('La réponse du paiement ne correspond pas au montant attendu. Aucune confirmation possible.');
        return;
      }
      if (['FAILED', 'CANCELLED', 'EXPIRED'].includes(receipt.status)
          && !receipt.reconciliationRequired) {
        this.paymentKey = null;
        this.paymentWaiting.set(false);
        this.paymentError.set('Cette tentative a échoué. Actualisez le statut avant de réessayer.');
        return;
      }
      this.paymentWaiting.set(true);
      this.checkoutUrl.set(receipt.status === 'PENDING' && !receipt.reconciliationRequired
        ? safeBictorysCheckout(receipt.checkoutUrl ?? undefined)
        : null);
    } catch {
      if (sequence === this.sequence && token === this.token) {
        // Network errors are ambiguous. Keep the same idempotency key for a retry,
        // never claim a payment succeeded, and encourage checking the server first.
        this.paymentError.set('Initiation non confirmée. Actualisez le statut. En cas de nouvel essai, la même clé de sécurité sera réutilisée.');
      }
    } finally {
      if (sequence === this.sequence && token === this.token) this.paymentBusy.set(false);
    }
  }

  async reload() {
    const sequence = ++this.sequence;
    const token = this.token;
    if (this.paymentBusy()) {
      // A payment request can finish after this refresh. Do not reopen checkout
      // or trust a response whose previous sequence has been superseded.
      this.paymentWaiting.set(true);
      this.paymentBusy.set(false);
    }
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
        if (payment.value.financialStatus === 'SETTLEMENT_VERIFIED') {
          this.paymentWaiting.set(false);
          this.paymentKey = null;
          this.checkoutUrl.set(null);
          this.paymentError.set('');
        }
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
