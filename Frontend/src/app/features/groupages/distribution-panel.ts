import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom, timeout } from 'rxjs';
import { OrderFulfillmentControllerService } from '../../api/generated/services';
import type { FulfillmentBatch, CampaignLifecycleView } from '../../api/generated/models';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { Select } from '../../shared/forms/select';
import { apiCode, apiMessage } from '../../core/http/api-error';
import { DistributionOrder, DistributionAction } from './distribution-order';
import { filterDistribution } from './distribution-labels';
@Component({
  selector: 'g-distribution-panel',
  imports: [FormsModule, Button, Input, Select, DistributionOrder],
  templateUrl: './distribution-panel.html',
  styles: `
    :host {
      display: block;
      min-width: 0;
      margin-top: var(--s6);
    }
    h2,
    p {
      margin-bottom: var(--s3);
    }
    .counts {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 130px), 1fr));
      gap: var(--s3);
      margin: var(--s5) 0;
    }
    .counts div {
      border: 1px solid var(--border);
      background: var(--surface-alt);
      padding: var(--s3);
      border-radius: var(--radius-md);
    }
    .counts strong {
      display: block;
      font-size: var(--text-xl);
    }
    .filters,
    .orders {
      display: grid;
      gap: var(--s4);
      margin: var(--s5) 0;
    }
    .filters {
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr));
    }
    label {
      display: block;
      margin-bottom: var(--s2);
    }
    .orders {
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 420px), 1fr));
      gap:var(--s2);
    }
    .distribution-extra { margin:var(--s2) 0 var(--s4); }
    .distribution-extra summary { cursor:pointer; display:flex; align-items:center; min-height:44px; font-size:var(--text-sm); font-weight:600; color:var(--primary); }
    .distribution-extra summary:after { content:'⌄'; margin-left:auto; font-size:22px; }
    .distribution-extra[open] summary:after { transform:rotate(180deg); }
    .distribution-extra .counts { margin:var(--s2) 0; }
    .distribution-show-all { min-height:44px; margin-top:var(--s2); }
    .distribution-show-all:after { content:'⌄'; padding-left:var(--s2); }
    .distribution-show-all[aria-expanded='true']:after { display:inline-block; transform:rotate(180deg); }
    .counts div { font-size:var(--text-xs); }

  `,
})
export class DistributionPanel {
  readonly organizationId = input.required<string>();
  readonly campaignId = input.required<string>();
  readonly campaignChanged = output<CampaignLifecycleView>();
  readonly data = signal<FulfillmentBatch | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly message = signal('');
  readonly search = signal('');
  readonly filter = signal('all');
  readonly revision = signal(0);
  readonly showAll = signal(false);
  readonly rows = computed(() =>
    filterDistribution(this.data()?.orders ?? [], this.search(), this.filter()),
  );
  readonly visibleRows = computed(() => this.showAll() ? this.rows() : this.rows().slice(0, 4));
  private readonly api = inject(OrderFulfillmentControllerService);
  private sequence = 0;
  constructor() {
    effect(() => {
      this.organizationId();
      this.campaignId();
      void this.load();
    });
  }
  async load(clearError = true) {
    const seq = ++this.sequence;
    this.loading.set(true);
    if (clearError) this.error.set('');
    try {
      const value = await firstValueFrom(
        this.api
          .distributionBatch({
            organizationId: this.organizationId(),
            campaignId: this.campaignId(),
          })
          .pipe(timeout(15000)),
      );
      if (seq === this.sequence) {
        this.data.set(value);
        this.revision.update((v) => v + 1);
        if (value.campaign) this.campaignChanged.emit(value.campaign);
      }
    } catch (e) {
      if (seq === this.sequence) {
        this.data.set(null);
        this.error.set(apiMessage(e));
      }
    } finally {
      if (seq === this.sequence) this.loading.set(false);
    }
  }
  async perform(action: DistributionAction) {
    if (this.busy() || this.loading() || action.row.version == null || !action.row.orderId) return;
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    const seq = this.sequence;
    const params = {
      organizationId: this.organizationId(),
      campaignId: this.campaignId(),
      orderId: action.row.orderId,
    };
    try {
      const request =
        action.type === 'revoke'
          ? this.api.revokeOrderReadiness({
              ...params,
              body: { version: action.row.version, reason: action.reason! },
            })
          : action.type === 'ready'
            ? this.api.markOrderReady({
                ...params,
                body: { version: action.row.version, confirmed: true },
              })
            : this.api.confirmOrderPickup({
                ...params,
                body: { version: action.row.version, confirmed: true },
              });
      const value = await firstValueFrom(request.pipe(timeout(15000)));
      if (seq === this.sequence) {
        this.data.set(value);
        this.revision.update((v) => v + 1);
        if (value.campaign) this.campaignChanged.emit(value.campaign);
        this.message.set(
          action.type === 'ready'
            ? 'La commande est disponible pour retrait.'
            : action.type === 'revoke'
              ? 'La commande est de nouveau à préparer.'
              : 'La remise de la commande est enregistrée.',
        );
      }
    } catch (e) {
      if (seq === this.sequence) {
        this.error.set(
          apiCode(e) === 'ORDER_VERSION_CONFLICT'
            ? 'Cette commande a été modifiée par une autre personne. Les données ont été rechargées ; vérifiez-les avant de confirmer à nouveau.'
            : apiMessage(e),
        );
        await this.load(false);
      }
    } finally {
      this.busy.set(false);
    }
  }
}
