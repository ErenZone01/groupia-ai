import { CompletionPanel } from './completion-panel';
import { MerchantFundsPanel } from '../settlements/merchant-funds-panel';
import { DistributionPanel } from './distribution-panel';
import { LogisticsPanel } from './logistics-panel';
import { SupplierSummaryPanel } from '../orders/supplier-summary-panel';
import { OrdersPanel } from '../orders/orders-panel';
import { toSignal } from '@angular/core/rxjs-interop';
import { DOCUMENT } from '@angular/common';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { GroupiaApi } from '../../api/groupia-api';
import { ProductView, OfferView, CampaignLifecycleView } from '../../api/generated/models';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { apiMessage } from '../../core/http/api-error';
import { GroupageStore, GroupageLine } from './groupage.store';
import { ProductPicker } from './product-picker';
import { OfferPriceEditor } from './offer-price-editor';
import { GroupageSettings } from './groupage-settings';
import { Card } from '../../shared/ui/card';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Tabs } from '../../shared/ui/tabs';
import { Skeleton } from '../../shared/feedback/skeleton';
import { EmptyState } from '../../shared/feedback/empty-state';
import { ToastService } from '../../shared/feedback/toast.service';
import { dateLabel, statusLabel, money, variantLabel } from '../../shared/utils/display';
import {
  optionLabel,
  transportOptions,
  pricingOptions,
  collectionOptions,
} from './groupage-labels';
@Component({
  selector: 'g-groupage-detail',
  providers: [GroupageStore],
  imports: [
    RouterLink,
    OrdersPanel,
    SupplierSummaryPanel,
    LogisticsPanel,
    DistributionPanel,
    MerchantFundsPanel,
    CompletionPanel,
    Card,
    Badge,
    Button,
    Tabs,
    Skeleton,
    EmptyState,
    ProductPicker,
    OfferPriceEditor,
    GroupageSettings,
  ],
  templateUrl: './groupage-detail.page.html',
  styles: `
    .share {
      background: var(--primary-soft);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      padding: var(--s5);
      margin-bottom: var(--s6);
    }
    .share a {
      overflow-wrap: anywhere;
    }
    .product {
      padding: var(--s5) 0;
      border-bottom: 1px solid var(--border);
    }
    .price {
      font-size: var(--text-lg);
      font-weight: 600;
      margin: var(--s3) 0;
    }
    .summary {
      display: grid;
      gap: var(--s4);
    }
    g-product-picker {
      display: block;
      margin-top: var(--s6);
    }
  `,
})
export class GroupageDetailPage {
  readonly store = inject(GroupageStore);
  readonly context = inject(BoutiqueContextService);
  private readonly api = inject(GroupiaApi);
  private readonly route = inject(ActivatedRoute);
  private readonly document = inject(DOCUMENT);
  private readonly toast = inject(ToastService);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly catalogError = signal('');
  readonly products = signal<ProductView[]>([]);
  readonly tab = signal(
    ['orders', 'supplier', 'distribution'].includes(
      this.route.snapshot.queryParamMap.get('tab') ?? '',
    )
      ? this.route.snapshot.queryParamMap.get('tab')!
      : 'overview',
  );
  readonly adding = signal(false);
  readonly confirmingClose = signal(false);
  readonly tabs = computed(() => [
    { id: 'overview', label: 'Aperçu' },
    { id: 'products', label: 'Produits' },
    { id: 'orders', label: 'Commandes' },
    {
      id: 'supplier',
      label: ['SUPPLIER_ORDERED', 'IN_TRANSIT', 'ARRIVED', 'DISTRIBUTING', 'COMPLETED'].includes(
        this.store.current()?.status ?? '',
      )
        ? 'Commande fournisseur'
        : 'À commander',
    },
    ...(['ARRIVED', 'DISTRIBUTING', 'COMPLETED'].includes(this.store.current()?.status ?? '')
      ? [{ id: 'distribution', label: 'Distribution et retraits' }]
      : []),
    { id: 'settings', label: 'Paramètres' },
  ]);
  readonly editable = computed(() =>
    ['DRAFT', 'OPEN'].includes(this.store.current()?.status ?? ''),
  );
  readonly publicUrl = computed(() =>
    this.store.current()?.publicIdentifier
      ? this.document.location.origin +
        '/public/groupages/' +
        encodeURIComponent(this.store.current()!.publicIdentifier!)
      : '',
  );
  readonly published = this.route.snapshot.queryParamMap.has('published');
  readonly statusLabel = statusLabel;
  readonly dateLabel = dateLabel;
  readonly money = money;
  readonly optionLabel = optionLabel;
  readonly transportOptions = transportOptions;
  readonly pricingOptions = pricingOptions;
  readonly collectionOptions = collectionOptions;
  private readonly params = toSignal(this.route.paramMap, {
    initialValue: this.route.snapshot.paramMap,
  });
  private readonly id = computed(() => this.params().get('id')!);
  private sequence = 0;
  constructor() {
    effect(() => {
      const id = this.context.selectedId();
      this.id();
      if (id) void this.load(id);
    });
  }
  async load(id = this.context.selectedId()!) {
    const seq = ++this.sequence;
    this.loading.set(true);
    this.error.set('');
    this.products.set([]);
    this.catalogError.set('');
    try {
      await this.store.load(id, this.id());
    } catch (e) {
      if (seq === this.sequence) {
        this.error.set(apiMessage(e));
        this.store.current.set(null);
        this.loading.set(false);
      }
      return;
    }
    if (seq !== this.sequence) return;
    this.loading.set(false);
    try {
      const products = await this.api.allProducts(id);
      if (seq === this.sequence) this.products.set(products);
    } catch {
      if (seq === this.sequence)
        this.catalogError.set(
          'Les noms des produits ne sont pas disponibles. Réessayez pour les actualiser.',
        );
    }
  }
  label(offer: OfferView) {
    return (
      offer.commercialLabel ||
      this.products().find((p) => p.variants?.some((v) => v.id === offer.productVariantId))?.name ||
      'Produit du groupage'
    );
  }
  variant(offer: OfferView) {
    const v = this.products()
      .flatMap((p) => p.variants ?? [])
      .find((v) => v.id === offer.productVariantId);
    return v ? variantLabel(v.attributes) : 'Détails du produit indisponibles';
  }
  excluded() {
    return (this.store.current()?.offers ?? []).map((o) => o.productVariantId!);
  }
  async perform(action: 'open' | 'close') {
    const id = this.context.selectedId();
    if (!id || this.store.busy()) return;
    this.error.set('');
    try {
      const result = action === 'open' ? await this.store.open(id) : await this.store.close(id);
      this.confirmingClose.set(false);
      this.toast.show(
        result.status === 'OPEN' ? 'Votre groupage est en ligne 🎉' : 'Les commandes sont fermées.',
      );
    } catch (e) {
      this.error.set(apiMessage(e));
      this.toast.show(apiMessage(e), 'error');
    }
  }
  async add(line: GroupageLine) {
    const id = this.context.selectedId();
    if (!id || this.store.busy()) return;
    this.error.set('');
    try {
      await this.store.add(id, line);
      this.adding.set(false);
      this.toast.show('Produit ajouté au groupage.');
    } catch (e) {
      this.error.set(apiMessage(e));
      this.toast.show(apiMessage(e), 'error');
    }
  }
  purchaseCreated(campaign: CampaignLifecycleView) {
    const current = this.store.current();
    if (
      current &&
      current.id === campaign.id &&
      current?.organizationId === campaign.organizationId &&
      (campaign.version ?? -1) >= (current.version ?? 0) &&
      current.status !== 'COMPLETED'
    )
      this.store.current.set({ ...current, status: campaign.status, version: campaign.version });
  }
  async copy() {
    try {
      await this.document.defaultView?.navigator.clipboard.writeText(this.publicUrl());
      this.toast.show('Lien copié.');
    } catch {
      this.toast.show('Copie indisponible. Sélectionnez le lien pour le copier.', 'error');
    }
  }
}
