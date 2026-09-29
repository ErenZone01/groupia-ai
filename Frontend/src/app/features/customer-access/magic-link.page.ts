import { Component, inject, signal } from '@angular/core';
import { Location } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { PublicShell } from '../checkout/public-shell';
import { Button } from '../../shared/ui/button';
import { CustomerAccessService } from './customer-access.service';
@Component({
  selector: 'g-magic-link-page',
  imports: [PublicShell, Button, RouterLink],
  styleUrl: './access-page.scss',
  template: `<g-public-shell
    ><h1>Votre suivi privé</h1>
    @if (busy()) {
      <p role="status">Ouverture sécurisée de votre commande…</p>
    }
    @if (error()) {
      <p role="alert">{{ error() }}</p>
    }
    @if (retryable()) {
      <button gButton (click)="exchange()" [disabled]="busy()">Réessayer</button>
    }
    <div class="actions"><a routerLink="/suivi">Retrouver mes commandes avec mon numéro</a></div>
  </g-public-shell>`,
})
export class MagicLinkPage {
  private readonly access = inject(CustomerAccessService);
  private readonly router = inject(Router);
  private token = inject(ActivatedRoute).snapshot.paramMap.get('token') ?? '';
  readonly busy = signal(false);
  readonly error = signal('');
  readonly retryable = signal(false);
  constructor() {
    // Remove the bearer from the current history entry BEFORE any HTTP request.
    inject(Location).replaceState('/s');
    void this.exchange();
  }
  async exchange() {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.retryable.set(false);
    try {
      const session = await this.access.exchange(this.token);
      this.token = '';
      if (!session.orderNumber) throw new Error('Missing order');
      await this.router.navigate(['/mes-commandes', session.orderNumber], { replaceUrl: true });
    } catch (error) {
      const retry =
        (error instanceof HttpErrorResponse && (error.status === 0 || error.status >= 500)) ||
        (error instanceof Error && error.name === 'TimeoutError');
      this.retryable.set(retry);
      this.error.set(
        retry
          ? 'Connexion interrompue. Réessayez pour ouvrir votre suivi.'
          : 'Ce lien est invalide ou n’est plus disponible.',
      );
      if (!retry) this.token = '';
    } finally {
      this.busy.set(false);
    }
  }
}
