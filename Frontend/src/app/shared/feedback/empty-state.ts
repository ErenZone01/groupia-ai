import { Component, input } from '@angular/core';
import { Icon } from '../ui/icon';
@Component({
  selector: 'g-empty-state',
  imports: [Icon],
  template: `<div class="symbol"><g-icon [name]="icon()" /></div>
    <h2>{{ title() }}</h2>
    <p>{{ description() }}</p>
    <ng-content />`,
  styles: `
    :host {
      display: flex;
      align-items: center;
      flex-direction: column;
      text-align: center;
      padding: var(--s10) var(--s4);
      gap: var(--s3);
    }
    .symbol {
      display: grid;
      place-items: center;
      width: 64px;
      height: 64px;
      background: var(--primary-soft);
      color: var(--primary);
      border-radius: var(--radius-xl);
      margin-bottom: var(--s2);
    }
    .symbol g-icon {
      width: 28px;
      height: 28px;
    }
    p {
      color: var(--muted);
      max-width: 38ch;
    }
  `,
})
export class EmptyState {
  readonly title = input.required<string>();
  readonly description = input('');
  readonly icon = input('box');
}
