import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { Button } from '../../shared/ui/button';
@Component({
  selector: 'g-session-unavailable',
  imports: [Button, RouterLink],
  template: `<main>
    <h1>Connexion temporairement indisponible</h1>
    <p role="status">{{ auth.problem() }}</p>
    <button gButton [disabled]="busy()" (click)="retry()">Réessayer</button>
    <a routerLink="/suivi">Suivre une commande</a>
  </main>`,
  styles: `
    main {
      max-width: 38rem;
      margin: 10vh auto;
      padding: var(--s6);
    }
  `,
})
export class SessionUnavailablePage {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly busy = signal(false);
  async retry() {
    this.busy.set(true);
    try {
      if (await this.auth.retryRestoration()) {
        const target = this.route.snapshot.queryParamMap.get('returnUrl');
        await this.router.navigateByUrl(target?.startsWith('/app/') ? target : '/app/accueil');
      } else if (this.auth.state() === 'ANONYMOUS') await this.router.navigateByUrl('/connexion');
    } finally {
      this.busy.set(false);
    }
  }
}
