import { Component, DestroyRef, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  DirectSalesApi,
  DirectTrackingView,
  DirectCustomerFinancialStatus,
} from './direct-sales.api';
import { PublicShell } from '../checkout/public-shell';
import { money } from '../../shared/utils/display';
import { rejectedFeeQuote } from '../../core/http/api-error';
import type { PaymentFeeQuoteView } from '../../api/generated/models';
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
          <p class="tracking-ref">
            Référence : <strong>{{ data.orderNumber }}</strong>
          </p>
          <h2>État de ma commande</h2>
          <p>
            Le paiement et le suivi de la commande sont vérifiés côté serveur. Votre commande n'est
            payée qu'après confirmation du prestataire.
          </p>
          <dl class="tracking-data">
            <div>
              <dt>Statut</dt>
              <dd>{{ orderLabel(data.status) }}</dd>
            </div>
            <div>
              <dt>Remise</dt>
              <dd>
                {{
                  data.fulfillmentMethod === 'PICKUP'
                    ? 'Retrait à convenir'
                    : 'Livraison à confirmer'
                }}
              </dd>
            </div>
            <div>
              <dt>Articles</dt>
              <dd>{{ money(data.goodsSubtotalXof, data.currency) }}</dd>
            </div>
            <div>
              <dt>Frais de plateforme</dt>
              <dd>{{ money(data.platformFeeXof, data.currency) }}</dd>
            </div>
            @if (data.shippingFeeXof !== null) {
              <div>
                <dt>Livraison</dt>
                <dd>{{ money(data.shippingFeeXof, data.currency) }}</dd>
              </div>
            }
            @if (data.acceptedTotalXof !== null) {
              <div>
                <dt>Total accepté</dt>
                <dd>{{ money(data.acceptedTotalXof, data.currency) }}</dd>
              </div>
            }
            <div>
              <dt>Paiement</dt>
              <dd>{{ paymentLabel() }}</dd>
            </div>
          </dl>
          @if (canPay() && hasProvider('BICTORYS')) {
            <button type="button" class="action-link" [disabled]="paymentBusy()" (click)="pay()">
              Payer {{ money(data.acceptedTotalXof ?? undefined, data.currency) }} avec Bictorys
            </button>
          }
          @if (canPay() && hasProvider('SASPAY')) {
            <button type="button" class="action-link" (click)="prepareSaspay()">
              Voir les frais SasPay
            </button>
          }
          @for (quote of quotes(); track quote.id) {
            @if (canPay()) {
              <section>
                <h3>{{ quote.networkLabel }}</h3>
                <p>Prix convenu : {{ money(quote.saleAmount, quote.currency) }}.</p>
                <p>
                  Frais d’encaissement à votre charge :
                  {{ money(quote.collectionFee, quote.currency) }}.
                </p>
                <p>
                  Net conservé pour la vendeuse : {{ money(quote.merchantNet, quote.currency) }}.
                  Commission Groupia : {{ money(quote.groupiaCommission, quote.currency) }}.
                </p>
                <button type="button" class="action-link" (click)="pay(quote)">
                  Accepter les frais et payer {{ money(quote.totalCharged, quote.currency) }}
                </button>
              </section>
            }
          }
          @if (acceptedQuote(); as quote) {
            <p>
              Montant accepté : {{ money(quote.totalCharged, quote.currency) }}, dont
              {{ money(quote.collectionFee, quote.currency) }} de frais d’encaissement.
            </p>
          }
          @if (phoneConfirmation()) {
            <p role="status">
              Confirmez la demande sur votre téléphone, puis actualisez le statut.
            </p>
          }
          @if (paymentBusy()) {
            <p role="status">Préparation du paiement sécurisé…</p>
          }
          @if (checkoutUrl(); as url) {
            <p>
              <a
                class="action-link"
                [href]="url"
                target="_blank"
                rel="noopener noreferrer"
                referrerpolicy="no-referrer"
                >Ouvrir le paiement sécurisé</a
              >
            </p>
            <p>
              Le paiement s'ouvre dans un nouvel onglet. Revenez ici pour actualiser sa
              confirmation.
            </p>
          }
          @if (paymentWaiting()) {
            <p class="notice" role="status">
              Initiation enregistrée. Le paiement n'est pas encore confirmé. Consultez le
              prestataire si un lien sécurisé est affiché, puis actualisez le statut.
            </p>
          }
          @if (paymentError()) {
            <p role="alert">{{ paymentError() }}</p>
          }
          @if (!financial()?.paymentAvailable && financial()?.financialStatus === 'PREPARED') {
            <p>Le paiement en ligne n'est pas encore ouvert pour cette commande.</p>
          }
          @if (paymentProblem() || financial()?.financialStatus === 'REVIEW_REQUIRED') {
            <p class="notice" role="alert">
              Le paiement nécessite une vérification. Ne retirez pas les articles et n'organisez pas
              de livraison sur la base de cette page.
            </p>
          }
          <p class="notice">
            Conservez ce lien privé pour revenir consulter votre commande. Toute personne qui
            possède ce lien peut voir ce résumé.
          </p>
          <button type="button" class="action-link" (click)="reload()">Actualiser le statut</button>
        } @else {
          <p role="alert">{{ message() || 'Lien de commande indisponible.' }}</p>
          <p>
            Le numéro de commande seul ne donne pas accès au suivi. Utilisez le lien privé reçu lors
            de la confirmation.
          </p>
        }
        <a class="back-link" [routerLink]="['/v', publicId(), 'recuperation']">
          J'ai perdu mon lien : vérifier mon numéro
        </a>
        <a class="back-link" [routerLink]="['/v', publicId()]">Retour à la boutique</a>
      </section>
    </g-public-shell>
  `,
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
      }
      .direct-tracking {
        max-width: 740px;
        margin: 24px auto;
        padding: clamp(20px, 4vw, 36px);
        border: 1px solid var(--border);
        border-radius: 18px;
        background: var(--surface);
        display: grid;
        gap: 18px;
        min-width: 0;
      }
      .eyebrow {
        font-size: 11px;
        text-transform: uppercase;
        color: var(--primary);
        font-weight: 700;
      }
      h1 {
        font-size: clamp(24px, 4vw, 34px);
        letter-spacing: -0.035em;
      }
      h2 {
        font-size: 18px;
      }
      p {
        font-size: 14px;
        line-height: 1.6;
        color: var(--muted);
      }
      .tracking-ref strong {
        color: var(--text);
        overflow-wrap: anywhere;
      }
      .tracking-data {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 16px;
        margin: 0;
      }
      .tracking-data div {
        min-width: 0;
      }
      .tracking-data dt {
        font-size: 12px;
        color: var(--muted);
      }
      .tracking-data dd {
        margin: 5px 0 0;
        font-size: 14px;
        font-weight: 700;
        overflow-wrap: anywhere;
      }
      .notice {
        padding: 14px;
        border: 1px solid var(--hero-border);
        border-radius: 12px;
        background: var(--primary-soft);
      }
      .action-link {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: fit-content;
        min-height: 44px;
        padding: 10px 16px;
        border: 0;
        border-radius: 10px;
        background: var(--primary);
        color: var(--on-primary);
        cursor: pointer;
        font: inherit;
        font-weight: 650;
      }
      .action-link:disabled {
        opacity: 0.55;
        cursor: wait;
      }
      .back-link {
        font-size: 13px;
        color: var(--primary);
        font-weight: 650;
      }
      @media (max-width: 620px) {
        .tracking-data {
          grid-template-columns: 1fr;
        }
      }
    `,
  ],
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
  readonly quotes = signal<PaymentFeeQuoteView[]>([]);
  readonly acceptedQuote = signal<PaymentFeeQuoteView | null>(null);
  readonly phoneConfirmation = signal(false);
  private commandQuote?: PaymentFeeQuoteView;
  private commandProvider?: string;
  private storageKey: string | null = null;
  private paymentKey: string | null = null;
  hasProvider(provider: string) {
    return (this.financial()?.availableProviders ?? ['BICTORYS']).includes(provider);
  }
  private rememberCommand() {
    try {
      if (this.storageKey) {
        if (this.paymentKey)
          sessionStorage.setItem(
            this.storageKey,
            JSON.stringify({
              key: this.paymentKey,
              provider: this.commandProvider,
              quote: this.commandQuote,
            }),
          );
        else sessionStorage.removeItem(this.storageKey);
      }
    } catch {
      /* Server attempt recovery remains available if storage is denied. */
    }
  }
  private async restoreCommand(token: string, sequence: number) {
    try {
      const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token));
      if (sequence !== this.sequence || token !== this.token) return;
      this.storageKey =
        'groupia.direct-payment:' +
        Array.from(new Uint8Array(digest))
          .map((b) => b.toString(16).padStart(2, '0'))
          .join('');
      if (this.paymentKey) return;
      const saved = JSON.parse(sessionStorage.getItem(this.storageKey) ?? 'null');
      if (
        saved &&
        /^[0-9a-f-]{36}$/i.test(saved.key) &&
        ['SASPAY', 'BICTORYS'].includes(saved.provider)
      ) {
        this.paymentKey = saved.key;
        this.commandProvider = saved.provider;
        this.commandQuote = saved.quote;
        if (saved.quote?.id && saved.quote.saleAmount === this.order()?.acceptedTotalXof)
          this.quotes.set([saved.quote]);
      }
    } catch {
      /* Storage or digest may be unavailable; keep in-memory identity. */
    }
  }
  private safeSaspayUrl(value?: string) {
    try {
      const url = new URL(value ?? '');
      return url.protocol === 'https:' &&
        url.hostname === 'pay.saspay.me' &&
        !url.username &&
        !url.password &&
        !url.hash &&
        (!url.port || url.port === '443')
        ? url.href
        : null;
    } catch {
      return null;
    }
  }
  private recoverAttempt() {
    const state = this.financial();
    const recovery = state?.latestAttempt;
    if (state?.financialStatus !== 'PAYMENT_IN_PROGRESS' || !recovery?.attempt) return;
    const attempt = recovery.attempt;
    if (
      attempt.reconciliationRequired ||
      attempt.currency !== this.order()?.currency ||
      attempt.expectedAmount !== this.order()?.acceptedTotalXof ||
      attempt.status !== 'PENDING'
    )
      return;
    this.paymentWaiting.set(true);
    this.acceptedQuote.set(recovery.quote ?? null);
    this.checkoutUrl.set(
      recovery.provider === 'SASPAY'
        ? this.safeSaspayUrl(attempt.checkoutUrl)
        : recovery.provider === 'BICTORYS'
          ? safeBictorysCheckout(attempt.checkoutUrl)
          : null,
    );
    this.phoneConfirmation.set(recovery.provider === 'SASPAY' && !attempt.checkoutUrl);
  }
  async prepareSaspay() {
    if (!this.canPay() || !this.token || !this.hasProvider('SASPAY')) return;
    if (this.paymentKey && this.commandProvider) {
      this.paymentError.set('Reprenez le paiement déjà demandé avant de changer de moyen.');
      return;
    }
    const token = this.token,
      sequence = this.sequence;
    this.paymentBusy.set(true);
    this.paymentError.set('');
    try {
      const quotes = await this.api.quoteDirectPayment(token);
      if (sequence !== this.sequence || token !== this.token) return;
      this.quotes.set(quotes);
      if (!quotes.length)
        this.paymentError.set(
          'Aucun moyen SasPay disponible pour les coordonnées de cette commande.',
        );
    } catch {
      if (sequence === this.sequence)
        this.paymentError.set('Les frais sont indisponibles. Réessayez plus tard.');
    } finally {
      if (sequence === this.sequence) this.paymentBusy.set(false);
    }
  }
  private token: string | null = null;
  private sequence = 0;
  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  private pollAttempts = 0;
  private readonly pollDelayMs = 15000;
  private readonly maxPollAttempts = 12;

  private stopPoll() {
    if (this.pollTimer !== null) clearTimeout(this.pollTimer);
    this.pollTimer = null;
  }

  private schedulePoll() {
    this.stopPoll();
    const stage = this.financial()?.financialStatus;
    const isPending =
      stage === 'PAYMENT_IN_PROGRESS' ||
      stage === 'SETTLEMENT_PROCESSING' ||
      (stage === 'PREPARED' && this.paymentWaiting());
    if (!isPending || !this.token || !this.order() || this.pollAttempts >= this.maxPollAttempts)
      return;
    this.pollTimer = setTimeout(() => {
      this.pollTimer = null;
      this.pollAttempts++;
      void this.pollOnce();
    }, this.pollDelayMs);
  }

  private acknowledgeVerifiedSettlement() {
    this.paymentWaiting.set(false);
    this.paymentKey = null;
    this.rememberCommand();
    this.commandProvider = undefined;
    this.commandQuote = undefined;
    this.quotes.set([]);
    this.acceptedQuote.set(null);
    this.phoneConfirmation.set(false);
    this.checkoutUrl.set(null);
    this.paymentError.set('');
    this.stopPoll();
  }

  /** A bounded, read-only refresh: keep the existing order visible during network I/O. */
  private async pollOnce() {
    const sequence = this.sequence;
    const token = this.token;
    if (!token || !this.order()) return;
    const [tracking, payment] = await Promise.allSettled([
      this.api.trackDirectOrder(token),
      this.api.directPaymentStatus(token),
    ]);
    if (sequence !== this.sequence || token !== this.token) return;
    if (
      tracking.status === 'fulfilled' &&
      payment.status === 'fulfilled' &&
      tracking.value.orderNumber === payment.value.orderNumber &&
      tracking.value.currency === payment.value.currency &&
      tracking.value.acceptedTotalXof === payment.value.acceptedTotalXof
    ) {
      this.order.set(tracking.value);
      this.financial.set(payment.value);
      this.recoverAttempt();
      this.paymentProblem.set(false);
      if (payment.value.financialStatus === 'SETTLEMENT_VERIFIED') {
        this.acknowledgeVerifiedSettlement();
      } else if (
        ['REVIEW_REQUIRED', 'EXPIRED', 'UNAVAILABLE'].includes(payment.value.financialStatus)
      ) {
        this.paymentWaiting.set(false);
        this.phoneConfirmation.set(false);
        this.checkoutUrl.set(null);
      }
    } else {
      // An unavailable or contradictory server read must not unlock payment or fulfillment.
      this.paymentProblem.set(true);
      this.checkoutUrl.set(null);
    }
    this.schedulePoll();
  }

  constructor() {
    const params = this.route.paramMap.subscribe((map) => {
      this.publicId.set(map.get('publicIdentifier') ?? '');
    });
    const fragment = this.route.fragment.subscribe((value) => {
      const token = value?.startsWith('access=') ? value.slice('access='.length) : '';
      const nextToken = /^[A-Za-z0-9_-]{43}$/.test(token) ? token : null;
      if (nextToken !== this.token) {
        this.stopPoll();
        this.pollAttempts = 0;
        this.paymentKey = null;
        this.commandProvider = undefined;
        this.commandQuote = undefined;
        this.storageKey = null;
        this.quotes.set([]);
        this.acceptedQuote.set(null);
        this.phoneConfirmation.set(false);
        this.paymentBusy.set(false);
        this.paymentWaiting.set(false);
        this.phoneConfirmation.set(false);
        this.checkoutUrl.set(null);
        this.paymentError.set('');
      }
      this.token = nextToken;
      void this.reload();
    });
    inject(DestroyRef).onDestroy(() => {
      this.stopPoll();
      this.sequence++;
      this.token = null;
      this.paymentKey = null;
      this.checkoutUrl.set(null);
      params.unsubscribe();
      fragment.unsubscribe();
    });
  }

  orderLabel(value: string) {
    if (value === 'REFUND_PENDING') return 'Commande annulée · remboursement à traiter avec la boutique';
    if (value === 'REFUNDED') return 'Remboursement externe déclaré par la boutique';
    if (value === 'CANCELLED') return 'Commande annulée';
    if (value === 'PENDING_PAYMENT_INTEGRATION') return 'En attente de règlement';
    if (this.financial()?.financialStatus !== 'SETTLEMENT_VERIFIED') {
      return 'Traitement suspendu : règlement à vérifier';
    }
    switch (value) {
      case 'DISPATCHED':
        return 'Commande expédiée';
      case 'PICKED_UP':
        return 'Commande retirée';
      case 'DELIVERED':
        return 'Commande livrée';
      case 'COMPLETED':
        return 'Commande clôturée';
      case 'PAYMENT_CONFIRMED':
        return 'Règlement confirmé';
      case 'PREPARING':
        return 'Commande en préparation';
      case 'READY_FOR_PICKUP':
        return 'Prête pour le retrait (à confirmer avec la boutique)';
      case 'READY_FOR_DISPATCH':
        return 'Prête pour l’expédition (à confirmer avec la boutique)';
      default:
        return 'Statut à vérifier';
    }
  }

  paymentLabel() {
    if (this.paymentProblem()) return 'État financier temporairement indisponible';
    switch (this.financial()?.financialStatus) {
      case 'SETTLEMENT_VERIFIED':
        return 'Règlement vérifié';
      case 'PAYMENT_IN_PROGRESS':
        return 'Paiement en cours chez le prestataire — en attente de confirmation';
      case 'SETTLEMENT_PROCESSING':
        return 'Règlement en cours de confirmation';
      case 'REVIEW_REQUIRED':
        return 'Vérification manuelle nécessaire';
      case 'PREPARED':
        return 'Paiement non encore confirmé';
      case 'NOT_PREPARED':
        return 'Paiement non préparé';
      case 'EXPIRED':
        return 'Délai de règlement expiré';
      case 'UNAVAILABLE':
        return 'Paiement indisponible';
      default:
        return 'État à vérifier';
    }
  }

  canPay(): boolean {
    const order = this.order();
    const status = this.financial();
    return (
      !this.busy() &&
      !this.paymentBusy() &&
      !this.paymentWaiting() &&
      !this.paymentProblem() &&
      !!this.token &&
      order?.status === 'PENDING_PAYMENT_INTEGRATION' &&
      status?.financialStatus === 'PREPARED' &&
      status.paymentAvailable === true &&
      order.currency === 'XOF' &&
      status.currency === 'XOF' &&
      Number.isSafeInteger(order.acceptedTotalXof) &&
      (order.acceptedTotalXof ?? 0) > 0 &&
      status.acceptedTotalXof === order.acceptedTotalXof
    );
  }

  async pay(quote?: PaymentFeeQuoteView) {
    if (!this.canPay() || !this.token || !this.hasProvider(quote ? 'SASPAY' : 'BICTORYS')) return;
    if (quote && (!quote.id || !this.quotes().some((q) => q.id === quote.id))) return;
    if (
      this.paymentKey &&
      this.commandProvider &&
      (this.commandProvider !== (quote ? 'SASPAY' : 'BICTORYS') ||
        this.commandQuote?.id !== quote?.id)
    ) {
      this.paymentError.set('Reprenez le paiement déjà demandé avant de changer de moyen.');
      return;
    }
    if (quote && Date.parse(quote.expiresAt ?? '') <= Date.now() && !this.paymentKey) {
      this.paymentError.set('Le devis a expiré. Actualisez les frais avant de payer.');
      return;
    }
    const token = this.token;
    const sequence = this.sequence;
    this.paymentKey ??= crypto.randomUUID();
    this.commandProvider = quote ? 'SASPAY' : 'BICTORYS';
    this.commandQuote = quote;
    this.acceptedQuote.set(quote ?? null);
    this.rememberCommand();
    this.paymentBusy.set(true);
    this.paymentError.set('');
    this.checkoutUrl.set(null);
    try {
      const receipt = quote
        ? await this.api.initiateDirectPayment(token, this.paymentKey, quote.id)
        : await this.api.initiateDirectPayment(token, this.paymentKey);
      if (sequence !== this.sequence || token !== this.token) return;
      if (receipt.currency !== 'XOF' || receipt.expectedAmount !== this.order()?.acceptedTotalXof) {
        this.paymentWaiting.set(true);
        this.paymentError.set(
          'La réponse du paiement ne correspond pas au montant attendu. Aucune confirmation possible.',
        );
        return;
      }
      if (
        ['FAILED', 'CANCELLED', 'EXPIRED'].includes(receipt.status) &&
        !receipt.reconciliationRequired
      ) {
        this.paymentKey = null;
        this.rememberCommand();
        this.commandProvider = undefined;
        this.commandQuote = undefined;
        this.paymentWaiting.set(false);
        this.paymentError.set('Cette tentative a échoué. Actualisez le statut avant de réessayer.');
        return;
      }
      this.paymentWaiting.set(true);
      this.checkoutUrl.set(
        receipt.status === 'PENDING' && !receipt.reconciliationRequired
          ? quote
            ? this.safeSaspayUrl(receipt.checkoutUrl ?? undefined)
            : safeBictorysCheckout(receipt.checkoutUrl ?? undefined)
          : null,
      );
      this.phoneConfirmation.set(
        !!quote &&
          receipt.status === 'PENDING' &&
          !receipt.checkoutUrl &&
          !receipt.reconciliationRequired,
      );
      this.pollAttempts = 0;
      this.schedulePoll();
    } catch (error) {
      if (sequence === this.sequence && token === this.token) {
        if (rejectedFeeQuote(error)) {
          this.paymentKey = null;
          this.rememberCommand();
          this.commandProvider = undefined;
          this.commandQuote = undefined;
          this.quotes.set([]);
          this.acceptedQuote.set(null);
          this.paymentError.set(
            'Le devis ne peut plus être utilisé. Actualisez les frais avant de payer.',
          );
          return;
        }
        // Network errors are ambiguous. Keep the same idempotency key for a retry,
        // never claim a payment succeeded, and encourage checking the server first.
        this.paymentError.set(
          'Initiation non confirmée. Actualisez le statut. En cas de nouvel essai, la même clé de sécurité sera réutilisée.',
        );
      }
    } finally {
      if (sequence === this.sequence && token === this.token) this.paymentBusy.set(false);
    }
  }

  async reload() {
    this.stopPoll();
    this.pollAttempts = 0;
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
      if (
        payment.status === 'fulfilled' &&
        payment.value.orderNumber === order.value.orderNumber &&
        payment.value.currency === order.value.currency &&
        payment.value.acceptedTotalXof === order.value.acceptedTotalXof
      ) {
        this.financial.set(payment.value);
        await this.restoreCommand(token, sequence);
        if (sequence !== this.sequence) return;
        this.recoverAttempt();
        if (payment.value.financialStatus === 'SETTLEMENT_VERIFIED') {
          this.acknowledgeVerifiedSettlement();
        } else if (
          ['REVIEW_REQUIRED', 'EXPIRED', 'UNAVAILABLE'].includes(payment.value.financialStatus)
        ) {
          this.paymentWaiting.set(false);
          this.phoneConfirmation.set(false);
          this.checkoutUrl.set(null);
        }
      } else {
        this.paymentProblem.set(true);
      }
    } catch {
      if (sequence === this.sequence) {
        this.message.set('Ce lien est invalide ou cette commande n’est pas accessible.');
      }
    } finally {
      if (sequence === this.sequence) {
        this.busy.set(false);
        this.schedulePoll();
      }
    }
  }
}
