import { Component } from '@angular/core';
@Component({
  selector: 'g-card',
  template: '<ng-content/>',
  styles: `
    :host {
      display: block;
      min-width: 0;
      height: 100%;
      padding: var(--s6);
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      box-shadow: var(--shadow);
      overflow-wrap: anywhere;
    }
    @media (max-width: 599px) {
      :host {
        padding: var(--s5);
      }
    }
  `,
})
export class Card {}
