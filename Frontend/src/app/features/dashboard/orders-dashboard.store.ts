import { computed, DestroyRef, Injectable, inject, signal } from '@angular/core';
import { Subscription, forkJoin } from 'rxjs';
import { OrderSummary } from '../../api/generated/models';
import { OrdersApi } from '../orders/orders-api';
import { apiMessage } from '../../core/http/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
@Injectable()
export class OrdersDashboardStore {
  private readonly api = inject(OrdersApi);
  private readonly auth = inject(AuthService);
  private readonly context = inject(BoutiqueContextService);
  private readonly owner = signal('');
  private key() {
    return `${this.auth.generation()}:${this.context.selectedId()}`;
  }
  private readonly visible = computed(() => this.auth.signedIn() && this.owner() === this.key());
  private subscription?: Subscription;
  private readonly rawStats = signal<(number | null)[]>([null, null, null, null]);
  private readonly rawRecent = signal<OrderSummary[]>([]);
  readonly stats = computed(() => (this.visible() ? this.rawStats() : [null, null, null, null]));
  readonly recent = computed(() => (this.visible() ? this.rawRecent() : []));
  readonly loading = signal(false);
  readonly error = signal('');
  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscription?.unsubscribe());
  }
  reset() {
    this.subscription?.unsubscribe();
    this.owner.set('');
    this.rawStats.set([null, null, null, null]);
    this.rawRecent.set([]);
    this.error.set('');
    this.loading.set(false);
  }
  load(org: string) {
    this.reset();
    const key = `${this.auth.generation()}:${org}`;
    this.owner.set(key);
    this.loading.set(true);
    this.subscription = forkJoin([
      this.api.list(org, { size: 5 }),
      this.api.list(org, { size: 1, status: 'CONFIRMED' }),
      this.api.list(org, { size: 1, status: 'PENDING_PAYMENT' }),
      this.api.list(org, { size: 1, status: 'EXPIRED' }),
    ]).subscribe({
      next: (pages) => {
        if (!this.auth.signedIn() || key !== this.key()) return;
        this.rawStats.set(
          pages.map((p) =>
            Number.isSafeInteger(p.totalElements) && p.totalElements! >= 0
              ? p.totalElements!
              : null,
          ),
        );
        this.rawRecent.set(pages[0].items ?? []);
        this.loading.set(false);
      },
      error: (e) => {
        if (!this.auth.signedIn() || key !== this.key()) return;
        this.error.set(apiMessage(e));
        this.loading.set(false);
      },
    });
  }
}
