import { Component, input, signal } from '@angular/core';
import { Icon } from '../../shared/ui/icon';

@Component({
  selector: 'g-groupage-wizard-summary',
  imports: [Icon],
  templateUrl: './groupage-wizard-summary.html',
  styleUrl: './groupage-wizard-summary.scss',
})
export class GroupageWizardSummary {
  readonly title = input.required<string>();
  readonly closingLabel = input.required<string>();
  readonly transportLabel = input.required<string>();
  readonly rateLabel = input.required<string>();
  readonly count = input.required<number>();
  readonly progress = input.required<number>();
  readonly expanded = signal(false);
}
