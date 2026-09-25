import { Component } from '@angular/core';
import { FeaturePlaceholder } from '../../shared/feedback/feature-placeholder';
@Component({
  selector: 'g-boutique-page',
  imports: [FeaturePlaceholder],
  template: `<g-feature-placeholder
    title="Ma boutique"
    intro="Votre identité, vos coordonnées et vos préférences au même endroit."
    icon="store"
  />`,
  styles: `
    :host {
      display: block;
    }
  `,
})
export class BoutiquePage {}
