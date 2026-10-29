import { Component, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PublicGroupingView, PublicOfferView } from '../../api/generated/models';
import { CartStore } from '../checkout/cart.store';
import { Button } from '../../shared/ui/button';
import { Icon } from '../../shared/ui/icon';
import { money, variantLabel } from '../../shared/utils/display';

@Component({
  selector: 'g-public-selection-panel',
  imports: [RouterLink, Button, Icon],
  templateUrl: './public-selection-panel.html',
  styleUrl: './public-selection-panel.scss',
})
export class PublicSelectionPanel {
  readonly group = input.required<PublicGroupingView>();
  readonly selection = inject(CartStore);
  readonly money = money;
  readonly variantLabel = variantLabel;

  productLabel(offer: PublicOfferView): string {
    return offer.commercialLabel || offer.catalog?.productName || 'Produit';
  }

  canIncrease(offer: PublicOfferView): boolean {
    const current = this.group().offers?.find((item) => item.offerId === offer.offerId);
    return !!this.group().orderable && !!current?.active && !!current.catalog?.usable &&
      !!current.offerId && Number.isSafeInteger(current.unitPrice);
  }

  increase(offer: PublicOfferView) {
    const current = this.group().offers?.find((item) => item.offerId === offer.offerId);
    if (current && this.canIncrease(offer)) this.selection.add(this.group(), current);
  }
}
