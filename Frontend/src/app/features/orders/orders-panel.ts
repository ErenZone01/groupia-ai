import { Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { OrdersStore } from './orders.store';
import { OrderList } from './order-list';
import { ProductMediaApi } from '../catalog/product-media.api';
import { ProductMediaSummary, OrderSummary } from '../../api/generated/models';
import { orderFilters, orderFilter, orderPage } from './order-labels';
import { Button } from '../../shared/ui/button';
import { SearchInput } from '../../shared/forms/search-input';
import { EmptyState } from '../../shared/feedback/empty-state';
import { Skeleton } from '../../shared/feedback/skeleton';
import { Select } from '../../shared/forms/select';
@Component({
  selector: 'g-orders-panel',
  providers: [OrdersStore],
  imports: [OrderList, Button, SearchInput, EmptyState, Skeleton, Select],
  templateUrl: './orders-panel.html',
  styles: `
    :host { display:block; min-width:0; }
    .filters { display:flex; flex-wrap:nowrap; gap:var(--s2); overflow-x:auto;
      padding:var(--s2) 0; margin:var(--s2) 0; scrollbar-width:thin; }
    .filters button { flex-shrink:0; border-radius:var(--pill); font-size:var(--text-xs); padding:var(--s2) var(--s3); }
    .toolbar {
      display: flex;
      gap: var(--s3);
      align-items: center;
      flex-wrap: wrap;
      margin: var(--s4) 0;
    }
    .toolbar label { display:flex; align-items:center; gap:var(--s2); color:var(--muted); font-size:var(--text-xs); }
    .toolbar select { min-height:44px; padding:var(--s2); max-width:180px; }
    .filter-help { font-size:var(--text-xs); color:var(--muted); margin-bottom:var(--s3); }
    .filter-help summary { cursor:pointer; min-height:36px; display:flex; align-items:center; color:var(--primary); }
    .filter-help summary:after { content:'⌄'; margin-left:var(--s2); }
    .filter-help[open] summary:after { transform:rotate(180deg); }
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
  private readonly mediaApi = inject(ProductMediaApi);
  readonly media = signal<Record<string, ProductMediaSummary>>({});
  readonly sorting = signal<'recent' | 'oldest' | 'amount'>('recent');
  readonly visibleOrders = computed(() => {
    const rows = [...this.store.items()];
    switch (this.sorting()) {
      case 'oldest': return rows.sort((a, b) => (a.placedAt ?? '').localeCompare(b.placedAt ?? ''));
      case 'amount': return rows.sort((a, b) => (b.initialAmountDue ?? 0) - (a.initialAmountDue ?? 0));
      default: return rows;
    }
  });
  private mediaSequence = 0;
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
    inject(DestroyRef).onDestroy(() => { this.mediaSequence++; });
    effect(() => {
      const organizationId = this.context.selectedId();
      const items = this.store.data()?.items ?? [];
      untracked(() => { void this.loadMedia(organizationId, items); });
    });
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
  private async loadMedia(organizationId: string | null, orders: readonly OrderSummary[]) {
    const current = ++this.mediaSequence;
    this.media.set({});
    if (!organizationId) return;
    const ids = [...new Set(orders.flatMap((order) => order.previewProductIds ?? []))];
    if (!ids.length) return;
    try {
      const images = await this.mediaApi.batch(organizationId, ids);
      if (this.mediaSequence === current && this.context.selectedId() === organizationId) {
        this.media.set(Object.fromEntries(images.filter((image) => ids.includes(image.productId ?? ''))
          .map((image) => [image.productId!, image])));
      }
    } catch {
      // Photos are optional, including during a temporary media outage.
    }
  }
  changeSorting(value: string) {
    if (value === 'recent' || value === 'oldest' || value === 'amount') this.sorting.set(value);
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
