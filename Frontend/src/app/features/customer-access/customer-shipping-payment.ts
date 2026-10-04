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
import { ShippingSettlementView } from '../../api/generated/models';
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
        <button gButton [loading]="busy()" [disabled]="busy() || !validAmount()" (click)="pay()">
          Payer {{ money(value()?.amountDue) }} de transport
        </button>
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
  private key: string | null = null;
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
      if (v?.currentShippingCalculationId !== this.calculation) {
        this.calculation = v?.currentShippingCalculationId;
        this.key = null;
        this.checkoutUrl.set(null);
        this.waiting.set(false);
      }
      if (v?.status === 'SETTLED' || v?.status === 'CREDIT_DUE') {
        this.waiting.set(false);
        this.checkoutUrl.set(null);
        this.message.set('');
        this.stop();
      } else if (v?.paymentInProgress) this.poll();
      else if (this.previouslyPending) {
        this.waiting.set(false);
        this.checkoutUrl.set(null);
        this.key = null;
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
  async pay() {
    if (
      this.busy() ||
      this.waiting() ||
      this.value()?.paymentInProgress ||
      this.value()?.status !== 'BALANCE_DUE' ||
      !this.validAmount()
    )
      return;
    this.key ??= crypto.randomUUID();
    this.busy.set(true);
    this.error.set('');
    try {
      const attempt = await this.access.payShipping(this.orderNumber(), this.key);
      if (this.destroyed) return;
      if (
        ['FAILED', 'CANCELLED', 'EXPIRED'].includes(attempt.status ?? '') &&
        !attempt.reconciliationRequired
      ) {
        this.key = null;
        this.error.set('Cette tentative n’a pas abouti. Vous pouvez réessayer.');
        return;
      }
      this.waiting.set(true);
      this.message.set('Vérification du paiement en cours');
      this.poll();
      const url =
        attempt.status === 'PENDING' && !attempt.reconciliationRequired
          ? safeBictorysCheckout(attempt.checkoutUrl)
          : null;
      this.checkoutUrl.set(url);
      if (url) this.navigate(url);
    } catch (error) {
      if (!this.destroyed) this.error.set(accessError(error));
    } finally {
      if (!this.destroyed) {
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
