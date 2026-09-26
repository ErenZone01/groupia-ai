import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { Subscription, forkJoin } from 'rxjs';
import { OrderSummary } from '../../api/generated/models';
import { OrdersApi } from '../orders/orders-api';
import { apiMessage } from '../../core/http/api-error';
@Injectable()
export class OrdersDashboardStore {
  private readonly api = inject(OrdersApi);
  private subscription?: Subscription;
  readonly stats = signal<(number | null)[]>([null, null, null, null]);
  readonly recent = signal<OrderSummary[]>([]);
  readonly loading = signal(false);
  readonly error = signal('');
  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscription?.unsubscribe());
  }
  reset() {
    this.subscription?.unsubscribe();
    this.stats.set([null, null, null, null]);
    this.recent.set([]);
    this.error.set('');
    this.loading.set(false);
  }
  load(org: string) {
    this.reset();
    this.loading.set(true);
    this.subscription = forkJoin([
      this.api.list(org, { size: 5 }),
      this.api.list(org, { size: 1, status: 'CONFIRMED' }),
      this.api.list(org, { size: 1, status: 'PENDING_PAYMENT' }),
      this.api.list(org, { size: 1, status: 'EXPIRED' }),
    ]).subscribe({
      next: (pages) => {
        this.stats.set(
          pages.map((p) =>
            Number.isSafeInteger(p.totalElements) && p.totalElements! >= 0
              ? p.totalElements!
              : null,
          ),
        );
        this.recent.set(pages[0].items ?? []);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(apiMessage(e));
        this.loading.set(false);
      },
    });
  }
}
