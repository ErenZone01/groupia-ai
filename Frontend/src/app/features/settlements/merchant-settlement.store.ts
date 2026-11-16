import { effect, inject, Injectable, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom, timeout } from 'rxjs';
import { MerchantSettlementControllerService } from '../../api/generated/services';
import type {
  MerchantBalance,
  MerchantPayoutView,
  PayoutDestinationView,
} from '../../api/generated/models';
import { AuthService } from '../../core/auth/auth.service';
import { apiMessage } from '../../core/http/api-error';

export interface DirectSettlement {
  directOrderId: string;
  orderNumber: string;
  status: string;
  currency: string;
  provider: string;
  accountReference: string;
  grossMerchantCollections: number;
  platformFees: number;
  eligibleBalance: number;
  merchantPayouts: number;
  reservedForPayout: number;
  availableForPayout: number;
  deficit: number;
}
export interface PayoutFeeQuote {
  quoteId: string;
  destinationId: string;
  method: string;
  maskedPhone: string;
  grossAmount: number;
  providerFee: number;
  netAmount: number;
  currency: string;
  feeChargeMode: 'DEDUCTED';
  expiresAt: string;
}
type UnifiedBalance = MerchantBalance & {
  directOrders?: DirectSettlement[];
  payoutFeePolicy?: 'DEDUCTED' | 'EXTERNAL';
};
type UnifiedPayout = MerchantPayoutView & { directOrderId?: string | null };
@Injectable()
export class MerchantSettlementStore {
  private readonly api = inject(MerchantSettlementControllerService);
  private readonly http = inject(HttpClient);
  readonly balance = signal<MerchantBalance | null>(null);
  readonly directOrders = signal<DirectSettlement[]>([]);
  readonly destination = signal<PayoutDestinationView | null>(null);
  readonly quote = signal<PayoutFeeQuote | null>(null);
  private quotedTarget: string | null = null;
  readonly history = signal<MerchantPayoutView[]>([]);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  private readonly auth = inject(AuthService);
  private session = -1;
  private contextGeneration = 0;
  private operation = 0;
  private readonly uncertain = new Map<string, Set<string>>();
  private org = '';
  private generation = 0;
  private readonly keys = new Map<string, string>();
  constructor() {
    effect(() => {
      const session = this.auth.generation();
      if (this.session >= 0 && this.session !== session) this.clear();
    });
  }
  clear() {
    this.contextGeneration++;
    this.generation++;
    this.operation++;
    this.session = this.auth.generation();
    this.org = '';
    this.balance.set(null);
    this.directOrders.set([]);
    this.destination.set(null);
    this.quote.set(null);
    this.quotedTarget = null;
    this.history.set([]);
    this.loading.set(false);
    this.busy.set(false);
    this.error.set('');
  }
  private current(org: string, context: number, session: number) {
    return (
      this.org === org && this.contextGeneration === context && this.auth.generation() === session
    );
  }
  async load(org = this.org) {
    if (!org) {
      this.clear();
      return;
    }
    if (this.session !== this.auth.generation()) this.clear();
    const generation = ++this.generation;
    if (org !== this.org) {
      this.contextGeneration++;
      this.operation++;
      this.busy.set(false);
      this.balance.set(null);
      this.directOrders.set([]);
      this.destination.set(null);
      this.quote.set(null);
      this.quotedTarget = null;
      this.history.set([]);
      this.error.set('');
    }
    this.org = org;
    const context = this.contextGeneration,
      session = this.session;
    this.loading.set(true);
    try {
      const [balance, destination, history] = await Promise.all([
        firstValueFrom(this.api.merchantBalance({ organizationId: org }).pipe(timeout(15000))),
        firstValueFrom(
          this.api.merchantPayoutDestination({ organizationId: org }).pipe(timeout(15000)),
        ),
        firstValueFrom(
          this.api.merchantPayoutHistory({ organizationId: org }).pipe(timeout(15000)),
        ),
      ]);
      if (generation !== this.generation || !this.current(org, context, session)) return;
      this.balance.set(balance);
      this.directOrders.set((balance as UnifiedBalance).directOrders ?? []);
      this.destination.set(destination ?? null);
      this.history.set(history);
      for (const [scope, previous] of this.uncertain) {
        const resolved = history.find((p) => {
          const directId = (p as UnifiedPayout).directOrderId;
          return (
            (scope === org + ':' + p.campaignId || scope === org + ':direct:' + directId) &&
            p.id &&
            !previous.has(p.id)
          );
        });
        if (
          resolved &&
          ['FAILED', 'CANCELLED', 'SUCCEEDED', 'NON_EXECUTE'].includes(resolved.status ?? '')
        ) {
          this.uncertain.delete(scope);
          this.keys.delete(scope);
        }
      }
    } catch (e) {
      if (generation === this.generation && this.current(org, context, session)) {
        this.error.set(apiMessage(e));
        this.balance.set(null);
        this.directOrders.set([]);
        this.destination.set(null);
        this.quote.set(null);
        this.quotedTarget = null;
        this.history.set([]);
      }
    } finally {
      if (generation === this.generation && this.current(org, context, session))
        this.loading.set(false);
    }
  }
  async saveDestination(method: string, phone: string, holder: string, confirmed: boolean) {
    if (this.session !== this.auth.generation()) {
      this.clear();
      return;
    }
    if (this.busy() || !this.balance()?.canManageDestination || !confirmed) return;
    const org = this.org,
      context = this.contextGeneration,
      session = this.session,
      operation = ++this.operation;
    this.busy.set(true);
    this.error.set('');
    let failure = '';
    try {
      await firstValueFrom(
        this.api
          .changeMerchantPayoutDestination({
            organizationId: org,
            body: {
              previousDestinationId: this.destination()?.id,
              method,
              phoneE164: phone,
              holderName: holder,
              confirmed,
            },
          })
          .pipe(timeout(20000)),
      );
    } catch (e) {
      if (this.current(org, context, session)) failure = apiMessage(e);
    } finally {
      if (this.current(org, context, session)) {
        await this.load(org);
        if (failure && this.current(org, context, session)) this.error.set(failure);
      }
      if (operation === this.operation) this.busy.set(false);
    }
  }
  get deductedFees() {
    return (this.balance() as UnifiedBalance | null)?.payoutFeePolicy === 'DEDUCTED';
  }

