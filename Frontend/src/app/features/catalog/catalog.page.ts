import { Component } from '@angular/core';
import { FeaturePlaceholder } from '../../shared/feedback/feature-placeholder';
@Component({
  selector: 'g-catalog-page',
  imports: [FeaturePlaceholder],
  template: `<g-feature-placeholder
    title="Produits"
    intro="Préparez les produits que vous proposerez dans vos groupages."
    icon="box"
  />`,
  styles: `
    :host {
      display: block;
    }
  `,
})
export class CatalogPage {}
