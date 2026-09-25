import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Card } from '../../shared/ui/card';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Icon } from '../../shared/ui/icon';
import { Avatar } from '../../shared/ui/avatar';
import { StatCard } from '../../shared/ui/stat-card';
import { EmptyState } from '../../shared/feedback/empty-state';
import { SalesChart } from './sales-chart';
import { CampaignArt } from './campaign-art';
import { dashboardData } from './dashboard.data';
@Component({
  selector: 'g-dashboard-page',
  imports: [
    RouterLink,
    Card,
    Badge,
    Button,
    Icon,
    Avatar,
    StatCard,
    EmptyState,
    SalesChart,
    CampaignArt,
  ],
  templateUrl: './dashboard.page.html',
  styleUrl: './dashboard.page.scss',
})
export class DashboardPage {
  readonly data = dashboardData;
  readonly labels = ['Commandes', 'Payées', 'En attente', 'Expirées'];
  readonly tones = ['info', 'positive', 'warning', 'danger'];
  readonly icons = ['orders', 'check', 'clock', 'close'];
}
