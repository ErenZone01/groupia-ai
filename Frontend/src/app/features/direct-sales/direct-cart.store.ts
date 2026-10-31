import { Injectable, computed, signal } from '@angular/core';

export interface DirectCartItem {
  offerId: string;
  quantity: number;
}

/** In-memory cart scoped to one public shop. Never stores customer secrets or prices. */
@Injectable({ providedIn: 'root' })
export class DirectCartStore {
  readonly shopId = signal<string | null>(null);
  readonly items = signal<DirectCartItem[]>([]);
  readonly count = computed(() => this.items().reduce((sum, item) => sum + item.quantity, 0));

  forShop(id: string) {
    if (this.shopId() === id) return;
    this.shopId.set(id);
    this.items.set([]);
  }

  add(shopId: string, offerId: string) {
    this.forShop(shopId);
    const items = this.items();
    const existing = items.find((item) => item.offerId === offerId);
    if (existing && existing.quantity >= 100) return;
    if (!existing && items.length >= 20) return;
    this.items.set(existing
      ? items.map((item) => item.offerId === offerId
        ? { ...item, quantity: item.quantity + 1 } : item)
      : [...items, { offerId, quantity: 1 }]);
  }

  setQuantity(shopId: string, offerId: string, quantity: number) {
    this.forShop(shopId);
    if (!Number.isSafeInteger(quantity)) return;
    this.items.update((all) => quantity <= 0
      ? all.filter((item) => item.offerId !== offerId)
      : all.map((item) => item.offerId === offerId
        ? { ...item, quantity: Math.min(100, quantity) } : item));
  }

  remove(shopId: string, offerId: string) {
    this.setQuantity(shopId, offerId, 0);
  }

  clear(shopId: string) {
    this.forShop(shopId);
    this.items.set([]);
  }
}
