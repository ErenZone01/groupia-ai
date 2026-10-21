import { Component, effect, inject, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Card } from '../../shared/ui/card';
import { Button } from '../../shared/ui/button';
import { Icon } from '../../shared/ui/icon';
import { StatCard } from '../../shared/ui/stat-card';
import { EmptyState } from '../../shared/feedback/empty-state';
import { Skeleton } from '../../shared/feedback/skeleton';
import { RecentOrders } from './recent-orders';
import { OrdersDashboardStore } from './orders-dashboard.store';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { AuthService } from '../../core/auth/auth.service';
import { BusinessOverview } from './business-overview';
@Component({
  selector: 'g-dashboard-page',
  providers: [OrdersDashboardStore],
  imports: [
    RouterLink,
    Card,
    Button,
    Icon,
    StatCard,
    EmptyState,
    Skeleton,
    RecentOrders,
    BusinessOverview,
  ],
  templateUrl: './dashboard.page.html',
  styleUrl: './dashboard.page.scss',
})
export class DashboardPage {
  readonly store = inject(OrdersDashboardStore);
  readonly context = inject(BoutiqueContextService);
  private readonly auth = inject(AuthService);
  readonly labels = ['Commandes', 'Payées', 'En attente', 'Expirées'];
  readonly tones = ['info', 'positive', 'warning', 'danger'];
  readonly icons = ['orders', 'check', 'clock', 'close'];
  constructor() {
    effect(() => {
      const id = this.context.selectedId();
      const authenticated = this.auth.signedIn();
      this.auth.generation();
      untracked(() => (id && authenticated ? this.store.load(id) : this.store.reset()));
    });
  }
  reload() {
    const id = this.context.selectedId();
    if (id) this.store.load(id);
  }
}
