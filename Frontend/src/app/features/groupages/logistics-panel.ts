import { ShippingBatch } from './shipping-batch';
import {
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable, Subscription } from 'rxjs';
import {
  CampaignView,
  CampaignLifecycleView,
  CampaignShipmentView,
  ShipmentView,
} from '../../api/generated/models';
import { LogisticsDetailsEditor, LogisticsEdit } from './logistics-details-editor';
import { LogisticsTimeline } from './logistics-timeline';
import { LogisticsSourceInput } from './logistics-source';
import { ShipCampaign } from '../../api/generated/models';
import { LogisticsApi } from './logistics-api';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { Skeleton } from '../../shared/feedback/skeleton';
import { apiCode, apiMessage } from '../../core/http/api-error';
import { dateLabel, statusLabel } from '../../shared/utils/display';
import { optionLabel, transportOptions } from './groupage-labels';

@Component({
  selector: 'g-logistics-panel',
  imports: [
    ShippingBatch,
    FormsModule,
    Button,
    Input,
    Skeleton,
    LogisticsDetailsEditor,
    LogisticsTimeline,
    LogisticsSourceInput,
  ],
  templateUrl: './logistics-panel.html',
  styles: `
    :host { display:block; min-width:0; }
    .transport-workspace { display:grid; grid-template-columns:minmax(0,1.65fr) minmax(290px,1fr); gap:var(--s4); align-items:start; }
    .transport-workspace > * { min-width:0; }
    @media(max-width:900px) { .transport-workspace { grid-template-columns:minmax(0,1fr); } }
    .transport-side { background:var(--surface); border:1px solid var(--border); border-radius:var(--radius-lg); padding:var(--s4); }
    .transport-side h2 { margin:0 0 var(--s2); font-size:var(--text-lg); }
    .transport-side p { font-size:var(--text-sm); margin-bottom:var(--s3); }
    .transport-batch-toggle { display:flex; align-items:center; justify-content:space-between; gap:var(--s2); width:100%; }
    .toggle-caret { font-size:23px; line-height:1; transition:transform .15s ease; }
    .transport-batch-toggle[aria-expanded='true'] .toggle-caret { transform:rotate(180deg); }
    .logistics > button[gButton] { margin-block:var(--s2); }

    .logistics {
      padding: var(--s4);
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
    button { white-space:normal; min-height:44px; }
    .logistics-summary { display:grid; grid-template-columns:repeat(auto-fit,minmax(min(100%,180px),1fr)); gap:var(--s2); margin:var(--s3) 0; }
    .logistics-summary div { padding:var(--s3); border:1px solid var(--border); border-radius:var(--radius-md); background:var(--surface-alt); }
    .logistics-summary dt { color:var(--muted); font-size:var(--text-xs); }
    .logistics-summary dd { font-weight:650; font-size:var(--text-sm); margin:var(--s1) 0 0; }
    .logistics-actions { display:flex; flex-wrap:wrap; gap:var(--s2); margin:var(--s3) 0; }
    .logistics-more { background:var(--surface-alt); border:1px solid var(--border); border-radius:var(--radius-md); margin-top:var(--s3); padding:0 var(--s3); min-width:0; }
    .logistics-more summary { list-style:none; cursor:pointer; min-height:50px; display:flex; align-items:center; gap:var(--s2); font-size:var(--text-sm); font-weight:650; color:var(--primary); }
    .logistics-more summary::-webkit-details-marker { display:none; }
    .logistics-more summary:after { content:'⌄'; font-size:23px; margin-left:auto; transition:transform .15s ease; }
    .logistics-more[open] summary:after { transform:rotate(180deg); }
    .logistics-more summary:focus-visible { outline:2px solid var(--primary); outline-offset:2px; }
    .logistics-more[open] { padding-bottom:var(--s3); }
    .logistics-more dl { margin-top:var(--s3); }
    .transport-batch-toggle { margin-top:var(--s3); }

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
  readonly shippingExpanded = signal(false);
  readonly moreDetails = signal(false);
  readonly confirmingArrival = signal(false);
  readonly dateLabel = dateLabel;
  readonly statusLabel = statusLabel;
  readonly optionLabel = optionLabel;
  readonly transportOptions = transportOptions;
  readonly detailsMode = signal<'dossier' | 'eta' | 'fact' | null>(null);
  source: NonNullable<ShipCampaign['source']> = 'UNKNOWN';
  arrival = '';
  departure = '';
  expected = '';
  reference = '';
  note = '';
  private context = '';
  private request?: Subscription;
  constructor() {
    effect(() => {
      const incoming = this.campaign();
      const local = untracked(this.lifecycle);
      const key = this.organizationId() + '/' + incoming.id;
      if (key !== this.context || (local && (incoming.version ?? 0) > (local.version ?? 0))) {
        this.context = key;
        this.departure = this.expected = this.reference = this.note = this.arrival = '';
        this.source = 'UNKNOWN';
        this.detailsMode.set(null);
        this.request?.unsubscribe();
        this.busy.set(false);
        this.editing.set(false);
        this.shippingExpanded.set(false);
        this.moreDetails.set(false);
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
    this.departure = '';
    this.source = 'UNKNOWN';
    this.detailsMode.set(null);
    this.error.set('');
    this.editing.set(true);
  }
  ship() {
    if (!this.editing() || !this.canMutate('SUPPLIER_ORDERED')) return;
    const departed = this.departure ? Date.parse(this.departure) : undefined;
    const expected = this.expected ? Date.parse(this.expected) : undefined;
    if (
      (departed !== undefined && (!Number.isFinite(departed) || departed > Date.now())) ||
      (expected !== undefined &&
        (!Number.isFinite(expected) || (departed !== undefined && expected < departed))) ||
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
      departedAt: departed === undefined ? undefined : new Date(departed).toISOString(),
      source: this.source,
      expectedArrivalAt: expected === undefined ? undefined : new Date(expected).toISOString(),
      trackingReference: this.reference.trim() || undefined,
      note: this.note.trim() || undefined,
    });
  }
  arrive() {
    if (!this.confirmingArrival() || !this.canMutate('IN_TRANSIT')) return;
    const at = this.arrival ? Date.parse(this.arrival) : undefined;
    if (
      at !== undefined &&
      (!Number.isFinite(at) ||
        at > Date.now() ||
        (this.shipment()?.departedAt && at < Date.parse(this.shipment()!.departedAt!)))
    ) {
      this.error.set('Vérifiez les dates de départ et d’arrivée.');
      return;
    }
    this.mutate('arrive', {
      version: this.lifecycle()!.version!,
      arrivedAt: at === undefined ? undefined : new Date(at).toISOString(),
      source: this.source,
      note: this.note.trim() || undefined,
    });
  }
  beginDetails(mode: 'dossier' | 'eta' | 'fact') {
    if (this.busy() || this.stale()) return;
    this.editing.set(false);
    this.confirmingArrival.set(false);
    this.detailsMode.set(mode);
  }
  beginArrival() {
    if (!this.canMutate('IN_TRANSIT')) return;
    this.detailsMode.set(null);
    this.arrival = '';
    this.note = '';
    this.source = 'UNKNOWN';
    this.confirmingArrival.set(true);
  }
  saveDetails(edit: LogisticsEdit) {
    if (
      this.busy() ||
      this.loading() ||
      this.stale() ||
      !['SUPPLIER_ORDERED', 'IN_TRANSIT', 'ARRIVED'].includes(this.lifecycle()?.status ?? '')
    )
      return;
    const org = this.organizationId(),
      id = this.campaign().id!;
    this.perform(
      edit.action === 'dossier'
        ? this.api.enrich(org, id, edit.body)
        : edit.action === 'eta'
          ? this.api.estimate(org, id, edit.body)
          : this.api.addFact(org, id, edit.body),
    );
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
    this.perform(call);
  }
  private perform(call: Observable<CampaignShipmentView>) {
    this.busy.set(true);
    this.error.set('');
    this.request = call.subscribe({
      next: (value) => {
        this.apply(value);
        this.busy.set(false);
        this.editing.set(false);
        this.confirmingArrival.set(false);
        this.detailsMode.set(null);
        this.note = '';
        this.source = 'UNKNOWN';
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
