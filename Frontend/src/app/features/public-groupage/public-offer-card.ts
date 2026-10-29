import { Component, computed, input, output, signal } from '@angular/core';
import { PublicGroupingView, PublicOfferView } from '../../api/generated/models';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Icon } from '../../shared/ui/icon';
import { money, variantLabel } from '../../shared/utils/display';

@Component({
  selector: 'g-public-offer-card',
  imports: [Badge, Button, Icon],
  templateUrl: './public-offer-card.html',
  styleUrl: './public-offer-card.scss',
})
export class PublicOfferCard {
  readonly group = input.required<PublicGroupingView>();
  readonly offer = input.required<PublicOfferView>();
  readonly selectedQuantity = input(0);
  readonly added = output<void>();
  readonly activeImageIndex = signal(0);
  readonly money = money;
  readonly variantLabel = variantLabel;
  readonly label = computed(
    () => this.offer().commercialLabel || this.offer().catalog?.productName || 'Produit',
  );
  readonly activeImage = computed(() => {
    const images = this.offer().images ?? [];
    if (!images.length) return null;
    return images[Math.min(this.activeImageIndex(), images.length - 1)] ?? images[0] ?? null;
  });
  readonly canAdd = computed(() => {
    const group = this.group(), offer = this.offer();
    return !!group.orderable && !!offer.active && !!offer.catalog?.usable &&
      !!offer.offerId && Number.isSafeInteger(offer.unitPrice) && (offer.unitPrice ?? -1) >= 0;
  });
  selectImage(index: number) {
    const images = this.offer().images ?? [];
    if (index >= 0 && index < images.length) this.activeImageIndex.set(index);
  }
}
