import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  EMPTY,
  Subscription,
  catchError,
  exhaustMap,
  finalize,
  firstValueFrom,
  take,
  takeUntil,
  takeWhile,
  timer,
} from 'rxjs';
import { OrderTrackingView, PaymentFeeQuoteView } from '../../api/generated/models';
import { PublicOrderApi } from '../checkout/public-order-api';
import {
  PublicOrderSessionService,
  safeCheckoutUrl,
} from '../checkout/public-order-session.service';
import { CartStore } from '../checkout/cart.store';
import { publicMessage } from '../checkout/public-messages';
@Injectable()
export class PublicTrackingStore {
  private readonly api = inject(PublicOrderApi);
  readonly session = inject(PublicOrderSessionService);
  private readonly cart = inject(CartStore);
  private readonly destroy = inject(DestroyRef);
  readonly order = signal<OrderTrackingView | null>(null);
  readonly loading = signal(false);
  readonly polling = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly unavailable = signal(false);
  readonly feeQuotes = signal<PaymentFeeQuoteView[]>([]);
  readonly quoteLoading = signal(false);
  readonly quoteMessage = signal('');
  quoteExpired(quote: PaymentFeeQuoteView) {
    return !quote.expiresAt || Date.parse(quote.expiresAt) <= Date.now();
  }
  async loadQuotes() {
    const s = this.session.current();
    if (
      !s?.trackingToken ||
      s.payment ||
      s.paymentKey ||
      this.busy() ||
      this.quoteLoading() ||
      this.order()?.status !== 'PENDING_PAYMENT'
    )
      return;
    this.quoteLoading.set(true);
    this.quoteMessage.set('');
    try {
      const quotes = await firstValueFrom(this.api.quotes(s.orderNumber, s.trackingToken));
      if (
        this.session.current()?.trackingToken !== s.trackingToken ||
        this.order()?.status !== 'PENDING_PAYMENT'
      )
        return;
      this.feeQuotes.set(quotes);
      if (!quotes.length)
        this.quoteMessage.set('Aucun autre moyen de paiement disponible pour cette commande.');
    } catch {
      this.feeQuotes.set([]);
      this.quoteMessage.set(
        'Les frais ne peuvent pas être confirmés actuellement. Réessayez plus tard.',
      );
    } finally {
      this.quoteLoading.set(false);
    }
  }
  private subscription?: Subscription;
  constructor() {
    this.start();
  }
  start() {
    const token = this.session.current()?.trackingToken;
    if (!token) return;
    this.polling.set(true);
    // At most 46 requests / three minutes per visit. No overlap, no background interval.
    this.subscription = timer(0, 4000)
      .pipe(
        take(46),
        exhaustMap(() => this.api.track(token)),
        takeUntil(timer(180000)),
        takeWhile((order) => !this.final(order), true),
        catchError((e) => {
          this.handleError(e);
          return EMPTY;
        }),
        finalize(() => {
          this.polling.set(false);
          this.loading.set(false);
        }),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe((order) => this.receive(order));
  }
  async refresh() {
    if (this.loading()) return;
    const token = this.session.current()?.trackingToken;
    if (!token) return;
    this.subscription?.unsubscribe();
    this.loading.set(true);
    this.error.set('');
    try {
      this.receive(await firstValueFrom(this.api.track(token)));
    } catch (e) {
      this.handleError(e);
    } finally {
      this.loading.set(false);
    }
  }
  private receive(order: OrderTrackingView) {
    if (order.orderNumber !== this.session.current()?.orderNumber) {
      this.error.set('Le suivi reçu ne correspond pas à cette commande.');
      this.subscription?.unsubscribe();
      return;
    }
    this.order.set(order);
    this.error.set('');
    if (order.status === 'CONFIRMED') this.cart.clear(this.session.current()!.publicIdentifier);
  }
  private final(order: OrderTrackingView) {
    return ['CONFIRMED', 'COMPLETED', 'EXPIRED', 'CANCELLED'].includes(order.status ?? '');
  }
  private handleError(e: unknown) {
    this.error.set(publicMessage(e));
    if (e instanceof HttpErrorResponse && [401, 403, 404].includes(e.status))
      this.unavailable.set(true);
  }
  async resumeInitiation() {
    const s = this.session.current();
    if (s?.paymentKey && s.paymentQuoteId) await this.initiate(undefined, true);
  }
  async initiate(quote?: PaymentFeeQuoteView, resume = false) {
    const s = this.session.current();
    if (
      this.busy() ||
      !s?.trackingToken ||
      s.payment ||
      this.unavailable() ||
      this.order()?.status !== 'PENDING_PAYMENT'
    )
      return;
    if (this.order()?.paymentDeadline && Date.parse(this.order()!.paymentDeadline!) <= Date.now()) {
      this.error.set('Le délai de paiement est dépassé. Actualisez le suivi.');
      return;
    }
    if (resume && (!s.paymentKey || !s.paymentQuoteId)) return;
    const quoteId = resume ? s.paymentQuoteId : quote?.id;
    if (quote && (!quote.id || !this.feeQuotes().includes(quote) || this.quoteExpired(quote))) {
      this.error.set('Le devis a expiré. Actualisez les frais avant de payer.');
      return;
    }
    if (s.paymentKey && s.paymentQuoteId !== quoteId) {
      this.error.set('Une tentative existe déjà. Réutilisez le même moyen de paiement.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    try {
      const key = s.paymentKey ?? crypto.randomUUID();
      this.session.update({ paymentKey: key, paymentQuoteId: quoteId });
      const payment = await firstValueFrom(
        this.api.initiate(s.orderNumber, s.trackingToken, key, quoteId),
      );
      if (this.session.current()?.orderNumber === s.orderNumber)
        this.session.update({
          payment: { ...payment, checkoutUrl: safeCheckoutUrl(payment.checkoutUrl) ?? undefined },
        });
    } catch (e) {
      this.error.set(publicMessage(e));
    } finally {
      this.busy.set(false);
    }
  }
  checkoutUrl() {
    const s = this.session.current();
    return s?.payment?.reconciliationRequired ? null : safeCheckoutUrl(s?.payment?.checkoutUrl);
  }
}
