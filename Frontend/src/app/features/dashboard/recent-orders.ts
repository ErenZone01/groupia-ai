import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { OrderSummary } from '../../api/generated/models';
import { Badge } from '../../shared/ui/badge';
import { Icon } from '../../shared/ui/icon';
import { money } from '../../shared/utils/display';
import { orderDate, orderLabel, orderTone } from '../orders/order-labels';

@Component({
  selector: 'g-recent-orders',
  imports: [RouterLink, Badge, Icon],
  template: `<ul aria-label="Dernières commandes">
    @for (row of rows(); track row.id) {
      <li>
        <span class="order-symbol" aria-hidden="true"><g-icon name="bag" /></span>
        <div class="reference">
          <strong>{{ row.orderNumber || 'Commande' }}</strong>
          <time [attr.datetime]="row.placedAt">{{ row.date }}</time>
        </div>
        <div class="summary">
          <strong>{{ row.amount }}</strong
          ><g-badge [tone]="row.tone">{{ row.label }}</g-badge>
        </div>
        @if (row.id) {
          <a
            [routerLink]="['/app/orders', row.id]"
            [attr.aria-label]="'Voir la commande ' + row.orderNumber"
          >
            <g-icon name="arrow" />
          </a>
        }
      </li>
    }
  </ul>`,
  styleUrl: './recent-orders.scss',
})
export class RecentOrders {
  readonly orders = input<OrderSummary[]>([]);
  readonly rows = computed(() =>
    this.orders().map((order) => ({
      ...order,
      label: orderLabel(order.status),
      tone: orderTone(order.status),
      amount: money(order.initialAmountDue, order.currency),
      date: orderDate(order.placedAt),
    })),
  );
}
