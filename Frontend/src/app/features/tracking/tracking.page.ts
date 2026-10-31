import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ShippingSettlement } from '../../shared/ui/shipping-settlement';
import { OrderTimeline } from './order-timeline';
import { PublicShell } from '../checkout/public-shell';
import { PublicTrackingStore } from './public-tracking.store';
import { OrderSummary } from './order-summary';
import { orderStatus, fulfillmentStatus } from '../checkout/public-messages';
import { Button } from '../../shared/ui/button';
import { Icon } from '../../shared/ui/icon';
import { Skeleton } from '../../shared/feedback/skeleton';

@Component({
  selector: 'g-tracking-page',
  providers: [PublicTrackingStore],
  imports: [
    ShippingSettlement,
    OrderTimeline,
    PublicShell,
    OrderSummary,
    Button,
    Icon,
    Skeleton,
    RouterLink,
  ],
  templateUrl: './tracking.page.html',
  styleUrl: './tracking.page.scss',
})
export class TrackingPage {
  readonly store = inject(PublicTrackingStore);
  readonly statusLabel = orderStatus;
  readonly fulfillment = fulfillmentStatus;
}
