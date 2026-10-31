import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Icon } from '../../shared/ui/icon';
import { PublicShell } from '../checkout/public-shell';
import { money, variantLabel } from '../../shared/utils/display';
import { apiMessage } from '../../core/http/api-error';
import { DirectSalesApi, DirectStorefront } from './direct-sales.api';

@Component({
  selector: 'g-public-direct-page',
  imports: [Icon, PublicShell],
  templateUrl: './public-direct.page.html',
  styleUrl: './public-direct.page.scss',
})
export class PublicDirectPage {
  private readonly api = inject(DirectSalesApi);
  private readonly route = inject(ActivatedRoute);
  readonly store = signal<DirectStorefront | null>(null);
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

  async load(identifier: string) {
    const request = ++this.counter;
    this.loading.set(true);
    this.error.set('');
    this.store.set(null);
    try {
      const store = await this.api.publicStore(identifier);
      if (request === this.counter) this.store.set(store);
    } catch (error) {
      if (request === this.counter) this.error.set(apiMessage(error));
    } finally {
      if (request === this.counter) this.loading.set(false);
    }
  }
}
