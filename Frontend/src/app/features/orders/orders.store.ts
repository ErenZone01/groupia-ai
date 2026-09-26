import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { OrderPage } from '../../api/generated/models';
import { apiMessage } from '../../core/http/api-error';
import { OrdersApi } from './orders-api';
@Injectable()
export class OrdersStore {
  private readonly api = inject(OrdersApi);
  readonly data = signal<OrderPage | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly search = signal('');
  readonly items = computed(() => {
    const q = this.search().trim().toLocaleLowerCase('fr');
    return (this.data()?.items ?? []).filter(
      (item) => !q || item.orderNumber?.toLocaleLowerCase('fr').includes(q),
    );
  });
  private subscription?: Subscription;
  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscription?.unsubscribe());
  }
  reset() {
    this.subscription?.unsubscribe();
    this.data.set(null);
    this.loading.set(false);
    this.error.set('');
  }
  load(
    organizationId: string,
    options: { campaignId?: string; status?: string; page?: number } = {},
  ) {
    this.reset();
    this.loading.set(true);
    this.subscription = this.api.list(organizationId, options).subscribe({
      next: (data) => {
        this.data.set(data);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(apiMessage(e));
        this.loading.set(false);
      },
    });
  }
}
