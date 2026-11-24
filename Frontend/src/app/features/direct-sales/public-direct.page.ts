import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Icon } from '../../shared/ui/icon';
import { PublicShell } from '../checkout/public-shell';
import { money, variantLabel } from '../../shared/utils/display';
import { apiMessage } from '../../core/http/api-error';
import { DirectSalesApi, DirectStorefront, DirectDeliveryPolicy } from './direct-sales.api';
import { DirectCartStore } from './direct-cart.store';

@Component({
  selector: 'g-public-direct-page',
  imports: [Icon, PublicShell, RouterLink],
  templateUrl: './public-direct.page.html',
  styleUrl: './public-direct.page.scss',
})
export class PublicDirectPage {
  private readonly api = inject(DirectSalesApi);
  readonly cart = inject(DirectCartStore);
  readonly notice = signal('');
  private readonly route = inject(ActivatedRoute);
  readonly store = signal<DirectStorefront | null>(null);
  readonly deliveryPolicy = signal<DirectDeliveryPolicy | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly search = signal('');
  readonly money = money;
  readonly variantLabel = variantLabel;
  readonly visible = computed(() => {
    const q = this.search().trim().toLocaleLowerCase('fr');
    return (this.store()?.offers ?? []).filter((offer) => !q ||
      (offer.productName + ' ' + Object.values(offer.attributes).join(' '))
        .toLocaleLowerCase('fr').includes(q));
  });
  private counter = 0;

  constructor() {
    const subscription = this.route.paramMap.subscribe((params) => {
      void this.load(params.get('publicIdentifier') ?? '');
    });
    inject(DestroyRef).onDestroy(() => {
      subscription.unsubscribe();
      this.counter++;
    });
  }

  addToCart(offerId: string) {
    const id = this.store()?.publicIdentifier;
    if (!id || this.store()?.offers.find(o => o.id === offerId)?.stockAvailable === 0) return;
    this.cart.add(id, offerId);
    this.notice.set('Article ajouté au panier.');
  }

  async load(identifier: string) {
    const request = ++this.counter;
    this.cart.forShop(identifier);
    this.notice.set('');
    this.loading.set(true);
    this.error.set('');
    this.store.set(null);
    this.deliveryPolicy.set(null);
    try {
      // Zones are an independent public read; failure must not hide the catalogue.
      const store = await this.api.publicStore(identifier);
      if (request !== this.counter) return;
      this.store.set(store);
      try {
        const policy = await this.api.publicDeliveryPolicy(identifier);
        if (request === this.counter) this.deliveryPolicy.set(policy);
      } catch {
        // Unknown coverage must never be displayed as nationwide delivery.
      }
    } catch (error) {
      if (request === this.counter) this.error.set(apiMessage(error));
    } finally {
      if (request === this.counter) this.loading.set(false);
    }
  }
}
