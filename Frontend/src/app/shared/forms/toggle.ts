import { Component, input, model } from '@angular/core';
@Component({
  selector: 'g-toggle',
  template: `<button
    type="button"
    role="switch"
    [attr.aria-checked]="checked()"
    [attr.aria-label]="label()"
    [disabled]="disabled()"
    (click)="checked.set(!checked())"
  >
    <span></span>
  </button>`,
  styles: `
    button {
      display: block;
      width: 44px;
      height: 44px;
      padding: 0;
      border: 0;
      border-radius: var(--pill);
      background: transparent;
    }
    span {
      display: block;
      width: 44px;
      height: 26px;
      padding: 3px;
      background: var(--muted);
      border: 1px solid var(--control-border);
      border-radius: var(--pill);
    }
    span::after {
      content: '';
      display: block;
      width: 18px;
      height: 18px;
      background: var(--control-thumb);
      border-radius: var(--pill);
      transition: transform var(--duration);
    }
    button[aria-checked='true'] span {
      background: var(--primary);
    }
    button[aria-checked='true'] span::after {
      transform: translateX(17px);
    }
    button:hover:not(:disabled) {
      background: var(--surface-hover);
    }
  `,
})
export class Toggle {
  readonly label = input.required<string>();
  readonly checked = model(false);
  readonly disabled = input(false);
}
