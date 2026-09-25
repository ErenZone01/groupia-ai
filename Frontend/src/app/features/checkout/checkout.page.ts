import { Component } from '@angular/core';
import { FeaturePlaceholder } from '../../shared/feedback/feature-placeholder';
@Component({
  selector: 'g-checkout-page',
  imports: [FeaturePlaceholder],
  template: `<g-feature-placeholder
    title="Votre commande"
    intro="Cet espace de commande sera bientôt disponible."
    icon="bag"
  />`,
  styles: `
    :host {
      display: block;
      max-width: 1000px;
      margin: auto;
      padding: var(--s8) var(--s5);
    }
  `,
})
export class CheckoutPage {}