  discardQuote() {
    this.quote.set(null);
    this.quotedTarget = null;
  }

  async prepareQuote(targetId: string, direct: boolean, amount?: number): Promise<boolean> {
    this.discardQuote();
    if (amount !== undefined && (!Number.isSafeInteger(amount) || amount <= 0)) {
      this.error.set('Indiquez un montant de retrait entier et positif.');
      return false;
    }
    if (!this.deductedFees) return true;
    const org = this.org;
    const destinationId = this.destination()?.id;
    if (!destinationId || !org || this.busy()) return false;
    const context = this.contextGeneration,
      session = this.session;
    this.busy.set(true);
    this.error.set('');
    try {
      const path = direct
        ? `/api/organizations/${encodeURIComponent(org)}/direct-orders/${encodeURIComponent(targetId)}/merchant-payout-quote`
        : `/api/organizations/${encodeURIComponent(org)}/grouping-campaigns/${encodeURIComponent(targetId)}/merchant-payout-quote`;
      const quote = await firstValueFrom(
        this.http
          .get<PayoutFeeQuote>(path, {
            params:
              amount === undefined ? { destinationId } : { destinationId, amount: String(amount) },
          })
          .pipe(timeout(20000)),
      );
      if (!this.current(org, context, session)) return false;
      if (
        quote.feeChargeMode !== 'DEDUCTED' ||
        quote.destinationId !== destinationId ||
        (amount !== undefined && quote.grossAmount !== amount) ||
        !(quote.netAmount > 0) ||
        Math.round(quote.grossAmount * 100) !==
          Math.round(quote.netAmount * 100) + Math.round(quote.providerFee * 100) ||
        Date.parse(quote.expiresAt) <= Date.now()
      ) {
        this.error.set('Devis de retrait invalide. Veuillez réessayer.');
        return false;
      }
      this.quote.set(quote);
      this.quotedTarget = (direct ? 'direct:' : 'campaign:') + targetId;
      return true;
    } catch (e) {
      if (this.current(org, context, session)) this.error.set(apiMessage(e));
      return false;
    } finally {
      if (this.current(org, context, session)) this.busy.set(false);
    }
  }

