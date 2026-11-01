import { DOCUMENT } from '@angular/common';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ProductView } from '../../api/generated/models';
import { GroupiaApi } from '../../api/groupia-api';
import { AuthService } from '../../core/auth/auth.service';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { apiMessage } from '../../core/http/api-error';
import { Button } from '../../shared/ui/button';
import { Icon } from '../../shared/ui/icon';
import { money, variantLabel } from '../../shared/utils/display';
import { DirectDeliveryMode, DirectDeliveryPolicy, DirectOffer, DirectSalesApi, DirectStorefront } from './direct-sales.api';
import { SENEGAL_REGIONS } from '../checkout/senegal-localities';

@Component({
  selector: 'g-direct-sales-page',
  imports: [FormsModule, RouterLink, Button, Icon],
  templateUrl: './direct-sales.page.html',
  styleUrl: './direct-sales.page.scss',
})
export class DirectSalesPage {
  private readonly api = inject(DirectSalesApi);
  private readonly productsApi = inject(GroupiaApi);
  private readonly auth = inject(AuthService);
  readonly context = inject(BoutiqueContextService);
  private readonly browser = inject(DOCUMENT).defaultView;

  readonly store = signal<DirectStorefront | null>(null);
  readonly products = signal<ProductView[]>([]);
  readonly deliveryPolicy = signal<DirectDeliveryPolicy | null>(null);
  readonly deliveryRegions = SENEGAL_REGIONS.map((region) => region.name);
  deliveryMode: DirectDeliveryMode = 'ALL';
  deliverySelected: string[] = [];
  readonly page = signal(0);
  readonly totalPages = signal(0);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly money = money;
  readonly variantLabel = variantLabel;
  readonly publishedCount = computed(() => this.store()?.offers.filter((o) => o.published && o.usable).length ?? 0);
  readonly variants = computed(() => this.products()
    .filter((p) => p.status === 'ACTIVE')
    .flatMap((p) => (p.variants ?? [])
      .filter((v) => v.active && v.id)
      .map((v) => ({
        id: v.id!,
        label: (p.name ?? 'Produit') + ' · ' + variantLabel(v.attributes),
      }))));
  name = '';
  selectedVariant = '';
  priceXof: number | null = null;
  readonly prices = signal<Record<string, number>>({});
  private request = 0;

  constructor() {
    effect(() => {
      const id = this.context.selectedId();
      this.auth.generation();
      untracked(() => {
        this.store.set(null);
        this.products.set([]);
        this.deliveryPolicy.set(null);
        this.deliveryMode = 'ALL';
        this.deliverySelected = [];
        this.name = '';
        this.selectedVariant = '';
        this.priceXof = null;
        this.busy.set(false);
        this.error.set('');
        this.notice.set('');
        const seq = ++this.request;
        if (id) void this.load(id, seq);
        else this.loading.set(false);
      });
    });
  }

  private current(id: string, seq: number, generation: number) {
    return seq === this.request && id === this.context.selectedId()
      && generation === this.auth.generation();
  }

  private setStore(next: DirectStorefront) {
    this.store.set(next);
    this.name = next.displayName ?? this.context.selected()?.name ?? '';
    this.prices.set(Object.fromEntries(next.offers.map((offer) => [offer.id, offer.priceXof])));
  }

  private async load(id: string, seq: number) {
    const generation = this.auth.generation();
    this.loading.set(true);
    try {
      const store = await this.api.manager(id);
      if (!this.current(id, seq, generation)) return;
      this.setStore(store);
      if (store.publicIdentifier) {
        const policy = await this.api.deliveryPolicy(id);
        if (!this.current(id, seq, generation)) return;
        this.setDeliveryPolicy(policy);
      }
      await this.loadProducts(id, 0, seq, generation);
    } catch (error) {
      if (this.current(id, seq, generation)) this.error.set(apiMessage(error));
    } finally {
      if (this.current(id, seq, generation)) this.loading.set(false);
    }
  }

  async loadProducts(id: string, page: number, seq = this.request, generation = this.auth.generation()) {
    const data = await this.productsApi.products(id, page);
    const details: ProductView[] = [];
    const items = data.items ?? [];
    for (let i = 0; i < items.length; i += 6) {
      if (!this.current(id, seq, generation)) return;
      details.push(...await Promise.all(items.slice(i, i + 6).map(
        (product) => this.productsApi.product(id, product.id!),
      )));
    }
    if (!this.current(id, seq, generation)) return;
    this.products.set(details);
    this.page.set(page);
    this.totalPages.set(data.totalPages ?? 0);
  }

