import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { apiMessage } from '../../core/http/api-error';
import { money, variantLabel } from '../../shared/utils/display';
import {
  DirectOrderDetail,
  DirectOrderPaymentStatus,
  DirectOrderSummary,
  DirectOrdersApi,
  DirectOrdersPage as DirectOrdersResponse,
} from './direct-orders.api';
import { DirectGeoPoint, DirectLocationMap, validDirectGeoPoint } from './direct-location-map';

type MerchantAction = 'cancel' | 'prepare' | 'ready';

@Component({
  selector: 'g-direct-orders-page',
  imports: [RouterLink, DatePipe, DirectLocationMap],
  templateUrl: './direct-orders.page.html',
  styleUrl: './direct-orders.page.scss',
})
export class DirectOrdersPage {
  private readonly api = inject(DirectOrdersApi);
  private readonly auth = inject(AuthService);
  readonly context = inject(BoutiqueContextService);
  readonly data = signal<DirectOrdersResponse | null>(null);
  readonly detail = signal<DirectOrderDetail | null>(null);
  readonly financial = signal<DirectOrderPaymentStatus | null>(null);
  readonly loading = signal(false);
  readonly detailLoading = signal(false);
  readonly financialLoading = signal(false);
  readonly actionBusy = signal(false);
  readonly error = signal('');
  readonly detailError = signal('');
  readonly financialError = signal('');
  readonly actionError = signal('');
  readonly actionNotice = signal('');
  readonly confirmCancellation = signal(false);
  readonly money = money;
  readonly variantLabel = variantLabel;
  readonly currentPage = signal(0);
  readonly totalPages = computed(() => {
    const page = this.data();
    return page ? Math.ceil(page.total / page.size) : 0;
  });
  readonly canCancel = computed(() => this.detail()?.order.status === 'PENDING_PAYMENT_INTEGRATION'
    && ['NOT_PREPARED', 'PREPARED', 'EXPIRED', 'UNAVAILABLE']
      .includes(this.financial()?.financialStatus ?? ''));
  readonly canPrepare = computed(() => this.detail()?.order.status === 'PAYMENT_CONFIRMED'
    && this.financial()?.financialStatus === 'SETTLEMENT_VERIFIED');
  readonly canReady = computed(() => this.detail()?.order.status === 'PREPARING'
    && this.financial()?.financialStatus === 'SETTLEMENT_VERIFIED');
  readonly reviewRequired = computed(() => ['REVIEW_REQUIRED', 'SETTLEMENT_PROCESSING']
    .includes(this.financial()?.financialStatus ?? ''));
  private request = 0;
  private detailRequest = 0;

  constructor() {
    effect(() => {
      const organizationId = this.context.selectedId();
      const generation = this.auth.generation();
      untracked(() => {
        const seq = ++this.request;
        this.clearDetail();
        this.data.set(null);
        this.error.set('');
        this.currentPage.set(0);
        if (organizationId) {
          void this.load(organizationId, 0, seq, generation);
        } else {
          this.loading.set(false);
        }
      });
    });
  }

  private current(id: string, seq: number, generation: number) {
    return id === this.context.selectedId() && seq === this.request
      && generation === this.auth.generation();
  }

  private currentDetail(id: string, listSeq: number, generation: number, detailSeq: number) {
    return this.current(id, listSeq, generation) && detailSeq === this.detailRequest;
  }

  private clearDetail() {
    this.detailRequest++;
    this.detail.set(null);
    this.financial.set(null);
    this.detailError.set('');
    this.financialError.set('');
    this.actionError.set('');
    this.actionNotice.set('');
    this.confirmCancellation.set(false);
    this.detailLoading.set(false);
    this.financialLoading.set(false);
    this.actionBusy.set(false);
  }

  private async load(id: string, page: number, seq: number, generation: number) {
    this.loading.set(true);
    this.error.set('');
    try {
      const result = await this.api.list(id, page);
      if (!this.current(id, seq, generation)) return;
      this.data.set(result);
      this.currentPage.set(page);
    } catch (error) {
      if (this.current(id, seq, generation)) this.error.set(apiMessage(error));
    } finally {
      if (this.current(id, seq, generation)) this.loading.set(false);
    }
  }

  async page(page: number) {
    const id = this.context.selectedId();
    if (!id || page < 0 || page >= this.totalPages() || this.loading() || this.actionBusy()) return;
    const seq = ++this.request;
    this.clearDetail();
    await this.load(id, page, seq, this.auth.generation());
  }

  async refresh() {
    const id = this.context.selectedId();
    if (!id || this.loading() || this.actionBusy()) return;
    const seq = ++this.request;
    this.clearDetail();
    await this.load(id, this.currentPage(), seq, this.auth.generation());
  }

  async open(order: DirectOrderSummary) {
    const id = this.context.selectedId();
    if (!id || this.loading() || this.actionBusy()) return;
    this.clearDetail();
    const detailSeq = this.detailRequest;
    const listSeq = this.request;
    const generation = this.auth.generation();
    this.detailLoading.set(true);
    this.financialLoading.set(true);
    const [detail, financial] = await Promise.allSettled([
      this.api.get(id, order.id),
      this.api.paymentStatus(id, order.id),
    ]);
    if (!this.currentDetail(id, listSeq, generation, detailSeq)) return;
    if (detail.status === 'fulfilled') this.detail.set(detail.value);
    else this.detailError.set(apiMessage(detail.reason));
    if (financial.status === 'fulfilled') this.financial.set(financial.value);
    else this.financialError.set(apiMessage(financial.reason));
    this.detailLoading.set(false);
    this.financialLoading.set(false);
  }

