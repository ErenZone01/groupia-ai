import { Component, input } from '@angular/core';
import { Icon } from './icon';
@Component({
  selector: 'g-stat-card',
  imports: [Icon],
  host: { '[attr.data-tone]': 'tone()' },
  template: `<div class="top">
      <span class="value">{{ value() }}</span
      ><span class="icon"><g-icon [name]="icon()" /></span>
    </div>
    <p>{{ label() }}</p>`,
  styles: `
    :host {
      display: block;
      min-width: 0;
      background: var(--surface);
      padding: var(--s5);
      border: 1px solid var(--border);
      border-radius: var(--radius-md);
      box-shadow: var(--shadow);
    }
    .top {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--s2);
    }
    .value {
      font-size: var(--text-2xl);
      font-weight: 600;
      letter-spacing: -0.05em;
    }
    .icon {
      display: grid;
      place-items: center;
      width: 38px;
      height: 38px;
      border-radius: var(--radius-sm);
      background: var(--info-soft);
      color: var(--info-text);
    }
    p {
      font-size: var(--text-sm);
      color: var(--muted);
      margin-top: var(--s3);
    }
    :host([data-tone='positive']) .icon {
      background: var(--positive-soft);
      color: var(--positive-text);
    }
    :host([data-tone='positive']) .value {
      color: var(--positive-text);
    }
    :host([data-tone='warning']) .icon {
      background: var(--warning-soft);
      color: var(--warning-text);
    }
    :host([data-tone='warning']) .value {
      color: var(--warning-text);
    }
    :host([data-tone='danger']) .icon {
      background: var(--danger-soft);
      color: var(--danger-text);
    }
    :host([data-tone='danger']) .value {
      color: var(--danger-text);
    }
    @media (max-width: 599px) {
      :host {
        padding: var(--s4);
      }
      .value {
        font-size: var(--text-xl);
      }
      .icon {
        width: 32px;
        height: 32px;
      }
      p {
        font-size: var(--text-xs);
      }
    }
  `,
})
export class StatCard {
  readonly value = input<string | number>('—');
  readonly label = input.required<string>();
  readonly icon = input('orders');
  readonly tone = input('info');
}
