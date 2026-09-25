import { Component, input, output } from '@angular/core';
import { Icon } from './icon';
@Component({
  selector: 'g-icon-button',
  imports: [Icon],
  template: `<button
    type="button"
    [disabled]="disabled() || loading()"
    [attr.aria-label]="label()"
    [attr.title]="label()"
    [attr.aria-busy]="loading() || null"
    (click)="pressed.emit()"
  >
    <g-icon [name]="loading() ? 'clock' : icon()" />
  </button>`,
  styles: `
    button {
      display: grid;
      place-items: center;
      width: 44px;
      height: 44px;
      border: 1px solid var(--border);
      border-radius: var(--radius-sm);
      background: var(--surface);
      color: var(--muted);
    }
    button:hover:not(:disabled) {
      color: var(--primary);
      background: var(--surface-alt);
    }
  `,
})
export class IconButton {
  readonly label = input.required<string>();
  readonly icon = input('bell');
  readonly disabled = input(false);
  readonly loading = input(false);
  readonly pressed = output<void>();
}
