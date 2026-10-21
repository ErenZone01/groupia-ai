import { Component, input } from '@angular/core';
@Component({
  selector: 'g-badge',
  host: { '[attr.data-tone]': 'tone()' },
  template: '<ng-content/>',
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: var(--s1);
      padding: var(--s1) var(--s2);
      border-radius: var(--pill);
      font-size: var(--text-xs);
      font-weight: 600;
      line-height: 1.5;
      max-width: 100%;
      background: var(--surface-alt);
      color: var(--muted);
      white-space: normal;
      overflow-wrap: anywhere;
    }
    :host([data-tone='positive']) {
      color: var(--positive-text);
      background: var(--positive-soft);
    }
    :host([data-tone='warning']) {
      color: var(--warning-text);
      background: var(--warning-soft);
    }
    :host([data-tone='danger']) {
      color: var(--danger-text);
      background: var(--danger-soft);
    }
    :host([data-tone='info']) {
      color: var(--info-text);
      background: var(--info-soft);
    }
  `,
})
export class Badge {
  readonly tone = input<'neutral' | 'positive' | 'warning' | 'danger' | 'info'>('neutral');
}
