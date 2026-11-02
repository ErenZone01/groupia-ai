import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PublicShell } from '../checkout/public-shell';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import {
  CustomerAccessService, DirectRecoveredOrders, RotatedDirectOrderLink,
} from '../customer-access/customer-access.service';
import { accessError } from '../customer-access/access-errors';
import { OtpRequested } from '../../api/generated/models';
import { money } from '../../shared/utils/display';

@Component({
  selector: 'g-direct-order-recovery',
  imports: [PublicShell, FormsModule, RouterLink, Button, Input],
  styleUrl: '../customer-access/access-page.scss',
  template: `<g-public-shell>
    <p class="eyebrow">Vente directe · Vos commandes</p>
    <h1>Retrouver mon suivi</h1>
    <p class="muted">Vous avez perdu votre lien privé ? Vérifiez le numéro de téléphone
      utilisé lors de la commande pour en créer un nouveau.</p>

    @if (loading()) { <p role="status">Vérification de votre session…</p> }
    @if (!loading() && !verified()) {
      @if (!challenge()) {
        <form (ngSubmit)="requestCode()">
          <label>Numéro WhatsApp
            <input gInput name="phone" type="tel" autocomplete="tel" inputmode="tel"
              maxlength="64" required [(ngModel)]="phone" placeholder="+221 77 123 45 67" />
          </label>
          <p class="muted">Le code est envoyé au numéro indiqué lors de votre commande.</p>
          <button gButton type="submit" [disabled]="busy() || !phone.trim()"
            [loading]="busy()">Recevoir mon code</button>
        </form>
      } @else {
        <form (ngSubmit)="verifyCode()">
          <h2>Saisir le code reçu</h2>
          <p role="status">{{ challenge()?.message }}</p>
          <label>Code à 6 chiffres
            <input gInput name="code" inputmode="numeric" autocomplete="one-time-code"
              pattern="[0-9]{6}" minlength="6" maxlength="6" required [(ngModel)]="code" />
          </label>
          <button gButton type="submit" [disabled]="busy() || !isSixDigits(code)"
            [loading]="busy()">Vérifier mon numéro</button>
        </form>
        <button gButton variant="ghost" [disabled]="busy()" (click)="changePhone()">
          Changer de numéro
        </button>
        <p class="muted">Le code expire rapidement et le nombre d'essais est limité.</p>
      }
    }

    @if (verified()) {
      <h2>Mes commandes directes</h2>
      <p class="muted">Seules les commandes liées au numéro vérifié apparaissent ici.</p>
      @if (orders(); as result) {
        <div class="stack">
          @for (order of result.items; track order.orderNumber) {
            <article class="card">
              <h3>Commande {{ order.orderNumber }}</h3>
              <p class="muted">
                {{ order.fulfillmentMethod === 'PICKUP' ? 'Retrait' : 'Livraison' }}
                · En attente de finalisation
              </p>
              @if (order.acceptedTotalXof !== null) {
                <p class="amount">{{ money(order.acceptedTotalXof, order.currency) }}</p>
              }
              @if (newLink()?.orderNumber === order.orderNumber) {
                <p role="status">Nouveau lien créé. L'ancien lien ne fonctionne plus.</p>
                <a gButton [routerLink]="['/v', newLink()!.publicIdentifier, 'commande']"
                  [fragment]="'access=' + newLink()!.token">Ouvrir mon nouveau suivi privé</a>
                <p class="muted">Conservez ce lien : toute personne qui le possède
                  pourra voir le résumé de la commande.</p>
              } @else {
                <button gButton variant="secondary" [disabled]="busy()" [loading]="busy()"
                  (click)="rotate(order.orderNumber)">Créer un nouveau lien privé</button>
              }
            </article>
          } @empty {
            <p class="card">Aucune commande directe liée à ce numéro.</p>
          }
        </div>
        @if (result.total > result.size) {
          <nav class="actions" aria-label="Pages des commandes directes">
            <button gButton variant="secondary" [disabled]="busy() || page() === 0"
              (click)="load(page() - 1)">Précédent</button>
            <span>Page {{ page() + 1 }}</span>
            <button gButton variant="secondary"
              [disabled]="busy() || (page() + 1) * result.size >= result.total"
              (click)="load(page() + 1)">Suivant</button>
          </nav>
        }
      }
      <div class="actions">
        <button gButton variant="ghost" [disabled]="busy()" (click)="logout()">
          Fermer ma session
        </button>
      </div>
    }
    @if (error()) { <p class="form-error" role="alert">{{ error() }}</p> }
    <a [routerLink]="['/v', publicId()]">Retour à la boutique</a>
  </g-public-shell>`,
})
export class DirectOrderRecoveryPage {
  private readonly access = inject(CustomerAccessService);
  private readonly route = inject(ActivatedRoute);
  readonly publicId = signal(this.route.snapshot.paramMap.get('publicIdentifier') ?? '');
  readonly loading = signal(true);
  readonly verified = signal(false);
  readonly busy = signal(false);
  readonly challenge = signal<OtpRequested | null>(null);
  readonly orders = signal<DirectRecoveredOrders | null>(null);
  readonly newLink = signal<RotatedDirectOrderLink | null>(null);
  readonly error = signal('');
  readonly page = signal(0);
  readonly money = money;
  phone = '';
  code = '';
  private sequence = 0;

