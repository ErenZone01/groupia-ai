import { Component, effect, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  AddShipmentFact,
  ShipmentView,
  UpdateShipment,
  UpdateShipmentEta,
  ShipCampaign,
} from '../../api/generated/models';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { Select } from '../../shared/forms/select';
import { LogisticsSourceInput } from './logistics-source';
import { logisticsFactOptions } from './logistics-labels';
export type LogisticsEdit =
  | { action: 'dossier'; body: UpdateShipment }
  | { action: 'eta'; body: UpdateShipmentEta }
  | { action: 'fact'; body: AddShipmentFact };
@Component({
  selector: 'g-logistics-details-editor',
  imports: [FormsModule, Button, Input, Select, LogisticsSourceInput],
  templateUrl: './logistics-details-editor.html',
})
export class LogisticsDetailsEditor {
  readonly mode = input.required<'dossier' | 'eta' | 'fact'>();
  readonly shipment = input<ShipmentView | null>(null);
  readonly version = input.required<number>();
  readonly disabled = input(false);
  readonly busy = input(false);
  readonly save = output<LogisticsEdit>();
  readonly dismissed = output<void>();
  readonly options = logisticsFactOptions;
  providerName = '';
  providerContact = '';
  reference = '';
  note = '';
  expected = '';
  occurred = '';
  source: NonNullable<ShipCampaign['source']> = 'UNKNOWN';
  type: NonNullable<AddShipmentFact['type']> = 'INFORMATION';
  error = '';
  constructor() {
    effect(() => {
      const s = this.shipment();
      const mode = this.mode();
      this.providerName = s?.providerName ?? '';
      this.providerContact = s?.providerContact ?? '';
      this.reference = s?.trackingReference ?? '';
      this.note = mode === 'dossier' ? (s?.note ?? '') : '';
      this.expected = s?.expectedArrivalAt ? this.local(s.expectedArrivalAt) : '';
      this.occurred = '';
      this.source = 'UNKNOWN';
      this.error = '';
    });
  }
  private local(value: string) {
    const d = new Date(value);
    return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  }
  submit() {
    if (this.disabled() || this.busy()) return;
    this.error = '';
    const base = {
      version: this.version(),
      source: this.source,
      note: this.note.trim() || undefined,
    };
    if (this.mode() === 'dossier') {
      this.save.emit({
        action: 'dossier',
        body: {
          ...base,
          providerName: this.providerName.trim() || undefined,
          providerContact: this.providerContact.trim() || undefined,
          trackingReference: this.reference.trim() || undefined,
        },
      });
    } else if (this.mode() === 'eta') {
      const t = this.expected ? Date.parse(this.expected) : undefined;
      if (
        t !== undefined &&
        (!Number.isFinite(t) ||
          (this.shipment()?.departedAt && t < Date.parse(this.shipment()!.departedAt!)))
      ) {
        this.error = 'Vérifiez l’arrivée estimée : elle ne doit pas précéder le départ connu.';
        return;
      }
      this.save.emit({
        action: 'eta',
        body: {
          ...base,
          expectedArrivalAt: t === undefined ? undefined : new Date(t).toISOString(),
        },
      });
    } else {
      const t = this.occurred ? Date.parse(this.occurred) : undefined;
      if (t !== undefined && (!Number.isFinite(t) || t > Date.now())) {
        this.error = 'La date communiquée ne peut pas être future.';
        return;
      }
      if (this.type === 'INFORMATION' && !base.note) {
        this.error = 'Précisez l’information ou la correction dans la note.';
        return;
      }
      this.save.emit({
        action: 'fact',
        body: {
          ...base,
          type: this.type,
          occurredAt: t === undefined ? undefined : new Date(t).toISOString(),
        },
      });
    }
  }
}
