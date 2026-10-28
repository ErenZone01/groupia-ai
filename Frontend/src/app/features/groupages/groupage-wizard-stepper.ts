import { Component, input, output } from '@angular/core';

export interface WizardStep {
  title: string;
  hint: string;
}

@Component({
  selector: 'g-groupage-wizard-stepper',
  templateUrl: './groupage-wizard-stepper.html',
  styleUrl: './groupage-wizard-stepper.scss',
})
export class GroupageWizardStepper {
  readonly step = input.required<number>();
  readonly visited = input.required<number>();
  readonly saving = input(false);
  readonly steps = input.required<readonly WizardStep[]>();
  readonly selected = output<number>();
}
