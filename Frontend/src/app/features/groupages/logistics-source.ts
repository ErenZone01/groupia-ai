import { Component, input, model } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Select } from '../../shared/forms/select';
import { ShipCampaign } from '../../api/generated/models';
import { logisticsSources } from './logistics-labels';
@Component({
  selector: 'g-logistics-source',
  imports: [FormsModule, Select],
  template: `<label
    >Information communiquée par
    <select
      gSelect
      [ngModel]="value()"
      [ngModelOptions]="{ standalone: true }"
      (ngModelChange)="value.set($event)"
      [disabled]="disabled()"
    >
      @for (option of options; track option.value) {
        <option [value]="option.value">{{ option.label }}</option>
      }
    </select></label
  >`,
})
export class LogisticsSourceInput {
  readonly value = model<NonNullable<ShipCampaign['source']>>('UNKNOWN');
  readonly disabled = input(false);
  readonly options = logisticsSources;
}
