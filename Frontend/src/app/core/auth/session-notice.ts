import { Component, inject, signal } from '@angular/core';
import { AuthService } from './auth.service';
import { Button } from '../../shared/ui/button';
@Component({
  selector: 'g-session-notice',
  imports: [Button],
  template: `@if (auth.problem()) {
    <aside role="status">
      <span>{{ auth.problem() }}</span
      ><button gButton [disabled]="busy()" (click)="retry()">Réessayer</button>
    </aside>
  }`,
  styles: `
    aside {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--s3);
      padding: var(--s4) var(--s6);
      background: var(--surface-alt);
      color: var(--text);
      border-bottom: 1px solid var(--border);
    }
    span {
      flex: 1;
      min-width: 0;
    }
  `,
})
export class SessionNotice {
  readonly auth = inject(AuthService);
  readonly busy = signal(false);
  async retry() {
    this.busy.set(true);
    try {
      await this.auth.retryRestoration();
    } finally {
      this.busy.set(false);
    }
  }
}
