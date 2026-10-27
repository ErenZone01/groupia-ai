import {
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Subscription } from 'rxjs';
import {
  CampaignLifecycleView,
  SupplierOrderResponse,
  SupplierOrderView,
  SupplierSummaryView,
  SupplierSummaryLine,
  ProductView,
  ProductMediaSummary,
} from '../../api/generated/models';
import { OrdersApi } from './orders-api';
import { apiCode, apiMessage } from '../../core/http/api-error';
import { Button } from '../../shared/ui/button';
import { ProductThumbnail } from '../../shared/ui/product-thumbnail';
import { Badge } from '../../shared/ui/badge';
import { Skeleton } from '../../shared/feedback/skeleton';
import { EmptyState } from '../../shared/feedback/empty-state';
import { dateLabel, money, variantLabel } from '../../shared/utils/display';

const orderedStates = ['SUPPLIER_ORDERED', 'IN_TRANSIT', 'ARRIVED', 'DISTRIBUTING', 'COMPLETED'];

@Component({
  selector: 'g-supplier-summary-panel',
  imports: [Button, Badge, Skeleton, EmptyState, ProductThumbnail],
  templateUrl: './supplier-summary-panel.html',
  styleUrl: './supplier-summary-panel.scss',
})
export class SupplierSummaryPanel {
  readonly organizationId = input.required<string>();
  readonly campaignId = input.required<string>();
  readonly campaignStatus = input('DRAFT');
  readonly campaignVersion = input<number>();
  readonly products = input<ProductView[]>([]);
  readonly media = input<Record<string, ProductMediaSummary>>({});
  readonly showAll = signal(false);
  readonly visibleLines = computed(() => this.showAll() ? (this.data()?.lines ?? []) : (this.data()?.lines ?? []).slice(0, 4));
  readonly campaignChanged = output<CampaignLifecycleView>();
  readonly refreshCampaign = output<void>();
  readonly manageOrders = output<void>();
  private readonly api = inject(OrdersApi);
  readonly data = signal<SupplierSummaryView | null>(null);
  readonly purchase = signal<SupplierOrderView | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly confirming = signal(false);
  readonly stale = signal(false);
  readonly error = signal('');
  readonly mutationError = signal('');
  readonly pendingCount = signal<number | null>(null);
  readonly lateCount = signal(0);
  readonly frozen = computed(
    () => this.purchase() !== null || orderedStates.includes(this.campaignStatus()),
  );
  readonly canCreate = computed(
    () =>
      this.campaignStatus() === 'CLOSED' &&
      !this.frozen() &&
      !this.stale() &&
      !this.loading() &&
      !this.busy() &&
      (this.data()?.acceptedOrderCount ?? 0) > 0 &&
      Number.isSafeInteger(this.campaignVersion()) &&
      this.campaignVersion()! >= 0,
  );
  readonly money = money;
  /** Frozen purchases contain a stable product ID; before purchase require a unique exact match. */
  photo(line: SupplierSummaryLine, index: number) {
    const frozen = this.purchase()?.lines?.[index];
    const id = frozen?.productId ?? this.uniqueCurrentProduct(line);
    return id ? (this.media()[id]?.primaryThumbnailUrl ?? '') : '';
  }
  private uniqueCurrentProduct(line: SupplierSummaryLine): string | undefined {
    const candidates = this.products().flatMap((product) => (product.variants ?? [])
      .map((variant) => ({ product, variant })));
    const matches = line.skuSnapshot
      ? candidates.filter(({ variant }) => variant.sku === line.skuSnapshot)
      : candidates.filter(({ product, variant }) => product.name === line.productNameSnapshot &&
          JSON.stringify(Object.entries(variant.attributes ?? {}).sort()) ===
          JSON.stringify(Object.entries(line.variantAttributesSnapshot ?? {}).sort()));
    const unique = [...new Set(matches.map(({ product }) => product.id).filter((id): id is string => !!id))];
    return unique.length === 1 ? unique[0] : undefined;
  }
  readonly dateLabel = dateLabel;
  readonly variantLabel = variantLabel;
  private subscription?: Subscription;
  private mutation?: Subscription;
  constructor() {
    effect(() => {
      const org = this.organizationId(),
        campaign = this.campaignId(),
        status = this.campaignStatus();
      untracked(() => {
        const current = this.purchase();
        // A successful POST already supplied the complete document. Parent status update needs no second read.
        if (
          current?.organizationId === org &&
          current?.groupingCampaignId === campaign &&
          orderedStates.includes(status)
        )
          return;
        this.mutation?.unsubscribe();
        this.busy.set(false);
        this.confirming.set(false);
        this.purchase.set(null);
        this.showAll.set(false);
        this.stale.set(false);
        this.load(org, campaign, orderedStates.includes(status));
      });
    });
    inject(DestroyRef).onDestroy(() => {
      this.subscription?.unsubscribe();
      this.mutation?.unsubscribe();
    });
  }
  saleUnit(value?: string) {
    const labels: Record<string, string> = {
      UNIT: 'à l’unité',
      PAIR: 'par paire',
      SET: 'par ensemble',
      PACK: 'par paquet',
    };
    return labels[value ?? ''] ?? 'Unité non précisée';
  }
  count(value?: number) {
    return value !== undefined && Number.isSafeInteger(value) && value >= 0
      ? new Intl.NumberFormat('fr-FR').format(value)
      : '—';
  }
  reload() {
    if (!this.busy()) this.load(this.organizationId(), this.campaignId(), this.frozen());
  }
  begin() {
    if (this.canCreate()) this.confirming.set(true);
  }
  create() {
    if (!this.confirming() || !this.canCreate()) return;
    this.busy.set(true);
    this.mutationError.set('');
    this.pendingCount.set(null);
    this.mutation = this.api
      .createSupplierOrder(this.organizationId(), this.campaignId(), this.campaignVersion()!)
      .subscribe({
        next: (result) => {
          this.busy.set(false);
          this.confirming.set(false);
          this.apply(result);
        },
        error: (error) => {
          this.busy.set(false);
          this.confirming.set(false);
          const code = apiCode(error);
          if (code === 'SUPPLIER_ORDER_PENDING_PAYMENTS') {
            const count =
              error instanceof HttpErrorResponse
                ? error.error?.details?.pendingOrderCount
                : undefined;
            this.pendingCount.set(Number.isSafeInteger(count) && count > 0 ? count : null);
            this.mutationError.set('Des commandes sont encore en attente de paiement.');
          } else if (code === 'SUPPLIER_ORDER_EMPTY') {
            this.mutationError.set(
              'Aucune commande acceptée à commander. Actualisez le récapitulatif.',
            );
          } else {
            this.stale.set(true);
            this.mutationError.set(
              'Vérifiez la commande fournisseur ou actualisez le groupage avant de réessayer.',
            );
            // A lost response can hide a committed purchase. Only GET is retried automatically.
            this.checkPurchase();
          }
        },
      });
  }
  checkPurchase() {
    this.subscription?.unsubscribe();
    this.loading.set(true);
    this.subscription = this.api.supplierOrder(this.organizationId(), this.campaignId()).subscribe({
      next: (result) => {
        this.loading.set(false);
        this.error.set('');
        this.apply(result);
      },
      error: () => {
        this.loading.set(false);
        this.mutationError.set(
          'La commande fournisseur n’a pas pu être confirmée. Actualisez le groupage avant de réessayer.',
        );
      },
    });
  }
  private apply(result: SupplierOrderResponse) {
    const p = result.supplierOrder,
      c = result.campaign;
    if (
      !p ||
      !c ||
      c.id !== this.campaignId() ||
      c.organizationId !== this.organizationId() ||
      !Number.isSafeInteger(c.version)
    ) {
      this.stale.set(true);
      this.mutationError.set('Réponse incomplète. Actualisez le groupage.');
      return;
    }
    this.purchase.set(p);
    this.data.set({ ...p, lines: p.lines?.map((line) => line.snapshot!) });
    this.lateCount.set(result.lateAcceptedOrderCount ?? 0);
    this.mutationError.set('');
    this.stale.set(false);
    this.pendingCount.set(null);
    this.campaignChanged.emit(c);
  }
  private load(org: string, campaign: string, frozen: boolean) {
    this.subscription?.unsubscribe();
    this.data.set(null);
    this.error.set('');
    this.pendingCount.set(null);
    this.loading.set(true);
    if (frozen) {
      this.subscription = this.api.supplierOrder(org, campaign).subscribe({
        next: (result) => {
          this.loading.set(false);
          this.apply(result);
        },
        error: (e) => {
          this.loading.set(false);
          this.error.set(apiMessage(e));
        },
      });
    } else {
      this.subscription = this.api.supplierSummary(org, campaign).subscribe({
        next: (data) => {
          this.data.set(data);
          this.loading.set(false);
        },
        error: (e) => {
          this.error.set(apiMessage(e));
          this.loading.set(false);
        },
      });
    }
  }
}
