import { Component, input } from '@angular/core';
import { Icon } from './icon';
@Component({
  selector: 'g-stat-card',
  imports: [Icon],
  host: { '[attr.data-tone]': 'tone()' },
  template: `<div class="top">
      <span class="icon"><g-icon [name]="icon()" /></span>
      <p>{{ label() }}</p>
    </div>
    <strong class="value">{{ value() }}</strong>`,
  styles: `
    :host {
      display: block;
      min-width: 0;
      background: var(--surface);
      padding: var(--s5);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      box-shadow: var(--shadow);
    }
    .top {
      display: flex;
      align-items: center;
      gap: var(--s2);
    }
    .value {
      display: block;
      font-size: var(--text-3xl);
      font-weight: 600;
      letter-spacing: var(--tracking-heading);
      font-variant-numeric: tabular-nums;
      line-height: var(--leading-heading);
      margin-top: var(--s4);
      overflow-wrap: anywhere;
    }
    .icon {
      display: grid;
      place-items: center;
      width: 38px;
      height: 38px;
      flex-shrink: 0;
      border-radius: var(--radius-sm);
      background: var(--info-soft);
      color: var(--info-text);
    }
    p {
      font-size: var(--text-sm);
      color: var(--muted);
      font-weight: 500;
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
        display: grid;
        grid-template-columns: minmax(0, 1fr) auto;
        gap: var(--s3) var(--s2);
        padding: var(--s4);
      }
      .top {
        display: contents;
      }
      .value {
        grid-row: 2;
        grid-column: 1;
        margin-top: 0;
        font-size: var(--text-2xl);
      }
      .icon {
        grid-row: 2;
        grid-column: 2;
        width: 32px;
        height: 32px;
      }
      p {
        grid-column: 1 / -1;
        grid-row: 1;
        font-size: var(--text-sm);
        overflow-wrap: anywhere;
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
