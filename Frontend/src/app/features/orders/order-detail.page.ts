import { Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { CustomerContact, OrganizerOrderView, ProductMediaSummary } from '../../api/generated/models';
import type { CustomerLocation } from '../checkout/checkout.store';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { apiMessage } from '../../core/http/api-error';
import { ProductMediaApi } from '../catalog/product-media.api';
import { OrdersApi } from './orders-api';
import {
  orderLabel,
  orderTone,
  orderDate,
  paymentLabel,
  fulfillmentLabel,
  orderFilter,
  orderPage,
} from './order-labels';
import { money } from '../../shared/utils/display';
import { Button } from '../../shared/ui/button';
import { Badge } from '../../shared/ui/badge';
import { Skeleton } from '../../shared/feedback/skeleton';
import { OrderLineCard } from './order-line-card';

@Component({
  selector: 'g-order-detail',
  imports: [RouterLink, Button, Badge, Skeleton, OrderLineCard],
  templateUrl: './order-detail.page.html',
  styleUrl: './order-detail.page.scss',
})
export class OrderDetailPage {
  private readonly api = inject(OrdersApi);
  private readonly mediaApi = inject(ProductMediaApi);
  private readonly context = inject(BoutiqueContextService);
  private readonly route = inject(ActivatedRoute);
  private subscription?: Subscription;
  private requestSequence = 0;
  private readonly params = toSignal(this.route.paramMap, {
    initialValue: this.route.snapshot.paramMap,
  });
  private readonly query = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });

  readonly data = signal<OrganizerOrderView | null>(null);
  readonly media = signal<Record<string, ProductMediaSummary>>({});
  readonly loading = signal(true);
  readonly error = signal('');
  readonly showAllItems = signal(false);
  readonly items = computed(() => this.data()?.order?.items ?? []);
  readonly visibleItems = computed(() => this.showAllItems() ? this.items() : this.items().slice(0, 4));
  readonly customerName = computed(() => {
    const contact = this.data()?.customerContactSnapshot;
    return [contact?.firstName, contact?.lastName].filter(Boolean).join(' ') || 'Cliente';
  });
  readonly customerLocation = computed(() => {
    const contact = this.data()?.customerContactSnapshot as
      | (CustomerContact & { deliveryAddress?: CustomerLocation })
      | undefined;
    return contact?.deliveryAddress ?? null;
  });
  readonly fromGroupage = computed(() =>
    this.data()?.campaignId && this.query().get('campaignId') === this.data()?.campaignId
      ? this.data()!.campaignId
      : null,
  );
  readonly backLink = computed(() =>
    this.fromGroupage() ? ['/app/groupages', this.fromGroupage()!] : ['/app/orders'],
  );
  readonly backQuery = computed(() => ({
    status: orderFilter(this.query().get('status')) || null,
    page: orderPage(this.query().get('page')) || null,
    q: this.query().get('q'),
    ...(this.fromGroupage() ? { tab: 'orders' } : {}),
  }));
  readonly status = orderLabel;
  readonly tone = orderTone;
  readonly date = orderDate;
  readonly payment = paymentLabel;
  readonly fulfillment = fulfillmentLabel;
  readonly money = money;

  constructor() {
    effect(() => {
      const org = this.context.selectedId();
      const id = this.params().get('orderId');
      untracked(() => {
        this.subscription?.unsubscribe();
        this.requestSequence++;
        this.data.set(null);
        this.media.set({});
        this.showAllItems.set(false);
        if (org && id) this.load(org, id);
        else this.loading.set(false);
      });
    });
    inject(DestroyRef).onDestroy(() => {
      this.subscription?.unsubscribe();
      this.requestSequence++;
    });
  }

  pickupLabel(orderStatus?: string, fulfillmentStatus?: string) {
    if (fulfillmentStatus === 'PICKED_UP' || fulfillmentStatus === 'READY_FOR_PICKUP') {
      return fulfillmentLabel(fulfillmentStatus);
    }
    if (orderStatus === 'PENDING_PAYMENT') return 'Après paiement';
    if (orderStatus === 'CANCELLED' || orderStatus === 'EXPIRED') return 'Non disponible';
    return fulfillmentLabel(fulfillmentStatus);
  }

  imageFor(lineNumber?: number) {
    const id = this.data()?.productIdsByLineNumber?.[String(lineNumber ?? '')];
    return id ? (this.media()[id]?.primaryThumbnailUrl ?? '') : '';
  }

  reload() {
    const org = this.context.selectedId();
    const id = this.params().get('orderId');
    if (org && id) this.load(org, id);
  }

  private load(org: string, id: string) {
    this.subscription?.unsubscribe();
    const sequence = ++this.requestSequence;
    this.loading.set(true);
    this.error.set('');
    this.data.set(null);
    this.media.set({});
    this.showAllItems.set(false);
    this.subscription = this.api.detail(org, id).subscribe({
      next: (data) => {
        if (sequence !== this.requestSequence) return;
        this.data.set(data);
        this.loading.set(false);
        void this.loadImages(org, data, sequence);
      },
      error: (error) => {
        if (sequence !== this.requestSequence) return;
        this.error.set(apiMessage(error));
        this.loading.set(false);
      },
    });
  }

  private async loadImages(org: string, view: OrganizerOrderView, sequence: number) {
    const ids = [...new Set(Object.values(view.productIdsByLineNumber ?? {}))].slice(0, 100);
    if (!ids.length) return;
    try {
      const media = await this.mediaApi.batch(org, ids);
      if (sequence === this.requestSequence && this.context.selectedId() === org) {
        this.media.set(Object.fromEntries(media
          .filter((item) => !!item.productId && ids.includes(item.productId))
          .map((item) => [item.productId!, item])));
      }
    } catch {
      // A media service failure must not block the manager from reading an order.
    }
  }
}
