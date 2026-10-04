import { ShippingSettlement } from '../../shared/ui/shipping-settlement';
import { logisticsSourceLabel } from './logistics-labels';
import {
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription, timeout } from 'rxjs';
import {
  OrderShippingRow,
  ShippingAssessmentView,
  ShippingExplanation as Explanation,
  FinalShipping,
  MeasurementInput,
} from '../../api/generated/models';
import { OrderShippingControllerService } from '../../api/generated/services';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { Select } from '../../shared/forms/select';
import { ShippingExplanation } from '../../shared/ui/shipping-explanation';
import { LogisticsSourceInput } from './logistics-source';
import {
  currentCalculation,
  currentMeasurement,
  variance,
  SHIPPING_VARIANCE_WARNING_PERCENT,
} from './shipping-presentation';
import { money, dateLabel } from '../../shared/utils/display';
import { apiMessage, apiCode } from '../../core/http/api-error';
@Component({
  selector: 'g-shipping-order-editor',
  imports: [
    ShippingSettlement,
    FormsModule,
    DecimalPipe,
    Button,
    Input,
    Select,
    ShippingExplanation,
    LogisticsSourceInput,
  ],
  templateUrl: './shipping-order-editor.html',
  styleUrl: './shipping-order-editor.scss',
})
export class ShippingOrderEditor {
  readonly row = input.required<OrderShippingRow>();
  readonly organizationId = input.required<string>();
  readonly campaignId = input.required<string>();
  readonly changed = output<ShippingAssessmentView>();
  readonly busyChange = output<boolean>();
  readonly assessment = computed(() => this.row().assessment);
  readonly final = computed(() => currentCalculation(this.assessment()));
  readonly current = computed(() => currentMeasurement(this.assessment()));
  readonly terms = computed(() => this.assessment()?.snapshot?.terms);
  readonly automatic = computed(() =>
    ['PER_ITEM', 'FIXED'].includes(this.terms()?.shippingPricingMethod ?? ''),
  );
  readonly editing = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly stale = signal(false);
  readonly preview = signal<Explanation | null>(null);
  private previewBody: FinalShipping | null = null;
  readonly abs = Math.abs;
  readonly measureDirty = signal(false);
  readonly sourceLabel = logisticsSourceLabel;
  readonly money = money;
  readonly dateLabel = dateLabel;
  readonly variance = variance;
  readonly threshold = inject(SHIPPING_VARIANCE_WARNING_PERCENT);
  readonly fields = [
    { key: 'actualWeightKg', label: 'Poids réel (kg)' },
    { key: 'chargeableWeightKg', label: 'Poids facturable communiqué (kg)' },
    { key: 'volumeCbm', label: 'Volume communiqué (m³)' },
    { key: 'lengthCm', label: 'Longueur (cm)' },
    { key: 'widthCm', label: 'Largeur (cm)' },
    { key: 'heightCm', label: 'Hauteur (cm)' },
  ] as const;
  info: MeasurementInput = {};
  origin: FinalShipping['origin'] = 'CALCULATED';
  amount: number | null = null;
  source: NonNullable<MeasurementInput['source']> = 'UNKNOWN';
  occurred = '';
  note = '';
  reason = '';
  private readonly api = inject(OrderShippingControllerService);
  private request?: Subscription;
  constructor() {
    inject(DestroyRef).onDestroy(() => this.request?.unsubscribe());
    effect(() => {
      this.row();
      this.invalidate();
      this.stale.set(false);
    });
  }
  state() {
    if (
      this.final()?.measurementId &&
      this.final()?.measurementId !== this.assessment()?.currentMeasurementId
    )
      return 'Correction à finaliser';
    return this.final()
      ? this.final()!.revision! > 1
        ? 'Corrigé'
        : 'Finalisé'
      : this.automatic() || this.current()
        ? 'Prêt à vérifier'
        : 'À renseigner';
  }
  begin() {
    this.editing.set(true);
    this.busyChange.emit(true);
    this.info = { ...this.current() };
    this.measureDirty.set(false);
    this.origin = this.final()?.explanation?.origin ?? 'CALCULATED';
    this.amount = this.final()?.explanation?.amount ?? null;
    this.source = this.info.source ?? this.final()?.explanation?.source ?? 'UNKNOWN';
    this.occurred = '';
    this.note = '';
    this.reason = '';
    this.invalidate();
  }
  close() {
    if (this.busy()) return;
    this.editing.set(false);
    this.busyChange.emit(false);
    this.invalidate();
  }
  invalidate() {
    this.preview.set(null);
    this.previewBody = null;
  }
  showField(key: string) {
    return (
      ['lengthCm', 'widthCm', 'heightCm'].includes(key) ||
      (this.terms()?.shippingPricingMethod === 'PER_CBM'
        ? key === 'volumeCbm'
        : ['actualWeightKg', 'chargeableWeightKg'].includes(key))
    );
  }
  physicalReady() {
    return (
      this.fields.some((f) => this.info[f.key] != null) &&
      this.fields.every(
        (f) =>
          this.info[f.key] == null || (Number.isFinite(this.info[f.key]) && this.info[f.key]! > 0),
      )
    );
  }
  private context() {
    return {
      organizationId: this.organizationId(),
      campaignId: this.campaignId(),
      orderId: this.row().orderId!,
    };
  }
  private metadata() {
    return {
      source: this.source,
      occurredAt: this.occurred ? new Date(this.occurred).toISOString() : undefined,
      note: this.note.trim() || undefined,
      correctionReason: this.reason.trim() || undefined,
    };
  }
  private validDate() {
    if (
      this.occurred &&
      (!Number.isFinite(Date.parse(this.occurred)) || Date.parse(this.occurred) > Date.now())
    ) {
      this.error.set('La date communiquée doit être passée.');
      return false;
    }
    return true;
  }
  saveMeasure() {
    if (this.busy() || this.stale() || !this.physicalReady() || !this.validDate()) return;
    this.invalidate();
    this.busy.set(true);
    this.error.set('');
    const information: MeasurementInput = {
      ...this.info,
      ...this.metadata(),
      supersedesMeasurementId: this.assessment()?.currentMeasurementId,
    };
    this.request = this.api
      .recordShippingMeasurement({
        ...this.context(),
        body: { version: this.assessment()?.version ?? 0, information },
      })
      .pipe(timeout(15000))
      .subscribe({
        next: (a) => {
          this.measureDirty.set(false);
          this.changed.emit(a);
          this.busy.set(false);
          this.error.set('');
        },
        error: (e) => this.failed(e),
      });
  }
  showPreview() {
    if (
      this.busy() ||
      this.stale() ||
      !this.validDate() ||
      (this.origin === 'CALCULATED' && !this.automatic() && this.measureDirty())
    )
      return;
    if (
      this.origin === 'PROVIDER_QUOTED' &&
      (this.amount == null || !Number.isSafeInteger(this.amount) || this.amount < 0)
    ) {
      this.error.set(
        'Saisissez un montant entier positif ou nul, représentable sans perte de précision.',
      );
      return;
    }
    this.invalidate();
    const body: FinalShipping = {
      version: this.assessment()?.version ?? 0,
      origin: this.origin,
      ...this.metadata(),
      currency: this.terms()?.shippingCurrency,
      quotedAmount: this.origin === 'PROVIDER_QUOTED' ? (this.amount ?? undefined) : undefined,
      measurementId:
        this.origin === 'CALCULATED' && !this.automatic()
          ? this.assessment()?.currentMeasurementId
          : undefined,
    };
    this.busy.set(true);
    this.error.set('');
    this.request = this.api
      .previewShipping({ ...this.context(), body })
      .pipe(timeout(15000))
      .subscribe({
        next: (v) => {
          if (v.amount == null || !Number.isSafeInteger(v.amount) || v.amount < 0) {
            this.error.set(
              'Ce montant ne peut pas être affiché avec une précision suffisante. La finalisation est indisponible.',
            );
            this.busy.set(false);
            return;
          }
          this.previewBody = body;
          this.preview.set(v);
          this.busy.set(false);
        },
        error: (e) => this.failed(e),
      });
  }
  finalize() {
    if (this.busy() || this.stale() || !this.previewBody) return;
    this.busy.set(true);
    this.error.set('');
    this.request = this.api
      .finalizeShipping({ ...this.context(), body: this.previewBody })
      .pipe(timeout(15000))
      .subscribe({
        next: (a) => {
          this.changed.emit(a);
          this.busy.set(false);
          this.editing.set(false);
          this.busyChange.emit(false);
          this.invalidate();
        },
        error: (e) => this.failed(e),
      });
  }
  private failed(e: unknown) {
    this.busy.set(false);
    this.invalidate();
    const status = (e as { status?: number }).status;
    if (
      ['SHIPPING_VERSION_CONFLICT', 'SHIPPING_MEASUREMENT_STALE'].includes(apiCode(e)) ||
      status === 0 ||
      status === 500 ||
      status === undefined
    ) {
      this.stale.set(true);
      this.error.set(
        'Les données doivent être actualisées avant de réessayer. Fermez la saisie puis actualisez les commandes.',
      );
    } else this.error.set(apiMessage(e));
  }
}
