import { Component } from '@angular/core';
import { FeaturePlaceholder } from '../../shared/feedback/feature-placeholder';
@Component({
  selector: 'g-tracking-page',
  imports: [FeaturePlaceholder],
  template: `<g-feature-placeholder
    title="Suivre ma commande"
    intro="Votre suivi de commande sera bientôt disponible."
    icon="orders"
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
export class TrackingPage {}
