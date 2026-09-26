import { Injectable, computed, signal } from '@angular/core';
import { PublicGroupingView, PublicOfferView } from '../../api/generated/models';
export interface CartItem {
  offer: PublicOfferView;
  quantity: number;
}
@Injectable({ providedIn: 'root' })
export class CartStore {
  private readonly carts = signal<Record<string, CartItem[]>>(this.restore());
  readonly publicIdentifier = signal('');
  readonly group = signal<PublicGroupingView | null>(null);
  readonly items = computed(() => this.carts()[this.publicIdentifier()] ?? []);
  readonly count = computed(() => this.items().reduce((sum, i) => sum + i.quantity, 0));
  readonly subtotal = computed(() =>
    this.items().reduce((sum, i) => sum + i.quantity * i.offer.unitPrice!, 0),
  );
  readonly changes = computed(() =>
    this.items().filter((i) => {
      const o = this.group()?.offers?.find((o) => o.offerId === i.offer.offerId);
      return (
        !o ||
        !o.active ||
        !o.catalog?.usable ||
        o.unitPrice !== i.offer.unitPrice ||
        o.priceRevision !== i.offer.priceRevision
      );
    }),
  );
  readonly ready = computed(
    () =>
      !!this.group()?.orderable &&
      this.count() > 0 &&
      this.changes().length === 0 &&
      Number.isSafeInteger(this.subtotal()),
  );
  select(id: string) {
    if (id !== this.publicIdentifier()) {
      this.publicIdentifier.set(id);
      this.group.set(null);
    }
  }
  add(group: PublicGroupingView, offer: PublicOfferView) {
    if (group.publicIdentifier) this.select(group.publicIdentifier);
    this.group.set(group);
    if (
      !group.orderable ||
      !offer.active ||
      !offer.catalog?.usable ||
      !offer.offerId ||
      !Number.isSafeInteger(offer.unitPrice) ||
      offer.unitPrice! < 0 ||
      !Number.isSafeInteger(offer.priceRevision)
    )
      return;
    const found = this.items().find((i) => i.offer.offerId === offer.offerId);
    if (found) this.quantity(offer.offerId, found.quantity + 1);
    else if (this.items().length < 100)
      this.save([...this.items(), { offer: structuredClone(offer), quantity: 1 }]);
  }
  quantity(id: string, quantity: number) {
    if (!Number.isSafeInteger(quantity) || quantity < 0 || quantity > 1000000) return;
    this.save(
      this.items().flatMap((i) =>
        i.offer.offerId !== id ? [i] : quantity ? [{ ...i, quantity }] : [],
      ),
    );
  }
  remove(id: string) {
    const item = this.items().find((i) => i.offer.offerId === id);
    if (item) this.quantity(id, item.quantity - 1);
  }
  delete(id: string) {
    this.save(this.items().filter((i) => i.offer.offerId !== id));
  }
  clear(id = this.publicIdentifier()) {
    this.carts.update((c) => ({ ...c, [id]: [] }));
    this.persist();
  }
  reconcile(group: PublicGroupingView) {
    this.group.set(group);
  }
  acceptPrice(id: string) {
    const offer = this.group()?.offers?.find((o) => o.offerId === id);
    if (
      !offer?.active ||
      !offer.catalog?.usable ||
      !Number.isSafeInteger(offer.unitPrice) ||
      !Number.isSafeInteger(offer.priceRevision)
    )
      return;
    this.save(
      this.items().map((i) =>
        i.offer.offerId === id ? { ...i, offer: structuredClone(offer) } : i,
      ),
    );
  }
  currentOffer(id: string) {
    return this.group()?.offers?.find((o) => o.offerId === id);
  }
  lines() {
    return this.items()
      .map((i) => ({
        offerId: i.offer.offerId!,
        quantity: i.quantity,
        expectedUnitPrice: i.offer.unitPrice!,
        expectedPriceRevision: i.offer.priceRevision!,
      }))
      .sort((a, b) => a.offerId.localeCompare(b.offerId));
  }
  private save(items: CartItem[]) {
    this.carts.update((c) => ({ ...c, [this.publicIdentifier()]: items }));
    this.persist();
  }
  private persist() {
    try {
      sessionStorage.setItem('groupia.carts', JSON.stringify(this.carts()));
    } catch {
      /* The current in-memory cart remains usable. */
    }
  }
  private restore(): Record<string, CartItem[]> {
    try {
      const value = JSON.parse(sessionStorage.getItem('groupia.carts') ?? '{}');
      if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
      return Object.fromEntries(
        Object.entries(value).filter((entry): entry is [string, CartItem[]] => {
          const [key, items] = entry;
          return (
            !!key &&
            Array.isArray(items) &&
            items.every(
              (i) =>
                i?.offer?.offerId &&
                Number.isSafeInteger(i.quantity) &&
                i.quantity > 0 &&
                i.quantity <= 1000000 &&
                Number.isSafeInteger(i.offer.unitPrice) &&
                i.offer.unitPrice >= 0 &&
                Number.isSafeInteger(i.offer.priceRevision),
            )
          );
        }),
      );
    } catch {
      return {};
    }
  }
}