  async request(campaignId: string) {
    return this.requestTarget(campaignId, false);
  }
  async requestDirect(directOrderId: string) {
    return this.requestTarget(directOrderId, true);
  }
  private async requestTarget(targetId: string, direct: boolean) {
    if (this.session !== this.auth.generation()) {
      this.clear();
      return;
    }
    const b = this.balance(),
      d = this.destination();
    const row = direct
      ? this.directOrders().find((d) => d.directOrderId === targetId)
      : b?.campaigns?.find((d) => d.campaignId === targetId);
    if (
      this.busy() ||
      this.loading() ||
      !b?.canManageSettlements ||
      !b.providerAvailable ||
      !d?.id ||
      !((row?.availableForPayout ?? 0) > 0)
    )
      return;
    const acceptedQuote = this.deductedFees ? this.quote() : null;
    if (
      this.deductedFees &&
      (!acceptedQuote ||
        this.quotedTarget !== (direct ? 'direct:' : 'campaign:') + targetId ||
        acceptedQuote.destinationId !== d.id ||
        !(acceptedQuote.grossAmount > 0) ||
        acceptedQuote.grossAmount > (row?.availableForPayout ?? 0) ||
        Date.parse(acceptedQuote.expiresAt) <= Date.now())
    ) {
      this.error.set('Devis de retrait expiré ou modifié. Rechargez les frais avant de confirmer.');
      return;
    }
    const org = this.org,
      scope = org + ':' + (direct ? 'direct:' : '') + targetId,
      context = this.contextGeneration,
      session = this.session,
      operation = ++this.operation;
    if (this.uncertain.has(scope)) {
      this.error.set('Versement en cours de vérification. Ne relancez pas un nouveau versement.');
      return;
    }
    const previous = new Set(this.history().flatMap((p) => (p.id ? [p.id] : [])));
    const key = this.keys.get(scope) ?? crypto.randomUUID();
    this.keys.set(scope, key);
    this.busy.set(true);
    this.error.set('');
    let failure = '';
    const body = acceptedQuote
      ? { destinationId: d.id, feeQuoteId: acceptedQuote.quoteId, confirmed: true }
      : { destinationId: d.id, confirmed: true };
    try {
      if (direct) {
        await firstValueFrom(
          this.http
            .post<MerchantPayoutView>(
              `/api/organizations/${encodeURIComponent(org)}/direct-orders/${encodeURIComponent(targetId)}/merchant-payouts`,
              body,
              { headers: { 'Idempotency-Key': key } },
            )
            .pipe(timeout(40000)),
        );
      } else {
        await firstValueFrom(
          this.api
            .requestMerchantPayout({
              organizationId: org,
              campaignId: targetId,
              'Idempotency-Key': key,
              body,
            })
            .pipe(timeout(40000)),
        );
      }
      if (this.keys.get(scope) === key) this.keys.delete(scope);
      this.quote.set(null);
      this.quotedTarget = null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status >= 400 && e.status < 500) {
        this.keys.delete(scope);
        this.quote.set(null);
        this.quotedTarget = null;
        if (this.current(org, context, session)) failure = apiMessage(e);
      } else {
        this.uncertain.set(scope, previous);
        if (this.current(org, context, session))
          failure = 'Versement en cours de vérification. Ne relancez pas un nouveau versement.';
      }
    } finally {
      if (this.current(org, context, session)) {
        await this.load(org);
        if (failure && this.current(org, context, session)) this.error.set(failure);
      }
      if (operation === this.operation) this.busy.set(false);
    }
  }
  async reconcile(payoutId: string) {
    if (this.session !== this.auth.generation()) {
      this.clear();
      return;
    }
    if (this.busy() || !this.balance()?.canManageSettlements || !this.balance()?.providerAvailable)
      return;
    const org = this.org,
      context = this.contextGeneration,
      session = this.session,
      operation = ++this.operation;
    this.busy.set(true);
    this.error.set('');
    let failure = '';
    try {
      await firstValueFrom(
        this.api.reconcileMerchantPayout({ organizationId: org, payoutId }).pipe(timeout(40000)),
      );
    } catch (e) {
      if (this.current(org, context, session)) failure = apiMessage(e);
    } finally {
      if (this.current(org, context, session)) {
        await this.load(org);
        if (failure && this.current(org, context, session)) this.error.set(failure);
      }
      if (operation === this.operation) this.busy.set(false);
    }
  }
}
