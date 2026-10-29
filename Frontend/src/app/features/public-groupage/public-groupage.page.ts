import { DOCUMENT } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  PublicGroupingView,
  PublicOfferView,
} from '../../api/generated/models';
import { GroupiaApi } from '../../api/groupia-api';
import { apiMessage } from '../../core/http/api-error';
import { ThemeService } from '../../core/theme/theme.service';
import { Brand } from '../../shared/ui/brand';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Icon } from '../../shared/ui/icon';
import { Select } from '../../shared/forms/select';
import { Skeleton } from '../../shared/feedback/skeleton';
import { EmptyState } from '../../shared/feedback/empty-state';
import { money, dateLabel, variantLabel } from '../../shared/utils/display';
import { PublicSelectionService } from './public-selection.service';
import { PublicOfferCard } from './public-offer-card';
import { publicShippingMessage, publicTransportLabel } from './public-order-info';

@Component({
  selector: 'g-public-groupage-page',
  imports: [RouterLink, Brand, Badge, Button, Icon, Select, Skeleton, EmptyState, PublicOfferCard],
  templateUrl: './public-groupage.page.html',
  styleUrl: './public-groupage.page.scss',
})
export class PublicGroupagePage {
  private readonly api = inject(GroupiaApi);
  private readonly route = inject(ActivatedRoute);
  private readonly window = inject(DOCUMENT).defaultView;
  readonly selection = inject(PublicSelectionService);
  readonly theme = inject(ThemeService);
  readonly data = signal<PublicGroupingView | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly query = signal('');
  readonly money = money;
  readonly dateLabel = dateLabel;
  readonly variantLabel = variantLabel;
  readonly publicShippingMessage = publicShippingMessage;
  readonly publicTransportLabel = publicTransportLabel;

  readonly visibleOffers = computed(() => {
    const items = this.data()?.offers ?? [];
    const query = this.query().trim().toLocaleLowerCase('fr');
    if (!query) return items;
    return items.filter((offer) =>
      [
        offer.commercialLabel ?? '',
        offer.catalog?.productName ?? '',
        offer.catalog?.productDescription ?? '',
        variantLabel(offer.catalog?.attributes),
      ]
        .some((value) => value.toLocaleLowerCase('fr').includes(query)),
    );
  });

  private request = 0;

  constructor() {
    const sub = this.route.paramMap.subscribe(() => {
      this.selection.select(
        this.route.snapshot.paramMap.get('publicIdentifier') ??
          this.route.snapshot.paramMap.get('publicId') ??
          '',
      );
      this.query.set('');
      void this.load();
    });
    const focus = () => void this.load(false);
    this.window?.addEventListener('focus', focus);
    inject(DestroyRef).onDestroy(() => {
      sub.unsubscribe();
      this.window?.removeEventListener('focus', focus);
      this.request++;
    });
  }

  async load(showLoading = true) {
    const id =
      this.route.snapshot.paramMap.get('publicIdentifier') ??
      this.route.snapshot.paramMap.get('publicId');
    if (!id) return;
    const seq = ++this.request;
    if (showLoading) {
      this.loading.set(true);
      this.data.set(null);
    }
    this.error.set('');
    try {
      const data = await this.api.publicGrouping(id);
      if (seq === this.request) {
        this.selection.reconcile(data);
        this.data.set(data);
      }
    } catch (error) {
      if (seq === this.request) {
        this.error.set(apiMessage(error));
        this.data.set(null);
      }
    } finally {
      if (seq === this.request) this.loading.set(false);
    }
  }

  productLabel(offer: PublicOfferView): string {
    return offer.commercialLabel || offer.catalog?.productName || 'Produit';
  }

  canAdd(group: PublicGroupingView, offer: PublicOfferView): boolean {
    return !!group.orderable && !!offer.active && !!offer.catalog?.usable &&
      !!offer.offerId && Number.isSafeInteger(offer.unitPrice);
  }

  selectedQuantity(offerId?: string): number {
    if (!offerId) return 0;
    return this.selection.items().find((item) => item.offer.offerId === offerId)?.quantity ?? 0;
  }

  increase(group: PublicGroupingView, offer: PublicOfferView) {
    if (this.canAdd(group, offer)) this.selection.add(group, offer);
  }

  reason(): string {
    const data = this.data();
    if (data?.status === 'CANCELLED') return 'Ce groupage a été annulé.';
    if (data?.closesAt && new Date(data.closesAt).getTime() <= Date.now())
      return 'La date de clôture est passée. Les commandes sont fermées.';
    if (data?.status === 'CLOSED' || data?.status === 'COMPLETED')
      return 'Les commandes de ce groupage sont fermées.';
    return 'Ce groupage ne reçoit pas de commandes actuellement.';
  }
}
