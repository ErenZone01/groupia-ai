import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { Subscription, timeout } from 'rxjs';
import { DecimalPipe } from '@angular/common';
import { OrderShippingControllerService } from '../../api/generated/services';
import { OrderShippingRow, ShippingAssessmentView } from '../../api/generated/models';
import { Button } from '../../shared/ui/button';
import { ShippingOrderEditor } from './shipping-order-editor';
import { shippingTotals } from './shipping-presentation';
import { money } from '../../shared/utils/display';
@Component({
  selector: 'g-shipping-batch',
  imports: [Button, ShippingOrderEditor, DecimalPipe],
  template: ` <section aria-labelledby="shipping-batch-title">
    <h2 id="shipping-batch-title">Transport des commandes</h2>
    <p class="muted">Frais et finalisation des commandes.</p>
    @if (loading()) {
      <p role="status">Chargement du transport…</p>
    }
    @if (error()) {
      <p role="alert" class="form-error">{{ error() }}</p>
    }
    <button gButton variant="secondary" (click)="reload()" [disabled]="loading() || editing()">
      Actualiser les commandes
    </button>
    @if (!loading() && !error()) {
      @let t = totals();
      <div class="summary" aria-label="Totaux de contrôle">
        <p>
          <strong>{{ t.count }}</strong> commandes concernées
        </p>
        <p>
          <strong>{{ t.finalized }}</strong> finalisées · {{ t.remaining }} restantes
        </p>
        <p>Transport final : {{ money(t.amount.value) }} ({{ t.amount.count }} commandes)</p>
        <details class="shipping-stat-details"><summary>Poids et volumes</summary>
        @if (t.weight.count) {
          <p>
            Poids réel : {{ t.weight.value | number: '1.0-9' }} kg ({{ t.weight.count }}/{{
              t.count
            }})
          </p>
        }
        @if (t.chargeable.count) {
          <p>
            Poids facturable : {{ t.chargeable.value | number: '1.0-9' }} kg ({{
              t.chargeable.count
            }}/{{ t.count }})
          </p>
        }
        @if (t.volume.count) {
          <p>Volume : {{ t.volume.value | number: '1.0-9' }} m³ ({{ t.volume.count }}/{{ t.count }})</p>
        }
        </details>
      </div>
      <p class="muted">Montants renseignés uniquement.</p>
      @for (row of visibleRows(); track row.orderId) {
        <g-shipping-order-editor
          [row]="row"
          [readOnly]="readOnly()"
          [organizationId]="organizationId()"
          [campaignId]="campaignId()"
          (changed)="apply(row.orderId!, $event)"
          (busyChange)="setEditing(row.orderId!, $event)"
        />
      } @empty {
        <p>Aucune commande confirmée à traiter.</p>
      }
      @if (rows().length > 4) {
        <button gButton variant="secondary" class="shipping-show-all" [attr.aria-expanded]="showAll()"
                (click)="showAll.set(!showAll())">
          {{ showAll() ? 'Réduire la liste' : 'Voir toutes les commandes (' + rows().length + ')' }}
        </button>
      }
    }
  </section>`,
  styles: `
    :host {
      display: block;
      margin-top: var(--s6);
    }
    section {
      border-top: 1px solid var(--border);
      padding-top: var(--s5);
    }
    h2,
    p {
      margin-bottom: var(--s3);
    }
    .shipping-stat-details { grid-column:1/-1; }
    .shipping-stat-details summary { cursor:pointer; min-height:44px; display:flex; align-items:center; font-weight:600; }
    .shipping-show-all { margin-top:var(--s3); min-height:44px; }
    .summary {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 200px), 1fr));
      gap: var(--s3);
      padding: var(--s4);
      margin-top: var(--s4);
      background: var(--surface-alt);
      border-radius: var(--radius-md);
    }
  `,
})
export class ShippingBatch {
  readonly readOnly = input(false);
  readonly organizationId = input.required<string>();
  readonly campaignId = input.required<string>();
  readonly rows = signal<OrderShippingRow[]>([]);
  readonly showAll = signal(false);
  readonly visibleRows = computed(() => this.showAll() ? this.rows() : this.rows().slice(0, 4));
  readonly loading = signal(true);
  readonly error = signal('');
  readonly editing = signal(false);
  private readonly editors = new Set<string>();
  setEditing(id: string, value: boolean) {
    if (value) this.editors.add(id);
    else this.editors.delete(id);
    this.editing.set(this.editors.size > 0);
  }
  readonly totals = computed(() => shippingTotals(this.rows()));
  readonly money = money;
  private readonly api = inject(OrderShippingControllerService);
  private request?: Subscription;
  constructor() {
    effect(() => {
      this.organizationId();
      this.campaignId();
      this.reload();
    });
    inject(DestroyRef).onDestroy(() => this.request?.unsubscribe());
  }
  reload() {
    this.request?.unsubscribe();
    this.showAll.set(false);
    this.loading.set(true);
    this.error.set('');
    this.request = this.api
      .orderShippingBatch({ organizationId: this.organizationId(), campaignId: this.campaignId() })
      .pipe(timeout(15000))
      .subscribe({
        next: (rows) => {
          this.rows.set(rows);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Impossible de lire le transport. Actualisez pour réessayer.');
        },
      });
  }
  apply(id: string, assessment: ShippingAssessmentView) {
    this.rows.update((rows) =>
      rows.map((r) => (r.orderId === id ? { ...r, assessment, shippingSettlement: undefined } : r)),
    );
    // Read the server position after the atomic finalization; never calculate money in the browser.
    this.request?.unsubscribe();
    this.request = this.api
      .orderShippingBatch({ organizationId: this.organizationId(), campaignId: this.campaignId() })
      .pipe(timeout(15000))
      .subscribe({
        next: (rows) =>
          this.rows.update((current) =>
            current.map((row) =>
              row.orderId === id ? (rows.find((r) => r.orderId === id) ?? row) : row,
            ),
          ),
        error: () =>
          this.error.set(
            'Le transport est enregistré. Actualisez pour relire sa position financière.',
          ),
      });
  }
}
