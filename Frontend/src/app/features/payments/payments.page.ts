import { Component } from '@angular/core';
import { FeaturePlaceholder } from '../../shared/feedback/feature-placeholder';
@Component({
  selector: 'g-payments-page',
  imports: [FeaturePlaceholder],
  template: `<g-feature-placeholder
    title="Paiements"
    intro="Suivez bientôt les paiements de votre boutique."
    icon="card"
  />`,
  styles: `
    :host {
      display: block;
    }
  `,
})
export class PaymentsPage {}
