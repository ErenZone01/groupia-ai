import { Component, computed, effect, inject, input, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { OrdersStore } from './orders.store';
import { OrderList } from './order-list';
import { orderFilters, orderFilter, orderPage } from './order-labels';
import { Button } from '../../shared/ui/button';
import { SearchInput } from '../../shared/forms/search-input';
import { EmptyState } from '../../shared/feedback/empty-state';
import { Skeleton } from '../../shared/feedback/skeleton';
@Component({
  selector: 'g-orders-panel',
  providers: [OrdersStore],
  imports: [OrderList, Button, SearchInput, EmptyState, Skeleton],
  templateUrl: './orders-panel.html',
  styles: `
    .filters {
      display: flex;
      flex-wrap: wrap;
      gap: var(--s2);
      margin: var(--s4) 0;
    }
    .toolbar {
      display: flex;
      gap: var(--s3);
      align-items: center;
      flex-wrap: wrap;
      margin: var(--s4) 0;
    }
    .toolbar g-search-input {
      flex: 1;
      min-width: min(100%, 240px);
    }
    .pagination {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--s3);
      flex-wrap: wrap;
      margin: var(--s5) 0;
    }
    .muted {
      margin: var(--s3) 0;
    }
    button {
      min-height: 44px;
    }
    h2 {
      font-size: var(--text-lg);
    }
  `,
})
export class OrdersPanel {
  readonly campaignId = input<string>();
  readonly store = inject(OrdersStore);
  readonly context = inject(BoutiqueContextService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly params = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });
  readonly status = computed(() => orderFilter(this.params().get('status')));
  readonly page = computed(() => orderPage(this.params().get('page')));
  readonly filters = orderFilters;
  readonly empty = computed(() => orderFilters.find((f) => f.value === this.status())!.empty);
  readonly returnQuery = computed(() => ({
    status: this.status(),
    page: this.page(),
    q: this.store.search(),
    ...(this.campaignId() ? { campaignId: this.campaignId()! } : {}),
  }));
  constructor() {
    this.store.search.set(this.route.snapshot.queryParamMap.get('q') ?? '');
    effect(() => {
      const id = this.context.selectedId(),
        campaignId = this.campaignId(),
        status = this.status(),
        page = this.page();
      untracked(() => {
        if (id) this.store.load(id, { campaignId, status: status || undefined, page });
        else this.store.reset();
      });
    });
  }
  reload() {
    const id = this.context.selectedId();
    if (id)
      this.store.load(id, {
        campaignId: this.campaignId(),
        status: this.status() || undefined,
        page: this.page(),
      });
  }
  filter(status: string) {
    this.store.search.set('');
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { status: status || null, page: null, q: null },
      queryParamsHandling: 'merge',
    });
  }
  paginate(page: number) {
    this.store.search.set('');
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { page: page || null, q: null },
      queryParamsHandling: 'merge',
    });
  }
}
