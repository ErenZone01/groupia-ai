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
import { ProductMediaApi } from '../catalog/product-media.api';
import { ProductMediaSummary } from '../../api/generated/models';
import { ProductView, OfferView, CampaignLifecycleView } from '../../api/generated/models';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { apiMessage } from '../../core/http/api-error';
import { GroupageStore, GroupageLine } from './groupage.store';
import { ProductPicker } from './product-picker';
import { OfferPriceEditor } from './offer-price-editor';
import { GroupageSettings } from './groupage-settings';
import { Card } from '../../shared/ui/card';
import { Icon } from '../../shared/ui/icon';
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
    Icon,
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
  styleUrl: './groupage-detail.page.scss',
})
export class GroupageDetailPage {
  readonly store = inject(GroupageStore);
  readonly context = inject(BoutiqueContextService);
  private readonly api = inject(GroupiaApi);
  private readonly mediaApi = inject(ProductMediaApi);
  private readonly route = inject(ActivatedRoute);
  private readonly document = inject(DOCUMENT);
  private readonly toast = inject(ToastService);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly catalogError = signal('');
  readonly products = signal<ProductView[]>([]);
  readonly media = signal<Record<string, ProductMediaSummary>>({});
  readonly showAllOffers = signal(false);
  readonly visibleOffers = computed(() => this.showAllOffers()
    ? (this.store.current()?.offers ?? [])
    : (this.store.current()?.offers ?? []).slice(0, 4));
  private readonly requestedTab = this.route.snapshot.queryParamMap.get('tab');
  readonly tab = signal(
    this.requestedTab === 'overview' || this.requestedTab === 'settings'
      ? 'info'
      : ['products', 'orders', 'supplier', 'transport', 'distribution', 'finances', 'completion', 'info']
          .includes(this.requestedTab ?? '')
        ? this.requestedTab!
        : 'products',
  );
  readonly adding = signal(false);
  readonly confirmingClose = signal(false);
  readonly tabs = computed(() => {
    const status = this.store.current()?.status ?? '';
    const afterClosing = ['CLOSED', 'SUPPLIER_ORDERED', 'IN_TRANSIT', 'ARRIVED', 'DISTRIBUTING', 'COMPLETED'].includes(status);
    const afterSupplier = ['SUPPLIER_ORDERED', 'IN_TRANSIT', 'ARRIVED', 'DISTRIBUTING', 'COMPLETED'].includes(status);
    const afterArrival = ['ARRIVED', 'DISTRIBUTING', 'COMPLETED'].includes(status);
    return [
      { id: 'products', label: 'Offres', icon: 'box' },
      { id: 'orders', label: 'Commandes', icon: 'orders' },
      ...(['OPEN', 'CLOSED', 'SUPPLIER_ORDERED', 'IN_TRANSIT', 'ARRIVED', 'DISTRIBUTING', 'COMPLETED'].includes(status)
        ? [{ id: 'supplier', label: 'Fournisseur', icon: 'bag' }] : []),
      ...(afterSupplier ? [{ id: 'transport', label: 'Transport', icon: 'group' }] : []),
      ...(afterArrival ? [{ id: 'distribution', label: 'Retraits', icon: 'check' }] : []),
      ...(afterClosing ? [{ id: 'finances', label: 'Finances', icon: 'card' }] : []),
      ...(['DISTRIBUTING', 'COMPLETED'].includes(status)
        ? [{ id: 'completion', label: 'Bilan', icon: 'trend' }] : []),
      { id: 'info', label: 'Infos', icon: 'info' },
    ];
  });
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
  readonly displayStatusTone = computed<'positive' | 'warning' | 'info' | 'neutral'>(() => {
    const status = this.store.current()?.status;
    if (status === 'OPEN') return 'positive';
    if (status === 'DRAFT') return 'warning';
    if (['IN_TRANSIT', 'ARRIVED', 'DISTRIBUTING'].includes(status ?? '')) return 'info';
    return 'neutral';
  });
  readonly nextStepLabel = computed(() => {
    switch (this.store.current()?.status) {
      case 'DRAFT': return 'Préparez vos offres';
      case 'OPEN': return 'Partagez et suivez les commandes';
      case 'CLOSED': return 'Préparez le fournisseur';
      case 'SUPPLIER_ORDERED': return 'Préparez le transport';
      case 'IN_TRANSIT': return 'Transport en cours';
      case 'ARRIVED': return 'Organisez les retraits';
      case 'DISTRIBUTING': return 'Retraits en cours';
      case 'COMPLETED': return 'Groupage terminé';
      case 'CANCELLED': return 'Groupage annulé';
      default: return 'Suivi du groupage';
    }
  });
  readonly dakarDate = (date?: string) =>
    date && Number.isFinite(Date.parse(date))
      ? new Intl.DateTimeFormat('fr-FR', {
          dateStyle: 'medium',
          timeStyle: 'short',
          timeZone: 'Africa/Dakar',
        }).format(new Date(date))
      : '';

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
      if (this.store.current() && !this.tabs().some((item) => item.id === this.tab()))
        this.tab.set('products');
    });
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
    this.media.set({});
    this.showAllOffers.set(false);
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
      if (seq !== this.sequence || id !== this.context.selectedId()) return;
      this.products.set(products);
      const productIds = [...new Set((this.store.current()?.offers ?? []).flatMap((offer) =>
        products.filter((p) => p.variants?.some((v) => v.id === offer.productVariantId))
          .map((p) => p.id).filter((value): value is string => !!value)))];
      if (productIds.length) {
        try {
          const summaries = await this.mediaApi.batch(id, productIds);
          if (seq === this.sequence && id === this.context.selectedId())
            this.media.set(Object.fromEntries(summaries.filter((m) => !!m.productId)
              .map((m) => [m.productId!, m])));
        } catch {
          // Les photos sont facultatives : ne pas bloquer les offres.
        }
      }
    } catch {
      if (seq === this.sequence)
        this.catalogError.set(
          'Les noms des produits ne sont pas disponibles. Réessayez pour les actualiser.',
        );
    }
  }
  thumbnail(offer: OfferView) {
    const product = this.products().find((p) =>
      p.variants?.some((v) => v.id === offer.productVariantId));
    return product?.id ? (this.media()[product.id]?.primaryThumbnailUrl ?? '') : '';
  }
  thumbnailFailed(offer: OfferView) {
    const product = this.products().find((p) =>
      p.variants?.some((v) => v.id === offer.productVariantId));
    if (product?.id)
      this.media.update((map) => ({
        ...map, [product.id!]: { ...map[product.id!], primaryThumbnailUrl: undefined },
      }));
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
