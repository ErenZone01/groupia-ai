import { Component } from '@angular/core';
import { FeaturePlaceholder } from '../../shared/feedback/feature-placeholder';
@Component({
  selector: 'g-groupages-page',
  imports: [FeaturePlaceholder],
  template: `<g-feature-placeholder
    title="Groupages"
    intro="Un espace pour rassembler vos clientes autour de vos collections."
    icon="group"
  />`,
  styles: `
    :host {
      display: block;
    }
  `,
})
export class GroupagesPage {}
