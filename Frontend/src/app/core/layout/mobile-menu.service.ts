import { Injectable, signal } from '@angular/core';

/**
 * The header owns the trigger and the mobile shell owns the native modal.
 * A request carries its real trigger so focus returns to the correct control.
 */
@Injectable({ providedIn: 'root' })
export class MobileMenuService {
  readonly requested = signal<{ element: HTMLElement; key: number } | null>(null);
  readonly opened = signal(false);
  private sequence = 0;

  requestOpen(element: HTMLElement) {
    this.requested.set({ element, key: ++this.sequence });
  }
}
