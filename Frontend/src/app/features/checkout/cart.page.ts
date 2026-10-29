import { Component, DestroyRef, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { GroupiaApi } from '../../api/groupia-api';
import { CartStore } from './cart.store';
import { CartLines } from './cart-lines';
import { PublicShell } from './public-shell';
import { Button } from '../../shared/ui/button';
import { Icon } from '../../shared/ui/icon';
import { Skeleton } from '../../shared/feedback/skeleton';
import { money } from '../../shared/utils/display';
import { publicShippingMessage } from '../public-groupage/public-order-info';
import { publicMessage } from './public-messages';

@Component({
  selector: 'g-cart-page',
  imports: [PublicShell, CartLines, Button, RouterLink, Icon, Skeleton],
  templateUrl: './cart.page.html',
  styleUrl: './cart.page.scss',
})
export class CartPage {
  readonly cart = inject(CartStore);
  private readonly api = inject(GroupiaApi);
  readonly id = inject(ActivatedRoute).snapshot.paramMap.get('publicIdentifier')!;
  readonly loading = signal(true);
  readonly error = signal('');
  readonly money = money;
  readonly publicShippingMessage = publicShippingMessage;
  private alive = true;

  constructor() {
    this.cart.select(this.id);
    inject(DestroyRef).onDestroy(() => (this.alive = false));
    void this.load();
  }

  async load() {
    this.loading.set(true);
    this.error.set('');
    try {
      const group = await this.api.publicGrouping(this.id);
      if (this.alive) this.cart.reconcile(group);
    } catch (error) {
      if (this.alive) this.error.set(publicMessage(error));
    } finally {
      if (this.alive) this.loading.set(false);
    }
  }
}
