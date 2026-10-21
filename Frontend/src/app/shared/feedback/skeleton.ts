import { Component, input } from '@angular/core';
@Component({
  selector: 'g-skeleton',
  template: '<span role="status"><span class="sr-only">{{label()}}</span></span>',
  styles: `
    :host {
      display: block;
    }
    span[role='status'] {
      display: block;
      height: 1.2em;
      border-radius: var(--radius-sm);
      background: var(--skeleton);
      animation: pulse 1.5s ease-in-out infinite;
    }
    @keyframes pulse {
      50% {
        opacity: 0.4;
      }
    }
  `,
})
export class Skeleton {
  readonly label = input('Chargement en cours');
}
