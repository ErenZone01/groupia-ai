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
      height: 26px;
      padding: 3px;
      border: 1px solid var(--border);
      border-radius: var(--pill);
      background: var(--muted);
    }
    span {
      display: block;
      width: 18px;
      height: 18px;
      background: var(--surface);
      border-radius: var(--pill);
      transition: transform var(--duration);
    }
    button[aria-checked='true'] {
      background: var(--primary);
    }
    button[aria-checked='true'] span {
      transform: translateX(17px);
    }
    button:hover:not(:disabled) {
      filter: brightness(0.95);
    }
  `,
})
export class Toggle {
  readonly label = input.required<string>();
  readonly checked = model(false);
  readonly disabled = input(false);
}
