import { Component, DestroyRef, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import {
  CampaignView,
  CampaignLifecycleView,
  CampaignShipmentView,
  ShipmentView,
} from '../../api/generated/models';
import { LogisticsApi } from './logistics-api';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { Skeleton } from '../../shared/feedback/skeleton';
import { apiCode, apiMessage } from '../../core/http/api-error';
import { dateLabel, statusLabel } from '../../shared/utils/display';
import { optionLabel, transportOptions } from './groupage-labels';

@Component({
  selector: 'g-logistics-panel',
  imports: [FormsModule, Button, Input, Skeleton],
  templateUrl: './logistics-panel.html',
  styles: `
    :host {
      display: block;
      margin-top: var(--s6);
    }
    .logistics {
      padding: var(--s5);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      background: var(--surface);
    }
    h2,
    p {
      margin-bottom: var(--s4);
    }
    dl {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: var(--s3);
      margin-bottom: var(--s5);
    }
    dt {
      color: var(--muted);
      font-size: var(--text-sm);
    }
    dd {
      margin: 0;
      overflow-wrap: anywhere;
    }
    form {
      margin-top: var(--s5);
    }
    .actions {
      flex-wrap: wrap;
    }
    button {
      white-space: normal;
    }
  `,
})
export class LogisticsPanel {
  readonly campaign = input.required<CampaignView>();
  readonly organizationId = input.required<string>();
  readonly campaignChanged = output<CampaignLifecycleView>();
  private readonly api = inject(LogisticsApi);
  private readonly destroy = inject(DestroyRef);
  readonly lifecycle = signal<CampaignLifecycleView | null>(null);
  readonly shipment = signal<ShipmentView | null>(null);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly stale = signal(true);
  readonly error = signal('');
  readonly editing = signal(false);
  readonly confirmingArrival = signal(false);
  readonly dateLabel = dateLabel;
  readonly statusLabel = statusLabel;
  readonly optionLabel = optionLabel;
  readonly transportOptions = transportOptions;
  departure = '';
  expected = '';
  reference = '';
  note = '';
  private context = '';
  private request?: Subscription;
  constructor() {
    effect(() => {
      const key = this.organizationId() + '/' + this.campaign().id;
      if (key !== this.context) {
        this.context = key;
        this.departure = this.expected = this.reference = this.note = '';
        this.request?.unsubscribe();
        this.busy.set(false);
        this.editing.set(false);
        this.confirmingArrival.set(false);
        this.shipment.set(null);
        this.lifecycle.set(null);
        this.reload();
      }
    });
    this.destroy.onDestroy(() => this.request?.unsubscribe());
  }
  reload() {
    if (this.busy()) return;
    this.request?.unsubscribe();
    this.loading.set(true);
    this.stale.set(true);
    this.error.set('');
    this.request = this.api.read(this.organizationId(), this.campaign().id!).subscribe({
      next: (value) => {
        this.apply(value);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(
          'La logistique n’a pas pu être actualisée. Réessayez avant toute modification.',
        );
        this.loading.set(false);
      },
    });
  }
  private apply(value: CampaignShipmentView) {
    const c = value.campaign;
    if (
      !c ||
      c.id !== this.campaign().id ||
      c.organizationId !== this.organizationId() ||
      !Number.isSafeInteger(c.version)
    ) {
      this.error.set('Les informations reçues ne correspondent pas à ce groupage.');
      this.stale.set(true);
      return;
    }
    this.lifecycle.set(c);
    this.shipment.set(value.shipment ?? null);
    this.stale.set(false);
    this.campaignChanged.emit(c);
  }
  begin() {
    if (this.busy() || this.stale() || this.lifecycle()?.status !== 'SUPPLIER_ORDERED') return;
    const now = new Date();
    this.departure = new Date(now.getTime() - now.getTimezoneOffset() * 60000)
      .toISOString()
      .slice(0, 16);
    this.error.set('');
    this.editing.set(true);
  }
  ship() {
    if (!this.editing() || !this.canMutate('SUPPLIER_ORDERED')) return;
    const departed = Date.parse(this.departure);
    const expected = this.expected ? Date.parse(this.expected) : undefined;
    if (
      !Number.isFinite(departed) ||
      departed > Date.now() ||
      (expected !== undefined && (!Number.isFinite(expected) || expected < departed)) ||
      this.reference.length > 160 ||
      this.note.length > 2000
    ) {
      this.error.set(
        'Vérifiez les dates : le départ doit être passé et l’arrivée prévue doit le suivre.',
      );
      return;
    }
    this.mutate('ship', {
      version: this.lifecycle()!.version!,
      departedAt: new Date(departed).toISOString(),
      expectedArrivalAt: expected === undefined ? undefined : new Date(expected).toISOString(),
      trackingReference: this.reference.trim() || undefined,
      note: this.note.trim() || undefined,
    });
  }
  arrive() {
    if (!this.confirmingArrival() || !this.canMutate('IN_TRANSIT')) return;
    this.mutate('arrive', { version: this.lifecycle()!.version! });
  }
  private canMutate(status: string) {
    return !this.busy() && !this.loading() && !this.stale() && this.lifecycle()?.status === status;
  }
  private mutate(
    action: 'ship' | 'arrive',
    body:
      | import('../../api/generated/models').ShipCampaign
      | import('../../api/generated/models').ArriveCampaign,
  ) {
    this.busy.set(true);
    this.error.set('');
    const call =
      action === 'ship'
        ? this.api.ship(
            this.organizationId(),
            this.campaign().id!,
            body as import('../../api/generated/models').ShipCampaign,
          )
        : this.api.arrive(this.organizationId(), this.campaign().id!, body);
    this.request = call.subscribe({
      next: (value) => {
        this.apply(value);
        this.busy.set(false);
        this.editing.set(false);
        this.confirmingArrival.set(false);
      },
      error: (e) => {
        this.busy.set(false);
        this.stale.set(true);
        this.error.set(
          apiCode(e) === 'GROUPING_CAMPAIGN_VERSION_CONFLICT'
            ? 'Ce groupage a changé. Actualisez la logistique avant de réessayer.'
            : apiMessage(e),
        );
      },
    });
  }
}
