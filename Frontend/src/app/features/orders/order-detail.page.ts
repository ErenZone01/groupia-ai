import { Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { OrganizerOrderView } from '../../api/generated/models';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { apiMessage } from '../../core/http/api-error';
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
import { money, variantLabel } from '../../shared/utils/display';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { Badge } from '../../shared/ui/badge';
import { Skeleton } from '../../shared/feedback/skeleton';
@Component({
  selector: 'g-order-detail',
  imports: [RouterLink, Button, Card, Badge, Skeleton],
  templateUrl: './order-detail.page.html',
  styleUrl: './order-detail.page.scss',
})
export class OrderDetailPage {
  private readonly api = inject(OrdersApi);
  private readonly context = inject(BoutiqueContextService);
  private readonly route = inject(ActivatedRoute);
  private subscription?: Subscription;
  private readonly params = toSignal(this.route.paramMap, {
    initialValue: this.route.snapshot.paramMap,
  });
  private readonly query = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });
  readonly data = signal<OrganizerOrderView | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
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
  readonly variant = variantLabel;
  constructor() {
    effect(() => {
      const org = this.context.selectedId(),
        id = this.params().get('orderId');
      untracked(() => {
        this.subscription?.unsubscribe();
        this.data.set(null);
        if (org && id) this.load(org, id);
        else this.loading.set(false);
      });
    });
    inject(DestroyRef).onDestroy(() => this.subscription?.unsubscribe());
  }
  reload() {
    const org = this.context.selectedId(),
      id = this.params().get('orderId');
    if (org && id) this.load(org, id);
  }
  private load(org: string, id: string) {
    this.subscription?.unsubscribe();
    this.loading.set(true);
    this.error.set('');
    this.data.set(null);
    this.subscription = this.api.detail(org, id).subscribe({
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
