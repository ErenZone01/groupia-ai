import { Component } from '@angular/core';
import { FeaturePlaceholder } from '../../shared/feedback/feature-placeholder';
@Component({
  selector: 'g-public-groupage-page',
  imports: [FeaturePlaceholder],
  template: `<g-feature-placeholder
    title="Découvrir le groupage"
    intro="La sélection de votre boutique sera bientôt disponible."
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
export class PublicGroupagePage {}
