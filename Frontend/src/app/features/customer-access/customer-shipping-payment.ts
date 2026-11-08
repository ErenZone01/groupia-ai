import {
  Component,
  DestroyRef,
  InjectionToken,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { rejectedFeeQuote } from '../../core/http/api-error';
import { PaymentFeeQuoteView, ShippingSettlementView } from '../../api/generated/models';
import { CustomerAccessService } from './customer-access.service';
import { ShippingSettlement } from '../../shared/ui/shipping-settlement';
import { Button } from '../../shared/ui/button';
import { money } from '../../shared/utils/display';
import { safeCheckoutUrl } from '../checkout/public-order-session.service';
import { accessError } from './access-errors';
export const SHIPPING_CHECKOUT_NAVIGATION = new InjectionToken<(url: string) => void>(
  'Shipping checkout navigation',
  {
    providedIn: 'root',
    factory: () => (url) => {
      window.open(url, '_blank', 'noopener,noreferrer');
    },
  },
);
export function safeBictorysCheckout(raw?: string): string | null {
  const value = safeCheckoutUrl(raw);
  if (!value) return null;
  const url = new URL(value);
  return ['pay.bictorys.com', 'pay.test.bictorys.com'].includes(url.hostname) &&
    !url.hash &&
    (!url.port || url.port === '443')
    ? value
    : null;
}
@Component({
  selector: 'g-customer-shipping-payment',
  imports: [ShippingSettlement, Button],
  template: `<g-shipping-settlement [value]="value()">
    @if (value()?.status === 'BALANCE_DUE') {
      @if (!value()?.paymentInProgress && !waiting()) {
        @if (providers().includes('BICTORYS')) {
          <button gButton [loading]="busy()" [disabled]="busy() || !validAmount()" (click)="pay()">
            Payer {{ money(value()?.amountDue) }} de transport
          </button>
        }
        @if (providers().includes('SASPAY')) {
          <button gButton variant="secondary" [disabled]="busy()" (click)="prepareSaspay()">
            Voir les frais SasPay du transport
          </button>
        }
        @for (quote of quotes(); track quote.id) {
          <p>
            {{ quote.networkLabel }} · Transport : {{ money(quote.saleAmount) }} · Frais à votre
            charge : {{ money(quote.collectionFee) }}.
          </p>
          <button gButton [disabled]="busy()" (click)="pay(quote)">
            Accepter les frais et payer {{ money(quote.totalCharged) }}
          </button>
        }
      }
      @if (checkoutUrl(); as url) {
        <p>
          <a
            gButton
            [href]="url"
            target="_blank"
            rel="noopener noreferrer"
            referrerpolicy="no-referrer"
            >Ouvrir le paiement sécurisé</a
          >
        </p>
      }
      <p class="muted">
        Le paiement sécurisé s’ouvre dans un nouvel onglet. Revenez ici pour suivre sa confirmation.
      </p>
    }
    @if (acceptedQuote(); as quote) {
      <p>
        Montant accepté : {{ money(quote.totalCharged) }}, dont {{ money(quote.collectionFee) }} de
        frais.
      </p>
    }
    @if (message()) {
      <p role="status">{{ message() }}</p>
    }
    @if (error()) {
      <p role="alert">{{ error() }}</p>
    }
  </g-shipping-settlement>`,
  styles: `
    p {
      margin: var(--s3) 0;
    }
    .g-button {
      max-width: 100%;
      white-space: normal;
      min-height: 44px;
    }
  `,
})
export class CustomerShippingPayment {
  readonly value = input<ShippingSettlementView>();
  readonly orderNumber = input.required<string>();
  readonly refresh = output<void>();
  readonly busy = signal(false);
  readonly waiting = signal(false);
  readonly message = signal('');
  readonly error = signal('');
  readonly checkoutUrl = signal<string | null>(null);
  readonly money = money;
  readonly providers = signal<string[]>(['BICTORYS']);
  readonly quotes = signal<PaymentFeeQuoteView[]>([]);
  readonly acceptedQuote = signal<PaymentFeeQuoteView | null>(null);
  private commandQuote?: PaymentFeeQuoteView;
  private commandProvider?: string;
  private scope = '';
  private readSequence = 0;
  private key: string | null = null;
  private remember() {
    try {
      if (!this.scope) return;
      const name = 'groupia.shipping-command:' + this.scope;
      if (this.key)
        sessionStorage.setItem(
          name,
          JSON.stringify({
            key: this.key,
            provider: this.commandProvider,
            quote: this.commandQuote,
          }),
        );
      else sessionStorage.removeItem(name);
    } catch {
      /* In-memory fallback; server recovery remains authoritative. */
    }
  }
  private async loadOptions(scope: string) {
    const read = ++this.readSequence;
    try {
      const value = await this.access.shippingPaymentOptions(this.orderNumber());
      if (this.destroyed || scope !== this.scope || read !== this.readSequence) return;
      this.providers.set(value.availableProviders ?? ['BICTORYS']);
      const recovery = value.latestAttempt;
      if (
        this.value()?.paymentInProgress &&
        recovery?.attempt?.status === 'PENDING' &&
        !recovery.attempt.reconciliationRequired &&
        recovery.attempt.currency === this.value()?.currency &&
        recovery.attempt.expectedAmount === this.value()?.amountDue
      ) {
        this.waiting.set(true);
        this.acceptedQuote.set(recovery.quote ?? null);
        this.checkoutUrl.set(
          recovery.provider === 'SASPAY'
            ? this.saspayUrl(recovery.attempt.checkoutUrl)
            : recovery.provider === 'BICTORYS'
              ? safeBictorysCheckout(recovery.attempt.checkoutUrl)
              : null,
        );
        if (recovery.provider === 'SASPAY' && !recovery.attempt.checkoutUrl)
          this.message.set('Confirmez la demande sur votre téléphone, puis vérifiez le paiement.');
      }
    } catch {
      /* No new payment is enabled by a failed recovery read. */
    }
  }
  private saspayUrl(raw?: string) {
    const value = safeCheckoutUrl(raw);
    if (!value) return null;
    const url = new URL(value);
    return url.hostname === 'pay.saspay.me' && !url.hash && (!url.port || url.port === '443')
      ? value
      : null;
  }
  async prepareSaspay() {
    if (
      this.busy() ||
      this.waiting() ||
      this.value()?.paymentInProgress ||
      this.value()?.status !== 'BALANCE_DUE' ||
      !this.providers().includes('SASPAY')
    )
      return;
    if (this.key && this.commandProvider) {
      this.error.set('Reprenez la demande déjà envoyée avant de changer de moyen.');
      return;
    }
    const scope = this.scope;
    this.busy.set(true);
    this.error.set('');
    try {
      const quotes = await this.access.quoteShipping(this.orderNumber());
      if (this.destroyed || scope !== this.scope) return;
      this.quotes.set(quotes);
      if (!quotes.length) this.error.set('Aucun moyen SasPay disponible pour ce transport.');
    } catch (error) {
      if (!this.destroyed && scope === this.scope) this.error.set(accessError(error));
    } finally {
      if (!this.destroyed && scope === this.scope) this.busy.set(false);
    }
  }
  private calculation: string | undefined;
  private previouslyPending = false;
  private readonly access = inject(CustomerAccessService);
  private readonly navigate = inject(SHIPPING_CHECKOUT_NAVIGATION);
  private destroyed = false;
  private timer?: ReturnType<typeof setInterval>;
  private polls = 0;
  constructor() {
    effect(() => {
      const v = this.value();
      const scope = this.orderNumber() + ':' + (v?.currentShippingCalculationId ?? '');
      if (scope !== this.scope) {
        this.scope = scope;
        this.commandQuote = undefined;
        this.commandProvider = undefined;
        this.quotes.set([]);
        this.acceptedQuote.set(null);
        this.busy.set(false);
        this.calculation = v?.currentShippingCalculationId;
        this.key = null;
        this.checkoutUrl.set(null);
        this.waiting.set(false);
      }
      if (!this.key) {
        try {
          const saved = JSON.parse(
            sessionStorage.getItem('groupia.shipping-command:' + scope) ?? 'null',
          );
          if (
            saved &&
            /^[0-9a-f-]{36}$/i.test(saved.key) &&
            ['SASPAY', 'BICTORYS'].includes(saved.provider)
          ) {
            this.key = saved.key;
            this.commandProvider = saved.provider;
            this.commandQuote = saved.quote;
            if (saved.quote?.saleAmount === v?.amountDue) this.quotes.set([saved.quote]);
          }
        } catch {
          /* Optional browser storage. */
        }
      }
      void this.loadOptions(scope);
      if (v?.status === 'SETTLED' || v?.status === 'CREDIT_DUE') {
        this.waiting.set(false);
        this.checkoutUrl.set(null);
        this.message.set('');
        this.key = null;
        this.remember();
        this.quotes.set([]);
        this.acceptedQuote.set(null);
        this.stop();
      } else if (v?.paymentInProgress) this.poll();
      else if (this.previouslyPending) {
        this.waiting.set(false);
        this.checkoutUrl.set(null);
        this.key = null;
        this.remember();
        this.commandProvider = undefined;
        this.commandQuote = undefined;
        this.message.set('La tentative est terminée. Le solde reste dû.');
        this.stop();
        this.polls = 0;
      }
      this.previouslyPending = v?.paymentInProgress === true;
    });
    const returned = () => {
      if (this.value()?.paymentInProgress || this.waiting()) {
        this.message.set('Vérification du paiement en cours');
        this.refresh.emit();
      }
    };
    window.addEventListener('focus', returned);
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      this.stop();
      window.removeEventListener('focus', returned);
    });
  }
  validAmount() {
    const amount = this.value()?.amountDue;
    return amount != null && Number.isSafeInteger(amount) && amount > 0;
  }
  async pay(quote?: PaymentFeeQuoteView) {
    if (
      this.busy() ||
      this.waiting() ||
      this.value()?.paymentInProgress ||
      this.value()?.status !== 'BALANCE_DUE' ||
      !this.validAmount()
    )
      return;
    if (!this.providers().includes(quote ? 'SASPAY' : 'BICTORYS')) return;
    if (quote && (!quote.id || !this.quotes().some((q) => q.id === quote.id))) return;
    if (
      this.key &&
      this.commandProvider &&
      (this.commandProvider !== (quote ? 'SASPAY' : 'BICTORYS') ||
        this.commandQuote?.id !== quote?.id)
    ) {
      this.error.set('Reprenez la demande déjà envoyée avant de changer de moyen.');
      return;
    }
    if (quote && Date.parse(quote.expiresAt ?? '') <= Date.now() && !this.key) {
      this.error.set('Le devis a expiré. Actualisez les frais.');
      return;
    }
    const scope = this.scope;
    this.key ??= crypto.randomUUID();
    this.commandQuote = quote;
    this.commandProvider = quote ? 'SASPAY' : 'BICTORYS';
    this.acceptedQuote.set(quote ?? null);
    this.remember();
    this.busy.set(true);
    this.error.set('');
    try {
      const attempt = quote
        ? await this.access.payShipping(this.orderNumber(), this.key, quote.id)
        : await this.access.payShipping(this.orderNumber(), this.key);
      if (this.destroyed || scope !== this.scope) return;
      if (
        ['FAILED', 'CANCELLED', 'EXPIRED'].includes(attempt.status ?? '') &&
        !attempt.reconciliationRequired
      ) {
        this.key = null;
        this.remember();
        this.commandProvider = undefined;
        this.commandQuote = undefined;
        this.error.set('Cette tentative n’a pas abouti. Vous pouvez réessayer.');
        return;
      }
      this.waiting.set(true);
      this.message.set('Vérification du paiement en cours');
      this.poll();
      const url =
        attempt.status === 'PENDING' && !attempt.reconciliationRequired
          ? quote
            ? this.saspayUrl(attempt.checkoutUrl)
            : safeBictorysCheckout(attempt.checkoutUrl)
          : null;
      this.checkoutUrl.set(url);
      if (
        quote &&
        attempt.status === 'PENDING' &&
        !attempt.checkoutUrl &&
        !attempt.reconciliationRequired
      )
        this.message.set('Confirmez la demande sur votre téléphone, puis vérifiez le paiement.');
      if (url) this.navigate(url);
    } catch (error) {
      if (!this.destroyed && scope === this.scope) {
        if (rejectedFeeQuote(error)) {
          this.key = null;
          this.remember();
          this.commandProvider = undefined;
          this.commandQuote = undefined;
          this.quotes.set([]);
          this.acceptedQuote.set(null);
        }
        this.error.set(accessError(error));
      }
    } finally {
      if (!this.destroyed && scope === this.scope) {
        this.busy.set(false);
        this.refresh.emit();
      }
    }
  }
  private poll() {
    if (this.timer || this.polls >= 36) return;
    this.timer = setInterval(() => {
      if (++this.polls >= 36) this.stop();
      this.refresh.emit();
    }, 5000);
  }
  private stop() {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
  }
}
