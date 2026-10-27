import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { OrderSummary } from '../../api/generated/models';
import { Badge } from '../../shared/ui/badge';
import { OrderProductPreview } from '../../shared/ui/order-product-preview';
import { ProductMediaSummary } from '../../api/generated/models';
import { Button } from '../../shared/ui/button';
import { money } from '../../shared/utils/display';
import { orderDate, orderLabel, orderTone } from './order-labels';
@Component({
  selector: 'g-order-list',
  imports: [RouterLink, Badge, Button, OrderProductPreview],
  templateUrl: './order-list.html',
  styleUrl: './order-list.scss',
})
export class OrderList {
  readonly orders = input<OrderSummary[]>([]);
  readonly media = input<Record<string, ProductMediaSummary>>({});
  readonly returnQuery = input<Record<string, string | number>>({});
  readonly rows = computed(() =>
    this.orders().map((o) => ({
      ...o,
      label: orderLabel(o.status),
      tone: orderTone(o.status),
      amount: money(o.initialAmountDue, o.currency),
      date: orderDate(o.placedAt),
    })),
  );
}
