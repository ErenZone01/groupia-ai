import { Component, DestroyRef, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DirectCartStore } from './direct-cart.store';
import { DirectSalesApi, DirectCartQuote } from './direct-sales.api';
import { PublicShell } from '../checkout/public-shell';
import { money, variantLabel } from '../../shared/utils/display';
import { apiMessage } from '../../core/http/api-error';

@Component({
  selector: 'g-direct-cart-page',
  imports: [RouterLink, PublicShell],
  templateUrl: './direct-cart.page.html',
  styleUrl: './direct-cart.page.scss',
})
export class DirectCartPage {
  readonly cart = inject(DirectCartStore);
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(DirectSalesApi);
  readonly publicId = signal('');
  readonly quote = signal<DirectCartQuote | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly money = money;
  readonly variantLabel = variantLabel;
  private sequence = 0;

  constructor() {
    const sub = this.route.paramMap.subscribe((params) => {
      const id = params.get('publicIdentifier') ?? '';
      this.publicId.set(id);
      this.cart.forShop(id);
      void this.refresh();
    });
    inject(DestroyRef).onDestroy(() => {
      sub.unsubscribe();
      this.sequence++;
    });
  }

  async refresh() {
    const seq = ++this.sequence;
    const id = this.publicId();
    const lines = this.cart.items();
    this.error.set('');
    this.quote.set(null);
    if (!id || lines.length === 0) {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    try {
      const data = await this.api.quote(id, lines);
      if (this.sequence === seq) this.quote.set(data);
    } catch (e) {
      if (this.sequence === seq) this.error.set(apiMessage(e));
    } finally {
      if (this.sequence === seq) this.loading.set(false);
    }
  }

  quantity(offerId: string, raw: string) {
    const amount = Number(raw);
    if (!Number.isSafeInteger(amount)) return;
    this.cart.setQuantity(this.publicId(), offerId, amount);
    void this.refresh();
  }

  remove(offerId: string) {
    this.cart.remove(this.publicId(), offerId);
    void this.refresh();
  }

  clear() {
    this.cart.clear(this.publicId());
    void this.refresh();
  }
}
