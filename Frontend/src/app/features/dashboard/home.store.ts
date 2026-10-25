import { computed, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { Observable, timeout } from 'rxjs';
import { CatalogControllerService, GroupingControllerService } from '../../api/generated/services';
import { CampaignSummary } from '../../api/generated/models';
import { AuthService } from '../../core/auth/auth.service';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { OrdersApi } from '../orders/orders-api';

/** Component lifetime only. A result is visible only while its entire context key matches. */
function resource<T>(key: () => string, request: () => Observable<T>) {
  const revision = signal(0);
  const identity = computed(() => (key() ? `${key()}:${revision()}` : ''));
  const result = signal<{ key: string; value: T | null; failed: boolean }>({
    key: '',
    value: null,
    failed: false,
  });
  effect((cleanup) => {
    const current = identity();
    if (!current) return;
    const subscription = untracked(() =>
      request()
        .pipe(timeout(15000))
        .subscribe({
          next: (value) => {
            if (identity() === current) result.set({ key: current, value, failed: false });
          },
          error: () => {
            if (identity() === current) result.set({ key: current, value: null, failed: true });
          },
        }),
    );
    cleanup(() => subscription.unsubscribe());
  });
  return {
    value: computed(() => (identity() && result().key === identity() ? result().value : null)),
    loading: computed(() => !!identity() && result().key !== identity()),
    error: computed(() => !!identity() && result().key === identity() && result().failed),
    retry: () => revision.update((n) => n + 1),
  };
}
export function priorityLabel(group: CampaignSummary): string | null {
  if (!group.id) return null;
  if (group.status === 'DRAFT') return 'Reprendre le brouillon';
  if (group.status === 'OPEN' && group.publicIdentifier) return 'Partager le groupage';
  // The summary does not expose distribution permissions. Keep these in the detail page.
  return null;
}
const activeStatuses = new Set([
  'DRAFT',
  'OPEN',
  'CLOSED',
  'SUPPLIER_ORDERED',
  'IN_TRANSIT',
  'ARRIVED',
  'DISTRIBUTING',
]);
@Injectable()
export class HomeStore {
  private readonly context = inject(BoutiqueContextService);
  private readonly auth = inject(AuthService);
  private readonly grouping = inject(GroupingControllerService);
  private readonly catalog = inject(CatalogControllerService);
  private readonly ordersApi = inject(OrdersApi);
  private readonly key = computed(() =>
    this.auth.signedIn() && this.context.selectedId()
      ? `${this.auth.generation()}:${this.context.selectedId()}`
      : '',
  );
  readonly groups = resource(this.key, () =>
    this.grouping.list2({ organizationId: this.context.selectedId()!, page: 0, size: 20 }),
  );
  readonly orders = resource(this.key, () =>
    this.ordersApi.list(this.context.selectedId()!, { size: 3 }),
  );
  readonly products = resource(
    () => (this.key() && this.groups.value()?.totalElements === 0 ? this.key() : ''),
    () => this.catalog.list1({ organizationId: this.context.selectedId()!, page: 0, size: 1 }),
  );
  readonly active = computed(() =>
    (this.groups.value()?.items ?? []).filter((g) => g.id && activeStatuses.has(g.status ?? '')),
  );
  readonly priorities = computed(() =>
    this.active()
      .filter((g) => priorityLabel(g))
      .sort((a, b) => Number(b.status === 'DRAFT') - Number(a.status === 'DRAFT'))
      .slice(0, 2),
  );
  readonly otherGroups = computed(() =>
    this.active()
      .filter((g) => !this.priorities().some((p) => p.id === g.id))
      .slice(0, 3),
  );
  readonly recent = computed(() => (this.orders.value()?.items ?? []).slice(0, 3));
  readonly noGroups = computed(() => this.groups.value()?.totalElements === 0);
}
