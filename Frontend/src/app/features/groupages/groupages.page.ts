import { Component, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CampaignPage, CampaignSummary } from '../../api/generated/models';
import { GroupiaApi } from '../../api/groupia-api';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { apiMessage } from '../../core/http/api-error';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Icon } from '../../shared/ui/icon';
import { Skeleton } from '../../shared/feedback/skeleton';
import { EmptyState } from '../../shared/feedback/empty-state';
import { statusLabel } from '../../shared/utils/display';

@Component({
  selector: 'g-groupages-page',
  imports: [RouterLink, Badge, Button, Icon, Skeleton, EmptyState],
  templateUrl: './groupages.page.html',
  styleUrl: './groupages.page.scss',
})
export class GroupagesPage {
  private readonly api = inject(GroupiaApi);
  readonly context = inject(BoutiqueContextService);
  readonly data = signal<CampaignPage | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly statusLabel = statusLabel;
  private request = 0;
  private currentPage = 0;

  constructor() {
    effect(() => {
      const org = this.context.selectedId();
      // Never leave another organization's campaigns visible after the boutique is cleared.
      if (!org) {
        this.request++;
        this.currentPage = 0;
        this.data.set(null);
        this.error.set('');
        this.loading.set(false);
        return;
      }
      void this.load(0, org);
    });
  }

  async load(page = this.currentPage, organizationId = this.context.selectedId()) {
    if (!organizationId || !Number.isSafeInteger(page) || page < 0) return;
    const seq = ++this.request;
    this.currentPage = page;
    this.loading.set(true);
    this.error.set('');
    this.data.set(null);
    try {
      const result = await this.api.campaigns(organizationId, page);
      if (seq === this.request && this.context.selectedId() === organizationId)
        this.data.set(result);
    } catch (e) {
      if (seq === this.request && this.context.selectedId() === organizationId)
        this.error.set(apiMessage(e));
    } finally {
      if (seq === this.request && this.context.selectedId() === organizationId)
        this.loading.set(false);
    }
  }

  statusTone(group: CampaignSummary): 'positive' | 'warning' | 'neutral' | 'info' {
    if (group.status === 'OPEN') return 'positive';
    if (group.status === 'DRAFT') return 'warning';
    if (['IN_TRANSIT', 'ARRIVED', 'DISTRIBUTING'].includes(group.status ?? '')) return 'info';
    return 'neutral';
  }

  statusHint(group: CampaignSummary) {
    switch (group.status) {
      case 'DRAFT':
        return 'En préparation · non publié';
      case 'OPEN':
        return group.publicIdentifier
          ? 'En ligne · lien disponible dans le détail'
          : 'Commandes ouvertes';
      case 'CLOSED':
        return 'Période de commande terminée';
      case 'SUPPLIER_ORDERED':
        return 'Commande fournisseur en cours';
      case 'IN_TRANSIT':
        return 'Transport en cours';
      case 'ARRIVED':
        return 'Marchandise arrivée';
      case 'DISTRIBUTING':
        return 'Distribution en cours';
      case 'COMPLETED':
        return 'Groupage terminé';
      case 'CANCELLED':
        return 'Groupage annulé';
      default:
        return 'Consultez le détail du groupage';
    }
  }

  closing(value?: string) {
    if (!value || !Number.isFinite(Date.parse(value))) return '';
    return new Intl.DateTimeFormat('fr-FR', {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone: 'Africa/Dakar',
    }).format(new Date(value));
  }
}
