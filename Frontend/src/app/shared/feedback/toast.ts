import { Component, inject } from '@angular/core';
import { IconButton } from '../ui/icon-button';
import { ToastService } from './toast.service';
@Component({
  selector: 'g-toast',
  imports: [IconButton],
  template: `<div class="announcer" aria-live="polite" aria-atomic="true">
    @if (toast.message()) {
      <div class="toast" [class.error]="toast.tone() === 'error'">
        <p>{{ toast.message() }}</p>
        <g-icon-button icon="close" label="Fermer le message" (pressed)="toast.dismiss()" />
      </div>
    }
  </div>`,
  styles: `
    .announcer {
      position: fixed;
      right: var(--s4);
      bottom: calc(var(--bottom-nav-height) + var(--s4));
      z-index: 100;
      max-width: calc(100% - var(--s8));
    }
    .toast {
      display: flex;
      align-items: center;
      gap: var(--s3);
      padding: var(--s4);
      border: 1px solid var(--border);
      border-left: 4px solid var(--success);
      background: var(--surface);
      color: var(--text);
      border-radius: var(--radius-md);
      box-shadow: var(--shadow);
      max-width: 440px;
    }
    .error {
      border-left-color: var(--danger);
    }
    p {
      overflow-wrap: anywhere;
    }
    @media (min-width: 1024px) {
      .announcer {
        bottom: var(--s6);
      }
    }
  `,
})
export class Toast {
  readonly toast = inject(ToastService);
}
