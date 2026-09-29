import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { PublicShell } from '../checkout/public-shell';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { CustomerAccessService } from './customer-access.service';
import { accessError } from './access-errors';
import { OtpRequested } from '../../api/generated/models';
@Component({
  selector: 'g-recovery-page',
  imports: [FormsModule, RouterLink, PublicShell, Button, Input],
  styleUrl: './access-page.scss',
  template: `<g-public-shell>
    <p class="eyebrow">Votre espace de suivi</p>
    <h1>Retrouver mes commandes</h1>
    <p class="muted">Vos commandes, simplement avec votre numéro. Aucun compte à créer.</p>
    @if (!challenge()) {
      <form (ngSubmit)="request()">
        <label
          >Numéro WhatsApp<input
            gInput
            type="tel"
            name="phone"
            autocomplete="tel"
            inputmode="tel"
            required
            maxlength="64"
            [(ngModel)]="phone"
            placeholder="+221 77 123 45 67"
        /></label>
        <p class="muted">
          Utilisez le numéro indiqué lors de votre commande, avec l’indicatif du pays.
        </p>
        <button gButton type="submit" [loading]="busy()" [disabled]="busy() || !phone.trim()">
          Recevoir mon code
        </button>
      </form>
    } @else {
      <form (ngSubmit)="verify()">
        <h2>Entrez le code reçu</h2>
        <p role="status">{{ challenge()?.message }}</p>
        <label
          >Code à 6 chiffres<input
            gInput
            name="otp"
            inputmode="numeric"
            autocomplete="one-time-code"
            pattern="[0-9]{6}"
            minlength="6"
            maxlength="6"
            required
            [(ngModel)]="code"
        /></label>
        <button gButton type="submit" [loading]="busy()" [disabled]="busy() || code.length !== 6">
          Voir mes commandes
        </button>
      </form>
      <div class="actions">
        <button
          gButton
          variant="secondary"
          [disabled]="busy() || remaining() > 0"
          (click)="request()"
        >
          {{ remaining() > 0 ? 'Renvoyer dans ' + remaining() + ' s' : 'Renvoyer le code' }}
        </button>
        <button gButton variant="ghost" [disabled]="busy()" (click)="changePhone()">
          Changer de numéro
        </button>
      </div>
      <p class="muted">
        Le code est valable quelques minutes. Le dernier code reçu remplace le précédent.
      </p>
    }
    @if (error()) {
      <p class="form-error" role="alert">{{ error() }}</p>
    }
    <div class="actions">
      <a routerLink="/public/order">Ouvrir le suivi conservé sur cet appareil</a>
    </div>
  </g-public-shell>`,
})
export class RecoveryPage {
  private readonly access = inject(CustomerAccessService);
  private readonly router = inject(Router);
  phone = '';
  code = '';
  readonly challenge = signal<OtpRequested | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly remaining = signal(0);
  private resendAt = 0;
  constructor() {
    const timer = setInterval(
      () => this.remaining.set(Math.max(0, Math.ceil((this.resendAt - Date.now()) / 1000))),
      1000,
    );
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }
  async request() {
    if (this.busy() || this.remaining() > 0) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const result = await this.access.request(this.phone);
      this.challenge.set(result);
      this.code = '';
      this.resendAt = Date.now() + (result.resendAfterSeconds ?? 60) * 1000;
      this.remaining.set(result.resendAfterSeconds ?? 60);
    } catch (error) {
      this.error.set(accessError(error));
    } finally {
      this.busy.set(false);
    }
  }
  async verify() {
    const id = this.challenge()?.challengeId;
    if (!id || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await this.access.verify(id, this.code);
      this.code = '';
      await this.router.navigateByUrl('/mes-commandes', { replaceUrl: true });
    } catch (error) {
      this.code = '';
      this.error.set(accessError(error));
    } finally {
      this.busy.set(false);
    }
  }
  changePhone() {
    this.challenge.set(null);
    this.code = '';
    this.error.set('');
    this.resendAt = 0;
    this.remaining.set(0);
  }
}