  constructor() {
    inject(DestroyRef).onDestroy(() => { this.sequence++; this.newLink.set(null); });
    void this.restoreSession();
  }

  isSixDigits(value: string) { return /^[0-9]{6}$/.test(value); }

  async restoreSession() {
    const seq = ++this.sequence;
    try {
      const session = await this.access.session();
      if (seq !== this.sequence) return;
      if (session.scope === 'PHONE_VERIFIED') {
        this.verified.set(true);
        await this.load(0);
      }
    } catch {
      // An absent/expired session is normal: the OTP form remains available.
    } finally {
      if (seq === this.sequence) this.loading.set(false);
    }
  }

  async requestCode() {
    if (this.busy() || !this.phone.trim()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const result = await this.access.request(this.phone);
      this.challenge.set(result);
      this.code = '';
    } catch (err) {
      this.error.set(accessError(err));
    } finally {
      this.busy.set(false);
    }
  }

  async verifyCode() {
    const id = this.challenge()?.challengeId;
    if (this.busy() || !id || !this.isSixDigits(this.code)) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const result = await this.access.verify(id, this.code);
      this.code = '';
      if (result.scope !== 'PHONE_VERIFIED') throw new Error('Vérification incomplète');
      this.verified.set(true);
      this.challenge.set(null);
    } catch (err) {
      this.code = '';
      this.error.set(accessError(err));
    } finally {
      this.busy.set(false);
    }
    if (this.verified()) await this.load(0);
  }

  changePhone() {
    this.challenge.set(null);
    this.code = '';
    this.error.set('');
  }

  async load(page = this.page()) {
    if (this.busy() || !this.verified()) return;
    const seq = ++this.sequence;
    this.busy.set(true);
    this.error.set('');
    this.newLink.set(null);
    try {
      const result = await this.access.directOrders(page);
      if (seq !== this.sequence) return;
      this.orders.set(result);
      this.page.set(page);
    } catch (err) {
      if (seq === this.sequence) this.error.set(accessError(err));
    } finally {
      if (seq === this.sequence) this.busy.set(false);
    }
  }

  async rotate(orderNumber: string) {
    if (this.busy() || !this.verified()) return;
    const seq = ++this.sequence;
    this.busy.set(true);
    this.error.set('');
    this.newLink.set(null);
    try {
      const result = await this.access.rotateDirectLink(orderNumber);
      if (seq === this.sequence) this.newLink.set(result);
    } catch (err) {
      if (seq === this.sequence) this.error.set(accessError(err));
    } finally {
      if (seq === this.sequence) this.busy.set(false);
    }
  }

  async logout() {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await this.access.logout();
      this.verified.set(false);
      this.orders.set(null);
      this.newLink.set(null);
      this.challenge.set(null);
      this.phone = '';
      this.code = '';
    } catch (err) {
      this.error.set(accessError(err));
    } finally {
      this.busy.set(false);
    }
  }
}
