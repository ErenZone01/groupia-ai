import { Component, effect, inject } from '@angular/core';
import { BoutiqueContextService } from '../boutique/boutique-context.service';
import { AuthService } from '../auth/auth.service';
import { BoutiqueOnboarding } from '../../features/boutique/boutique-onboarding';
import { Skeleton } from '../../shared/feedback/skeleton';
import { Button } from '../../shared/ui/button';
@Component({
  selector: 'g-boutique-gate',
  imports: [BoutiqueOnboarding, Skeleton, Button],
  template: `
    @if (context.loading()) {
      <g-skeleton /><g-skeleton />
    } @else if (context.error()) {
      <div role="alert">
        <p>{{ context.error() }}</p>
        <button gButton (click)="context.ensure()">Réessayer</button>
      </div>
    } @else if (context.ready() && !context.selected()) {
      <g-boutique-onboarding />
    } @else if (context.selected()) {
      <ng-content />
    }
  `,
})
export class BoutiqueGate {
  readonly context = inject(BoutiqueContextService);
  private readonly auth = inject(AuthService);
  constructor() {
    effect(() => {
      this.auth.generation();
      if (this.auth.signedIn()) void this.context.ensure();
    });
  }
}
