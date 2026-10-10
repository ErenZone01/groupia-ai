import { Component } from '@angular/core';
import { Icon } from './icon';
@Component({
  selector: 'g-brand',
  imports: [Icon],
  template: '<g-icon name="bag"/><span>Groupia<span class="dot">.</span></span>',
  styles: `
    :host {
      display: flex;
      align-items: center;
      gap: var(--s2);
      font-size: 1.7rem;
      font-weight: 700;
      letter-spacing: -0.06em;
      color: inherit;
    }
    g-icon {
      color: var(--accent);
      width: 32px;
      height: 36px;
    }
    .dot {
      color: var(--accent);
    }
  `,
})
export class Brand {}
