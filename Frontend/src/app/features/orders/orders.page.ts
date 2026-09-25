import { Component } from '@angular/core';
import { FeaturePlaceholder } from '../../shared/feedback/feature-placeholder';
@Component({
  selector: 'g-orders-page',
  imports: [FeaturePlaceholder],
  template: `<g-feature-placeholder
    title="Commandes"
    intro="Retrouvez bientôt les commandes de vos clientes."
    icon="orders"
  />`,
  styles: `
    :host {
      display: block;
    }
  `,
})
export class OrdersPage {}