  close() {
    this.clearDetail();
  }

  async act(action: MerchantAction) {
    const id = this.context.selectedId();
    const order = this.detail()?.order;
    if (!id || !order || this.actionBusy() || this.financialLoading()) return;
    if (action === 'cancel' && !this.canCancel()) return;
    if (action === 'prepare' && !this.canPrepare()) return;
    if (action === 'ready' && !this.canReady()) return;
    if (action === 'cancel' && !this.confirmCancellation()) return;

    const detailSeq = ++this.detailRequest;
    const listSeq = this.request;
    const generation = this.auth.generation();
    this.actionBusy.set(true);
    this.actionError.set('');
    this.actionNotice.set('');
    this.confirmCancellation.set(false);
    try {
      if (action === 'cancel') await this.api.cancel(id, order.id);
      else if (action === 'prepare') await this.api.startPreparation(id, order.id);
      else await this.api.markReady(id, order.id);
      if (!this.currentDetail(id, listSeq, generation, detailSeq)) return;
      this.actionNotice.set(action === 'cancel' ? 'Commande annulée.'
        : action === 'prepare' ? 'Préparation démarrée.' : 'Commande marquée comme prête.');

      // Re-read authoritative state, including the payment risk. Never trust the response
      // to a mutation as an authorization for another step.
      const [detail, financial, list] = await Promise.allSettled([
        this.api.get(id, order.id),
        this.api.paymentStatus(id, order.id),
        this.api.list(id, this.currentPage()),
      ]);
      if (!this.currentDetail(id, listSeq, generation, detailSeq)) return;
      if (detail.status === 'fulfilled') this.detail.set(detail.value);
      else this.detailError.set('Action effectuée, mais le détail n’a pas pu être rechargé.');
      if (financial.status === 'fulfilled') {
        this.financial.set(financial.value);
        this.financialError.set('');
      } else {
        this.financial.set(null);
        this.financialError.set('État financier indisponible : aucune autre action autorisée.');
      }
      if (list.status === 'fulfilled') this.data.set(list.value);
      else this.error.set('Actualisez la liste pour voir le dernier statut.');
    } catch (error) {
      if (this.currentDetail(id, listSeq, generation, detailSeq)) {
        this.actionError.set(apiMessage(error));
        // The server is authoritative; a rejected or ambiguous command must not
        // update the local status or optimistically unlock the next step.
        this.financial.set(null);
        this.financialError.set('Actualisez le détail pour vérifier l’état financier.');
      }
    } finally {
      if (this.currentDetail(id, listSeq, generation, detailSeq)) {
        this.actionBusy.set(false);
      }
    }
  }

  status(value: string) {
    switch (value) {
      case 'PENDING_PAYMENT_INTEGRATION': return 'En attente de paiement';
      case 'CANCELLED': return 'Annulée';
      case 'PAYMENT_CONFIRMED': return 'Paiement confirmé';
      case 'PREPARING': return 'En préparation';
      case 'READY_FOR_PICKUP': return 'Prête pour le retrait';
      case 'READY_FOR_DISPATCH': return 'Prête pour l’expédition';
      default: return 'Statut indisponible';
    }
  }

  financialLabel(value: string) {
    switch (value) {
      case 'NOT_PREPARED': return 'Paiement non préparé';
      case 'PREPARED': return 'Paiement préparé, non confirmé';
      case 'EXPIRED': return 'Demande de paiement expirée';
      case 'UNAVAILABLE': return 'Paiement indisponible';
      case 'PAYMENT_IN_PROGRESS': return 'Paiement en cours chez Bictorys — non confirmé';
      case 'SETTLEMENT_PROCESSING': return 'Vérification du règlement en cours';
      case 'SETTLEMENT_VERIFIED': return 'Règlement vérifié';
      case 'REVIEW_REQUIRED': return 'Vérification manuelle nécessaire';
      default: return 'Statut financier indisponible';
    }
  }

  /** Reuse the customer's saved point. No reverse-geocoding or browser GPS is inferred here. */
  deliveryPoint(address: DirectOrderDetail['deliveryAddress']): DirectGeoPoint | null {
    if (address?.locationMethod !== 'MAP' || address.coverageStatus !== 'REGION_MATCHED'
      || typeof address.latitude !== 'number' || typeof address.longitude !== 'number') return null;
    const point = { latitude: address.latitude, longitude: address.longitude };
    return validDirectGeoPoint(point) ? point : null;
  }

  deliveryMapUrl(address: DirectOrderDetail['deliveryAddress']): string | null {
    const point = this.deliveryPoint(address);
    if (!point) return null;
    const lat = point.latitude.toFixed(6);
    const lon = point.longitude.toFixed(6);
    return `https://www.openstreetmap.org/?mlat=${lat}&mlon=${lon}#map=18/${lat}/${lon}`;
  }

  deliveryDirectionsUrl(address: DirectOrderDetail['deliveryAddress']): string | null {
    const point = this.deliveryPoint(address);
    if (!point) return null;
    return `https://www.google.com/maps/dir/?api=1&destination=${point.latitude.toFixed(6)}%2C${point.longitude.toFixed(6)}`;
  }

  fulfillment(value: string) {
    return value === 'PICKUP' ? 'Retrait en boutique' : 'Livraison prévue';
  }
}
