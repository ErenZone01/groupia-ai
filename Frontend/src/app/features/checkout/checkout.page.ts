import { Component, inject } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Contact } from '../../api/generated/models';
import { CheckoutStore } from './checkout.store';
import { PublicShell } from './public-shell';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { money } from '../../shared/utils/display';
@Component({
  selector: 'g-checkout-page',
  imports: [PublicShell, Button, Input, FormsModule, RouterLink],
  template: ` <g-public-shell
    ><a [routerLink]="['/public/groupages', id, 'cart']">← Retour au panier</a>
    <h1>Vos coordonnées</h1>
    <p class="muted">Commandez sans créer de compte.</p>
    @if (store.cart.count()) {
      <p class="notice">
        {{ store.cart.count() }} article(s) ·
        {{ money(store.cart.subtotal(), store.cart.group()?.currency) }} hors frais. Le montant
        final sera affiché avant le paiement.
      </p>
      <form #form="ngForm" class="form-stack" (ngSubmit)="form.valid && submit()">
        <fieldset [disabled]="store.busy()">
          <div class="field-grid">
            <label
              >Prénom *<input
                gInput
                name="firstName"
                autocomplete="given-name"
                required
                maxlength="100"
                pattern=".*\\S.*"
                [(ngModel)]="contact.firstName"
                #first="ngModel"
              />
              @if (first.invalid && first.touched) {
                <span class="field-error">Indiquez votre prénom.</span>
              }
            </label>
            <label
              >Nom *<input
                gInput
                name="lastName"
                autocomplete="family-name"
                required
                maxlength="100"
                pattern=".*\\S.*"
                [(ngModel)]="contact.lastName"
                #last="ngModel"
              />
              @if (last.invalid && last.touched) {
                <span class="field-error">Indiquez votre nom.</span>
              }
            </label>
          </div>
          <label
            >Téléphone *<input
              gInput
              name="phone"
              type="tel"
              autocomplete="tel"
              required
              maxlength="30"
              pattern="\\+?[0-9\\(\\) .\\-]{7,30}"
              [(ngModel)]="contact.phone"
              #phone="ngModel"
              aria-describedby="phone-help"
          /></label>
          @if (phone.invalid && phone.touched) {
            <p class="form-error">Vérifiez votre numéro et son indicatif.</p>
          }
          <p id="phone-help" class="muted">
            Indiquez votre numéro avec l’indicatif du pays, par exemple +221.
          </p>
          <label
            >Email (facultatif)<input
              gInput
              name="email"
              type="email"
              email
              autocomplete="email"
              maxlength="254"
              [(ngModel)]="contact.email"
          /></label>
        </fieldset>
        @if (store.error()) {
          <p class="form-error" role="alert">{{ store.error() }}</p>
          <a [routerLink]="['/public/groupages', id, 'cart']">Vérifier mon panier</a>
        }
        <button
          gButton
          type="submit"
          [disabled]="form.invalid || store.busy()"
          [loading]="store.busy()"
        >
          Continuer vers le paiement
        </button>
        <p class="muted">
          Ce bouton crée votre commande. Aucun paiement n’est prélevé à cette étape.
        </p>
      </form>
    } @else {
      <p class="notice">Votre panier est vide. Ajoutez des produits avant de commander.</p>
    }
  </g-public-shell>`,
  styles: `
    h1 {
      margin: var(--s5) 0;
    }
    fieldset {
      border: 0;
      padding: 0;
      min-width: 0;
      display: grid;
      gap: var(--s3);
    }
    label {
      display: grid;
      gap: var(--s2);
    }
    form {
      margin-top: var(--s5);
    }
  `,
})
export class CheckoutPage {
  readonly store = inject(CheckoutStore);
  private readonly router = inject(Router);
  readonly id =
    inject(ActivatedRoute).snapshot.paramMap.get('publicIdentifier') ??
    inject(ActivatedRoute).snapshot.paramMap.get('publicId')!;
  readonly money = money;
  contact: Contact = { firstName: '', lastName: '', phone: '', email: '' };
  constructor() {
    this.store.cart.select(this.id);
    this.store.error.set('');
  }
  async submit() {
    if (await this.store.submit(this.contact)) await this.router.navigateByUrl('/public/order');
  }
}
