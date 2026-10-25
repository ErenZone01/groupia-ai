import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Button } from '../../shared/ui/button';
import { Icon } from '../../shared/ui/icon';
import { Badge } from '../../shared/ui/badge';
import { Skeleton } from '../../shared/feedback/skeleton';
import { RecentOrders } from './recent-orders';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { BusinessAnalyticsStore } from './business-analytics.store';
import { francs, monthLabel, periodLabel, quantity } from './business-analytics.model';
import { HomeStore, priorityLabel } from './home.store';
import { statusLabel } from '../../shared/utils/display';
@Component({
  selector: 'g-dashboard-page',
  providers: [HomeStore, BusinessAnalyticsStore],
  imports: [RouterLink, Button, Icon, Badge, Skeleton, RecentOrders],
  templateUrl: './dashboard.page.html',
  styleUrl: './dashboard-home.scss',
})
export class DashboardPage {
  readonly store = inject(HomeStore);
  readonly analytics = inject(BusinessAnalyticsStore);
  readonly context = inject(BoutiqueContextService);
  readonly priorityLabel = priorityLabel;
  readonly statusLabel = statusLabel;
  readonly francs = francs;
  readonly quantity = quantity;
  readonly periodLabel = periodLabel;
  /** Tiny visual hint only. Full sales chart and accessible values live in Statistiques. */
  readonly miniChart = computed(() => {
    const data = this.analytics.data();
    const months = data?.months ?? [];
    if (!months.length) return [];
    const maximum = Math.max(1, ...months.map((month) => month.goodsSales ?? 0));
    return months.map((month) => ({
      month: month.month ?? '',
      label: monthLabel(month.month, true),
      height: Math.max(3, Math.round(((month.goodsSales ?? 0) / maximum) * 100)),
    }));
  });
  constructor() {
    this.analytics.period.set(3);
  }
  closing(value?: string) {
    if (!value || !Number.isFinite(Date.parse(value))) return '';
    return new Intl.DateTimeFormat('fr-FR', {
      dateStyle: 'medium',
      timeZone: 'Africa/Dakar',
    }).format(new Date(value));
  }
}
