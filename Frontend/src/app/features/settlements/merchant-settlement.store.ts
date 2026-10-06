import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';
import { MerchantSettlementControllerService } from '../../api/generated/services';
import type {
  MerchantBalance,
  MerchantPayoutView,
  PayoutDestinationView,
} from '../../api/generated/models';
import { apiMessage } from '../../core/http/api-error';
@Injectable()
export class MerchantSettlementStore {
  private readonly api = inject(MerchantSettlementControllerService);
  readonly balance = signal<MerchantBalance | null>(null);
  readonly destination = signal<PayoutDestinationView | null>(null);
  readonly history = signal<MerchantPayoutView[]>([]);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  private org = '';
  private generation = 0;
  private readonly keys = new Map<string, string>();
  async load(org = this.org) {
    if (!org) return;
    const generation = ++this.generation;
    if (org !== this.org) {
      this.balance.set(null);
      this.destination.set(null);
      this.history.set([]);
      this.error.set('');
    }
    this.org = org;
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
      if (generation !== this.generation) return;
      this.balance.set(balance);
      this.destination.set(destination ?? null);
      this.history.set(history);
    } catch (e) {
      if (generation === this.generation) {
        this.error.set(apiMessage(e));
        this.balance.set(null);
        this.destination.set(null);
        this.history.set([]);
      }
    } finally {
      if (generation === this.generation) this.loading.set(false);
    }
  }
  async saveDestination(method: string, phone: string, holder: string, confirmed: boolean) {
    if (this.busy() || !this.balance()?.canManageDestination || !confirmed) return;
    const org = this.org;
    this.busy.set(true);
    this.error.set('');
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
      if (org === this.org) this.error.set(apiMessage(e));
    } finally {
      if (org === this.org) await this.load(org);
      this.busy.set(false);
    }
  }
  async request(campaignId: string) {
    const b = this.balance(),
      d = this.destination();
    const row = b?.campaigns?.find((c) => c.campaignId === campaignId);
    if (
      this.busy() ||
      this.loading() ||
      !b?.canManageSettlements ||
      !b.providerAvailable ||
      !d?.id ||
      !(row?.availableForPayout! > 0)
    )
      return;
    const org = this.org,
      scope = org + ':' + campaignId;
    const key = this.keys.get(scope) ?? crypto.randomUUID();
    this.keys.set(scope, key);
    this.busy.set(true);
    this.error.set('');
    try {
      await firstValueFrom(
        this.api
          .requestMerchantPayout({
            organizationId: org,
            campaignId,
            'Idempotency-Key': key,
            body: { destinationId: d.id, confirmed: true },
          })
          .pipe(timeout(40000)),
      );
      this.keys.delete(scope);
    } catch (e) {
      if (org === this.org) this.error.set(apiMessage(e));
    } finally {
      if (org === this.org) await this.load(org);
      this.busy.set(false);
    }
  }
  async reconcile(payoutId: string) {
    if (this.busy() || !this.balance()?.canManageSettlements || !this.balance()?.providerAvailable)
      return;
    const org = this.org;
    this.busy.set(true);
    this.error.set('');
    try {
      await firstValueFrom(
        this.api.reconcileMerchantPayout({ organizationId: org, payoutId }).pipe(timeout(40000)),
      );
    } catch (e) {
      if (org === this.org) this.error.set(apiMessage(e));
    } finally {
      if (org === this.org) await this.load(org);
      this.busy.set(false);
    }
  }
}