  async changePage(page: number) {
    const id = this.context.selectedId();
    if (!id || page < 0 || page >= this.totalPages() || this.busy()) return;
    const seq = this.request;
    const generation = this.auth.generation();
    this.busy.set(true);
    try {
      await this.loadProducts(id, page, seq, generation);
    } catch (error) {
      if (this.current(id, seq, generation)) this.error.set(apiMessage(error));
    } finally {
      if (this.current(id, seq, generation)) this.busy.set(false);
    }
  }

  private async mutate(action: (id: string) => Promise<DirectStorefront>, success: string) {
    const id = this.context.selectedId();
    if (!id || this.busy()) return;
    const seq = this.request, generation = this.auth.generation();
    this.error.set('');
    this.notice.set('');
    this.busy.set(true);
    try {
      const result = await action(id);
      if (!this.current(id, seq, generation)) return;
      this.setStore(result);
      // A newly created storefront has no delivery policy until its first read.
      if (result.publicIdentifier && !this.deliveryPolicy()) {
        const policy = await this.api.deliveryPolicy(id);
        if (!this.current(id, seq, generation)) return;
        this.setDeliveryPolicy(policy);
      }
      this.notice.set(success);
    } catch (error) {
      if (this.current(id, seq, generation)) this.error.set(apiMessage(error));
    } finally {
      if (this.current(id, seq, generation)) this.busy.set(false);
    }
  }

  private setDeliveryPolicy(policy: DirectDeliveryPolicy) {
    this.deliveryPolicy.set(policy);
    this.deliveryMode = policy.mode;
    this.deliverySelected = [...policy.allowedRegions];
  }

  toggleDeliveryRegion(region: string, checked: boolean) {
    this.deliverySelected = checked
      ? [...new Set([...this.deliverySelected, region])]
      : this.deliverySelected.filter((name) => name !== region);
  }

  async saveDeliveryPolicy() {
    const id = this.context.selectedId();
    const old = this.deliveryPolicy();
    if (!id || !old || this.busy()
      || (this.deliveryMode === 'SELECTED' && !this.deliverySelected.length)) return;
    const seq = this.request, generation = this.auth.generation();
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      const policy = await this.api.updateDeliveryPolicy(id, {
        mode: this.deliveryMode,
        allowedRegions: this.deliveryMode === 'SELECTED' ? this.deliverySelected : [],
        version: old.version,
      });
      if (this.current(id, seq, generation)) {
        this.setDeliveryPolicy(policy);
        this.notice.set('Zones de livraison enregistrées.');
      }
    } catch (error) {
      if (this.current(id, seq, generation)) this.error.set(apiMessage(error));
    } finally {
      if (this.current(id, seq, generation)) this.busy.set(false);
    }
  }

  async saveName() {
    await this.mutate((id) => this.api.rename(id, this.name.trim()), 'Nom de boutique enregistré.');
  }

  async add() {
    if (!this.selectedVariant || !Number.isSafeInteger(this.priceXof) ||
      this.priceXof! < 1 || this.priceXof! > 999999999999) return;
    const variant = this.selectedVariant, price = this.priceXof!;
    await this.mutate((id) => this.api.add(id, variant, price), 'Offre ajoutée en brouillon.');
  }

  setPrice(id: string, value: string) {
    this.prices.update((previous) => ({ ...previous, [id]: Number(value) }));
  }

  async update(offer: DirectOffer, published: boolean) {
    const price = this.prices()[offer.id];
    if (!Number.isSafeInteger(price) || price < 1 || price > 999999999999) return;
    await this.mutate(
      (id) => this.api.update(id, offer.id, offer.version, price, published),
      published ? 'Offre publiée.' : 'Offre enregistrée et masquée de la boutique publique.',
    );
  }

  publicLink() {
    const id = this.store()?.publicIdentifier;
    return id && this.browser ? this.browser.location.origin + '/v/' + id : '';
  }

  async copyLink() {
    const link = this.publicLink();
    if (!link || !this.browser?.navigator.clipboard) {
      this.error.set('Copie indisponible : utilisez le lien affiché.');
      return;
    }
    try {
      await this.browser.navigator.clipboard.writeText(link);
      this.notice.set('Lien copié.');
    } catch {
      this.error.set('Copie indisponible : sélectionnez le lien affiché.');
    }
  }
}
