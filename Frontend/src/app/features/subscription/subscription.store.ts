import { computed, effect, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom, forkJoin, Subject, takeUntil, timeout } from 'rxjs';
import type {
  BillingContactInput,
  SubscriptionDetails,
  SubscriptionPeriodView,
  SubscriptionInvoiceView,
  SubscriptionInvoicePreview,
} from '../../api/generated/models';
import { SubscriptionControllerService } from '../../api/generated/services';
import { AuthService } from '../../core/auth/auth.service';
import { apiMessage } from '../../core/http/api-error';

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
  private org = '';
  private session = -1;
  private generation = 0;
  private loadSequence = 0;
  private operation = 0;
  private cancelLoad = new Subject<void>();
  private cancelMutation = new Subject<void>();
  private pending?: Promise<void>;
  private readonly commands = new Map<
    string,
    { creationKey: string; invoiceId?: string; paymentKey: string; knownAttempt?: boolean }
  >();
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
        if (
          !result.invoices.some(
            (i) => i.status === 'OPEN' && i.attemptInProgress && !i.reconciliationRequired,
          )
        )
          this.checkoutUrl.set(null);
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
  async pay() {
    const details = this.details();
    if (
      this.busy() ||
      !details?.canManageSubscription ||
      !details.billingProviderAvailable ||
      !details.planConfigured ||
      !details.billingContact?.phoneE164 ||
      this.futurePaid()
    )
      return;
    const org = this.org,
      generation = this.generation,
      session = this.session,
      operation = ++this.operation;
    let command = this.commands.get(org);
    const existing = this.currentInvoice();
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
    if (existing && !existing.canPay) return;
    // A known attempt became terminal and the server explicitly permits another one.
    if (existing?.canPay && command.knownAttempt) {
      command.paymentKey = crypto.randomUUID();
      command.knownAttempt = false;
    }
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
      const payment = await firstValueFrom(
        this.api
          .paySubscriptionInvoice({
            organizationId: org,
            invoiceId: command.invoiceId,
            'Idempotency-Key': command.paymentKey,
            body: { provider: 'BICTORYS' },
          })
          .pipe(timeout(40000), takeUntil(this.cancelMutation)),
      );
      if (!this.current(org, generation, session)) return;
      command.knownAttempt = true;
      this.checkoutUrl.set(
        payment.reconciliationRequired ? null : this.safeUrl(payment.checkoutUrl),
      );
      if (
        ['FAILED', 'CANCELLED', 'EXPIRED'].includes(payment.status ?? '') &&
        !payment.reconciliationRequired
      )
        command.paymentKey = crypto.randomUUID();
    } catch (error) {
      if (this.current(org, generation, session)) failure = apiMessage(error);
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
  private safeUrl(value?: string) {
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
