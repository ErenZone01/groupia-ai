import { Component, inject } from '@angular/core';
import { Card } from '../../shared/ui/card';
import { Button } from '../../shared/ui/button';
import { Icon } from '../../shared/ui/icon';
import { Skeleton } from '../../shared/feedback/skeleton';
import { BusinessAnalyticsStore } from './business-analytics.store';
import { SalesChart } from './sales-chart';
import { changeLabel, francs, periodLabel, quantity } from './business-analytics.model';

@Component({
  selector: 'g-business-overview',
  providers: [BusinessAnalyticsStore],
  imports: [Card, Button, Icon, Skeleton, SalesChart],
  templateUrl: './business-overview.html',
  styleUrl: './business-overview.scss',
})
export class BusinessOverview {
  readonly analytics = inject(BusinessAnalyticsStore);
  readonly periods = [3, 6, 12] as const;
  readonly francs = francs;
  readonly quantity = quantity;
  readonly changeLabel = changeLabel;
  readonly periodLabel = periodLabel;
}
