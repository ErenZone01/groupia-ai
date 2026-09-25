import { Injectable, computed, signal } from '@angular/core';
import { PublicGroupingView, PublicOfferView } from '../../api/generated/models';
export interface SelectedItem {
  offer: PublicOfferView;
  quantity: number;
}
@Injectable()
export class PublicSelectionService {
  readonly items = signal<SelectedItem[]>([]);
  readonly count = computed(() => this.items().reduce((sum, i) => sum + i.quantity, 0));
  add(group: PublicGroupingView, offer: PublicOfferView) {
    if (!group.orderable || !offer.active || !offer.catalog?.usable || !offer.offerId) return;
    this.items.update((items) => {
      const found = items.find((i) => i.offer.offerId === offer.offerId);
      return found
        ? items.map((i) => (i === found ? { ...i, quantity: i.quantity + 1 } : i))
        : [...items, { offer, quantity: 1 }];
    });
  }
  remove(id: string) {
    this.items.update((items) =>
      items.flatMap((i) =>
        i.offer.offerId !== id ? [i] : i.quantity > 1 ? [{ ...i, quantity: i.quantity - 1 }] : [],
      ),
    );
  }
  clear() {
    this.items.set([]);
  }
  reconcile(group: PublicGroupingView) {
    if (!group.orderable) {
      this.clear();
      return;
    }
    this.items.update((items) =>
      items.filter((i) =>
        group.offers?.some(
          (o) =>
            o.offerId === i.offer.offerId &&
            o.active &&
            o.catalog?.usable &&
            o.priceRevision === i.offer.priceRevision &&
            o.unitPrice === i.offer.unitPrice,
        ),
      ),
    );
  }
}
