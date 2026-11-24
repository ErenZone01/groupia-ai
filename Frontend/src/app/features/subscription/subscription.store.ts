import { computed, effect, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom, forkJoin, Subject, takeUntil, timeout } from 'rxjs';
import type {
  BillingContactInput,
  PaymentFeeQuoteView,
  SubscriptionDetails,
  SubscriptionPeriodView,
  SubscriptionInvoiceView,
  SubscriptionInvoicePreview,
} from '../../api/generated/models';
import { SubscriptionControllerService } from '../../api/generated/services';
import { AuthService } from '../../core/auth/auth.service';
import { apiMessage, rejectedFeeQuote } from '../../core/http/api-error';
import { safeSaspayCheckout } from '../checkout/public-order-session.service';

interface PaymentCommand {
  creationKey: string;
  invoiceId?: string;
  paymentKey: string;
  knownAttempt?: boolean;
  provider?: 'BICTORYS' | 'SASPAY';
  quote?: PaymentFeeQuoteView;
  started?: boolean;
}

@Injectable({ providedIn: 'root' })
export class SubscriptionStore {
  private readonly api = inject(SubscriptionControllerService);
  private readonly auth = inject(AuthService);
  readonly details = signal<SubscriptionDetails | null>(null);
  readonly history = signal<SubscriptionPeriodView[]>([]);
  readonly invoices = signal<SubscriptionInvoiceView[]>([]);
  readonly preview = signal<SubscriptionInvoicePreview | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly checkoutUrl = signal<string | null>(null);
  readonly quotes = signal<PaymentFeeQuoteView[]>([]);
  readonly acceptedQuote = signal<PaymentFeeQuoteView | null>(null);
  readonly phoneConfirmation = signal(false);
  hasProvider(provider: string) {
    const details = this.details();
    return (
      !!details?.billingProviderAvailable &&
      (details.availableProviders ?? ['BICTORYS']).includes(provider)
    );
  }
  readonly currentInvoice = computed(
    () =>
      this.invoices().find(
        (i) =>
          i.status === 'OPEN' &&
          (Date.parse(i.periodEnd ?? '') > Date.now() || i.paymentStatus === 'SETTLED'),
      ) ?? null,
  );
  readonly futurePaid = computed(
    () =>
      this.invoices().find(
        (i) => i.status === 'PAID' && Date.parse(i.periodStart ?? '') > Date.now(),
      ) ?? null,
  );
  readonly renewalOpensAt = computed(() => {
    // First subscription purchase is permitted during trial. Only renewals are limited.
    if (!this.history().length) return null;
    const nextStart = Date.parse(this.preview()?.periodStart ?? '');
    return Number.isFinite(nextStart)
      ? new Date(nextStart - 7 * 24 * 60 * 60 * 1000).toISOString()
      : null;
  });
  renewalWindowOpen(): boolean {
    if (!this.history().length) return true;
    const opensAt = this.renewalOpensAt();
    return opensAt !== null && Date.now() >= Date.parse(opensAt);
  }
  private org = '';
  private session = -1;
  private generation = 0;
  private loadSequence = 0;
  private operation = 0;
  private cancelLoad = new Subject<void>();
  private cancelMutation = new Subject<void>();
  private pending?: Promise<void>;
  private readonly commands = new Map<string, PaymentCommand>();
  constructor() {
    effect(() => {
      if (this.session >= 0 && this.session !== this.auth.generation()) this.clear();
    });
  }
  clear() {
    this.cancelLoad.next();
    this.cancelMutation.next();
    this.generation++;
    this.loadSequence++;
    this.operation++;
    this.org = '';
    this.pending = undefined;
    this.session = this.auth.generation();
    this.commands.clear();
    this.resetView();
  }
  private resetView(resetBusy = true) {
    this.details.set(null);
    this.history.set([]);
    this.invoices.set([]);
    this.preview.set(null);
    this.checkoutUrl.set(null);
    this.quotes.set([]);
    this.acceptedQuote.set(null);
    this.phoneConfirmation.set(false);
    this.error.set('');
    if (resetBusy) this.busy.set(false);
    this.loading.set(false);
  }
  load(org: string, force = false): Promise<void> {
    if (!org) {
      this.clear();
      return Promise.resolve();
    }
    if (this.session !== this.auth.generation()) this.clear();
    if (org !== this.org) {
      this.cancelLoad.next();
      this.cancelMutation.next();
      this.generation++;
      this.operation++;
      this.org = org;
      this.pending = undefined;
      this.resetView();
    }
    if (!force && this.pending) return this.pending;
    if (!force && this.details()) return Promise.resolve();
    this.cancelLoad.next();
    const generation = this.generation,
      session = this.session,
      seq = ++this.loadSequence;
    this.loading.set(true);
    this.error.set('');
    const pending = firstValueFrom(
      forkJoin({
        details: this.api.details({ organizationId: org }),
        history: this.api.history({ organizationId: org }),
        invoices: this.api.listSubscriptionInvoices({ organizationId: org }),
        preview: this.api.previewSubscriptionInvoice({ organizationId: org }),
      }).pipe(timeout(15000), takeUntil(this.cancelLoad)),
    )
      .then((result) => {
        if (!this.current(org, generation, session) || seq !== this.loadSequence) return;
        this.details.set(result.details);
        this.history.set(result.history);
        this.invoices.set(result.invoices);
        this.preview.set(result.preview);
        const recoverable = result.invoices.find(
          (i) =>
            i.status === 'OPEN' &&
            i.attemptInProgress &&
            !i.reconciliationRequired &&
            i.paymentStatus !== 'SETTLED',
        );
        const recovery = result.details.canManageSubscription ? recoverable?.latestAttempt : null;
        if (recovery?.attempt) {
          this.checkoutUrl.set(this.safeUrl(recovery.attempt.checkoutUrl, recovery.provider));
          this.acceptedQuote.set(recovery.quote ?? null);
          this.phoneConfirmation.set(false); // Hosted checkout never requires a Groupia push screen.
        } else if (!recoverable || !result.details.canManageSubscription) {
          this.checkoutUrl.set(null);
          this.phoneConfirmation.set(false);
          this.acceptedQuote.set(null);
        }
        const command = this.getCommand(org);
        if (
          result.details.canManageSubscription &&
          command?.started &&
          command.quote &&
          (!this.currentInvoice() || this.currentInvoice()?.id === command.invoiceId) &&
          !this.currentInvoice()?.reconciliationRequired &&
          !this.futurePaid()
        )
          this.quotes.set([command.quote]);
      })
      .catch((error) => {
        if (!this.current(org, generation, session) || seq !== this.loadSequence) return;
        this.resetView(false);
        this.error.set(apiMessage(error));
      })
      .finally(() => {
        if (this.current(org, generation, session) && seq === this.loadSequence) {
          this.loading.set(false);
          this.pending = undefined;
        }
      });
    this.pending = pending;
    return pending;
  }
  async saveBillingContact(body: BillingContactInput) {
    if (this.busy() || !this.details()?.canManageSubscription) return;
    const org = this.org,
      generation = this.generation,
      session = this.session,
      operation = ++this.operation;
    this.busy.set(true);
    this.error.set('');
    let failure = '';
    try {
      await firstValueFrom(
        this.api
          .updateBillingContact({ organizationId: org, body })
          .pipe(timeout(20000), takeUntil(this.cancelMutation)),
      );
    } catch (error) {
      if (this.current(org, generation, session)) failure = apiMessage(error);
    } finally {
      if (this.current(org, generation, session)) {
        await this.load(org, true);
        if (failure && this.current(org, generation, session)) this.error.set(failure);
      }
      if (operation === this.operation) this.busy.set(false);
    }
  }
  private storageKey(org: string) {
    const scope = this.auth.commandScope();
    return scope ? `groupia.subscription-command:${scope}:${org}` : null;
  }
  private getCommand(org: string): PaymentCommand | undefined {
    const cached = this.commands.get(org);
    if (cached) return cached;
    try {
      const key = this.storageKey(org);
      const value = key ? JSON.parse(sessionStorage.getItem(key) ?? 'null') : null;
      const uuid = /^[0-9a-f-]{36}$/i;
      if (
        value &&
        uuid.test(value.creationKey) &&
        uuid.test(value.paymentKey) &&
        (!value.provider || ['SASPAY', 'BICTORYS'].includes(value.provider))
      ) {
        this.commands.set(org, value);
        return value;
      }
    } catch {
      /* Memory fallback when storage is unavailable. */
    }
    return undefined;
  }
  private remember(org: string, command: PaymentCommand) {
    this.commands.set(org, command);
    try {
      const key = this.storageKey(org);
      if (key) sessionStorage.setItem(key, JSON.stringify(command));
    } catch {
      /* The server also exposes authorized attempt recovery. */
    }
  }
  async prepareSaspay() {
    const details = this.details();
    if (
      this.busy() ||
      !details?.canManageSubscription ||
      !this.hasProvider('SASPAY') ||
      !details.planConfigured ||
      this.futurePaid() ||
      !this.renewalWindowOpen()
    )
      return;
    const existing = this.currentInvoice();
    if (existing && !existing.canPay) return;
    const org = this.org,
      generation = this.generation,
      session = this.session,
      operation = ++this.operation;
    let command = this.getCommand(org);
    if (command?.started && !command.knownAttempt) {
      if (command.quote) this.quotes.set([command.quote]);
      this.error.set('Vérifiez ou reprenez le paiement déjà demandé avant de changer de moyen.');
      return;
    }
    if (!command || (command.invoiceId && command.invoiceId !== existing?.id))
      command = { creationKey: crypto.randomUUID(), paymentKey: crypto.randomUUID() };
    if (existing?.id) command.invoiceId = existing.id;
    this.remember(org, command);
    this.busy.set(true);
    this.error.set('');
    this.quotes.set([]);
    try {
      if (!command.invoiceId) {
        const invoice = await firstValueFrom(
          this.api
            .createSubscriptionInvoice({
              organizationId: org,
              'Idempotency-Key': command.creationKey,
            })
            .pipe(timeout(20000), takeUntil(this.cancelMutation)),
        );
        command.invoiceId = invoice.id;
        if (!this.current(org, generation, session)) return;
        this.remember(org, command);
        this.invoices.update((values) => [invoice, ...values.filter((i) => i.id !== invoice.id)]);
      }
      if (!this.current(org, generation, session) || !command.invoiceId) return;
      const quotes = await firstValueFrom(
        this.api
          .quoteSubscriptionInvoicePayment({
            organizationId: org,
            invoiceId: command.invoiceId,
          })
          .pipe(timeout(20000), takeUntil(this.cancelMutation)),
      );
      if (!this.current(org, generation, session)) return;
      this.quotes.set(quotes);
      if (!quotes.length)
        this.error.set(
          'Aucun moyen SasPay disponible. Vérifiez le prénom, le nom et le courriel du payeur.',
        );
    } catch (error) {
      if (this.current(org, generation, session)) this.error.set(apiMessage(error));
    } finally {
      if (operation === this.operation) this.busy.set(false);
    }
  }
  async pay(quote?: PaymentFeeQuoteView, hosted = false) {
    const provider: 'BICTORYS' | 'SASPAY' = hosted ? 'SASPAY' : 'BICTORYS';
    if (quote) return; // Legacy provider-specific quotes must not initiate hosted checkout.
    const details = this.details();
    if (
      this.busy() ||
      !details?.canManageSubscription ||
      !this.hasProvider(provider) ||
      !details.planConfigured ||
      !details.billingContact?.phoneE164 ||
      this.futurePaid() ||
      !this.renewalWindowOpen()
    )
      return;
    const org = this.org,
      generation = this.generation,
      session = this.session,
      operation = ++this.operation;
    let command = this.getCommand(org);
    const existing = this.currentInvoice();
    if (
      command?.started &&
      !command.knownAttempt &&
      command.provider !== provider
    ) {
      this.error.set('Reprenez le paiement déjà demandé avant de changer de moyen.');
      return;
    }
    // A new user action may renew an expired invoice, never a timeout or an uncertain attempt.
    if (
      !command ||
      (command.invoiceId &&
        this.invoices().some(
          (i) =>
            i.id === command!.invoiceId &&
            (['EXPIRED', 'PAID'].includes(i.status ?? '') ||
              (i.status === 'OPEN' &&
                Date.parse(i.periodEnd ?? '') <= Date.now() &&
                i.paymentStatus !== 'SETTLED')),
        ))
    ) {
      command = { creationKey: crypto.randomUUID(), paymentKey: crypto.randomUUID() };
      this.commands.set(org, command);
    }
    if (existing?.id) command.invoiceId = existing.id;
    if (
      existing &&
      !existing.canPay &&
      !(
        command.started &&
        !command.knownAttempt &&
        !existing.reconciliationRequired &&
        existing.paymentStatus === 'PENDING'
      )
    )
      return;
    // A known attempt became terminal and the server explicitly permits another one.
    if (existing?.canPay && command.knownAttempt) {
      command.paymentKey = crypto.randomUUID();
      command.knownAttempt = false;
      command.started = false;
    }
    command.provider = provider;
    command.quote = undefined;
    this.remember(org, command);
    this.busy.set(true);
    this.error.set('');
    let failure = '';
    try {
      if (!command.invoiceId) {
        const invoice = await firstValueFrom(
          this.api
            .createSubscriptionInvoice({
              organizationId: org,
              'Idempotency-Key': command.creationKey,
            })
            .pipe(timeout(20000), takeUntil(this.cancelMutation)),
        );
        // Retain the command's identity even when its screen was left; never publish a stale response.
        command.invoiceId = invoice.id;
      }
      if (!this.current(org, generation, session) || !command.invoiceId) return;
      command.started = true;
      this.remember(org, command);
      this.acceptedQuote.set(null);
      const payment = await firstValueFrom(
        this.api
          .paySubscriptionInvoice({
            organizationId: org,
            invoiceId: command.invoiceId,
            'Idempotency-Key': command.paymentKey,
            body: { provider },
          })
          .pipe(timeout(40000), takeUntil(this.cancelMutation)),
      );
      if (!this.current(org, generation, session)) return;
      command.knownAttempt = true;
      this.remember(org, command);
      const checkout = payment.reconciliationRequired
        ? null
        : this.safeUrl(payment.checkoutUrl, provider);
      this.checkoutUrl.set(checkout);
      if (hosted && checkout) {
        try {
          sessionStorage.setItem(
            'groupia.saspay.return',
            window.location.pathname + window.location.search + window.location.hash,
          );
          window.location.assign(checkout);
        } catch {
          // Secure URL is still available on the invoice screen.
        }
      }
      if (
        ['FAILED', 'CANCELLED', 'EXPIRED'].includes(payment.status ?? '') &&
        !payment.reconciliationRequired
      )
        command.paymentKey = crypto.randomUUID();
    } catch (error) {
      if (this.current(org, generation, session)) {
        failure = apiMessage(error);
        if (rejectedFeeQuote(error)) {
          command.started = false;
          command.knownAttempt = false;
          command.quote = undefined;
          command.provider = undefined;
          command.paymentKey = crypto.randomUUID();
          this.remember(org, command);
          this.quotes.set([]);
          this.acceptedQuote.set(null);
        }
      }
    } finally {
      if (this.current(org, generation, session)) {
        await this.load(org, true);
        if (failure && this.current(org, generation, session))
          this.error.set(failure + ' Vérifiez le paiement avant de réessayer.');
      }
      if (operation === this.operation) this.busy.set(false);
    }
  }
  refresh() {
    return this.org ? this.load(this.org, true) : Promise.resolve();
  }
  private current(org: string, generation: number, session: number) {
    return org === this.org && generation === this.generation && session === this.auth.generation();
  }
  private safeUrl(value?: string, provider = 'BICTORYS') {
    if (provider === 'SASPAY') return safeSaspayCheckout(value);
    try {
      const url = new URL(value ?? '');
      return url.protocol === 'https:' &&
        ['pay.bictorys.com', 'pay.test.bictorys.com'].includes(url.hostname) &&
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
}
