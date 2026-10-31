import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { apiMessage } from '../../core/http/api-error';
import { money, variantLabel } from '../../shared/utils/display';
import { DirectOrderDetail, DirectOrderSummary, DirectOrdersApi, DirectOrdersPage } from './direct-orders.api';

@Component({
  selector: 'g-direct-orders-page',
  imports: [RouterLink, DatePipe],
  templateUrl: './direct-orders.page.html',
  styleUrl: './direct-orders.page.scss',
})
export class DirectOrdersPage {
  private readonly api = inject(DirectOrdersApi);
  private readonly auth = inject(AuthService);
  readonly context = inject(BoutiqueContextService);
  readonly data = signal<DirectOrdersPage | null>(null);
  readonly detail = signal<DirectOrderDetail | null>(null);
  readonly loading = signal(false);
  readonly detailLoading = signal(false);
  readonly error = signal('');
  readonly detailError = signal('');
  readonly money = money;
  readonly variantLabel = variantLabel;
  readonly currentPage = signal(0);
  readonly totalPages = computed(() => {
    const page = this.data();
    return page ? Math.ceil(page.total / page.size) : 0;
  });
  private request = 0;
  private detailRequest = 0;

  constructor() {
    effect(() => {
      const organizationId = this.context.selectedId();
      const generation = this.auth.generation();
      untracked(() => {
        const seq = ++this.request;
        this.detailRequest++;
        this.data.set(null);
        this.detail.set(null);
        this.error.set('');
        this.detailError.set('');
        this.currentPage.set(0);
        this.detailLoading.set(false);
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
    if (!id || page < 0 || page >= this.totalPages() || this.loading()) return;
    const seq = ++this.request;
    this.detailRequest++;
    this.detail.set(null);
    this.detailError.set('');
    await this.load(id, page, seq, this.auth.generation());
  }

  async refresh() {
    const id = this.context.selectedId();
    if (!id || this.loading()) return;
    const seq = ++this.request;
    this.detailRequest++;
    this.detail.set(null);
    this.detailError.set('');
    await this.load(id, this.currentPage(), seq, this.auth.generation());
  }

  async open(order: DirectOrderSummary) {
    const id = this.context.selectedId();
    if (!id || this.loading()) return;
    const seq = ++this.detailRequest;
    const listSeq = this.request;
    const generation = this.auth.generation();
    this.detail.set(null);
    this.detailError.set('');
    this.detailLoading.set(true);
    try {
      const result = await this.api.get(id, order.id);
      if (this.current(id, listSeq, generation) && seq === this.detailRequest) {
        this.detail.set(result);
      }
    } catch (error) {
      if (this.current(id, listSeq, generation) && seq === this.detailRequest) {
        this.detailError.set(apiMessage(error));
      }
    } finally {
      if (this.current(id, listSeq, generation) && seq === this.detailRequest) {
        this.detailLoading.set(false);
      }
    }
  }

  close() {
    this.detailRequest++;
    this.detail.set(null);
    this.detailError.set('');
    this.detailLoading.set(false);
  }

  status(value: string) {
    return value === 'PENDING_PAYMENT_INTEGRATION'
      ? 'Paiement non disponible'
      : 'Statut : ' + value;
  }

  fulfillment(value: string) {
    return value === 'PICKUP' ? 'Retrait à convenir' : 'Livraison à organiser';
  }
}
