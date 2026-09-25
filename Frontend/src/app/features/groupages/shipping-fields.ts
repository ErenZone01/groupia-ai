import { Component, model } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ShippingInput } from '../../api/generated/models';
import { Input } from '../../shared/forms/input';
import { Select } from '../../shared/forms/select';
import { collectionOptions, pricingOptions, transportOptions } from './groupage-labels';
export function defaultShipping(): ShippingInput {
  return {
    transportMode: 'AIR',
    shippingPricingMethod: 'PER_KG',
    shippingCollectionMode: 'AFTER_FINAL_ASSESSMENT',
    shippingCurrency: 'XOF',
    roundingRule: 'HALF_UP',
  };
}
export function validShipping(value: ShippingInput) {
  const rate = value.shippingRate;
  return (
    !!value.transportMode &&
    !!value.shippingPricingMethod &&
    !!value.shippingCollectionMode &&
    typeof rate === 'number' &&
    Number.isFinite(rate) &&
    rate >= 0 &&
    (value.shippingPricingMethod === 'FIXED' ? Number.isSafeInteger(rate) : rate > 0)
  );
}
@Component({
  selector: 'g-shipping-fields',
  imports: [FormsModule, Input, Select],
  template: `
    <div class="field-grid">
      <label
        >Mode de transport<select
          gSelect
          [ngModelOptions]="{ standalone: true }"
          [ngModel]="value().transportMode"
          (ngModelChange)="patch({ transportMode: $event })"
        >
          @for (option of transportOptions; track option.value) {
            <option [value]="option.value">{{ option.label }}</option>
          }
        </select></label
      >
      <label
        >Tarification de la livraison<select
          gSelect
          [ngModelOptions]="{ standalone: true }"
          [ngModel]="value().shippingPricingMethod"
          (ngModelChange)="patch({ shippingPricingMethod: $event })"
        >
          @for (option of pricingOptions; track option.value) {
            <option [value]="option.value">{{ option.label }}</option>
          }
        </select></label
      >
      <label
        >Collecte des frais<select
          gSelect
          [ngModelOptions]="{ standalone: true }"
          [ngModel]="value().shippingCollectionMode"
          (ngModelChange)="patch({ shippingCollectionMode: $event })"
        >
          @for (option of collectionOptions; track option.value) {
            <option [value]="option.value">{{ option.label }}</option>
          }
        </select></label
      >
      <label
        >Tarif de livraison (F CFA)<input
          gInput
          type="number"
          [min]="value().shippingPricingMethod === 'FIXED' ? 0 : 0.000001"
          [step]="value().shippingPricingMethod === 'FIXED' ? 1 : 'any'"
          [ngModelOptions]="{ standalone: true }"
          [ngModel]="value().shippingRate"
          (ngModelChange)="patch({ shippingRate: $event })"
          required
      /></label>
    </div>
    <p class="notice">
      Le tarif est exprimé par unité de tarification choisie. Un forfait peut être gratuit.
    </p>
    @if (value().shippingCollectionMode === 'ESTIMATED_AT_CHECKOUT') {
      <p class="notice">
        Au kilogramme ou au mètre cube, chaque variante doit avoir une estimation de poids ou de
        volume dans le catalogue.
      </p>
    }
  `,
})
export class ShippingFields {
  readonly value = model.required<ShippingInput>();
  readonly transportOptions = transportOptions;
  readonly pricingOptions = pricingOptions;
  readonly collectionOptions = collectionOptions;
  patch(value: Partial<ShippingInput>) {
    this.value.update((v) => ({ ...v, ...value }));
  }
}
