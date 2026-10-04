import { CustomerShippingPayment } from './customer-shipping-payment';
import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { PublicShell } from '../checkout/public-shell';
import { Button } from '../../shared/ui/button';
import { OrderTimeline } from '../tracking/order-timeline';
import { OrderSummary } from '../tracking/order-summary';
import { CustomerAccessService } from './customer-access.service';
import { OrderTrackingView } from '../../api/generated/models';
import { orderStatus, fulfillmentStatus } from '../checkout/public-messages';
import { accessError } from './access-errors';
@Component({
  selector: 'g-customer-order-page',
  imports: [CustomerShippingPayment, PublicShell, Button, RouterLink, OrderTimeline, OrderSummary],
  styleUrl: './access-page.scss',
  template: `<g-public-shell>
    @if (phoneVerified()) {
      <a routerLink="/mes-commandes">← Mes commandes</a>
    }
    <h1>Le suivi de ma commande</h1>
    @if (busy()) {
      <p role="status">Chargement…</p>
    }
    @if (order(); as value) {
      <p class="eyebrow">{{ statusLabel(value.status) }}</p>
      <p>{{ fulfillment(value.fulfillmentStatus) }}</p>
      <g-order-timeline [order]="value" />
      <g-customer-shipping-payment
        [value]="value.shippingSettlement"
        [orderNumber]="value.orderNumber!"
        (refresh)="load(true)"
      />
      <g-order-summary [receipt]="value" [confirmed]="value.status === 'CONFIRMED'" />
      <div class="actions">
        <button gButton variant="secondary" [disabled]="busy()" (click)="load()">
          Actualiser le suivi
        </button>
        @if (canResendTrackingLink()) {
          <button gButton variant="secondary" [disabled]="busy()" (click)="resend()">
            Renvoyer le lien de suivi
          </button>
        }
        <button gButton variant="ghost" [disabled]="busy()" (click)="logout()">
          Fermer ma session
        </button>
      </div>
      @if (phoneVerified()) {
        <p class="muted">
          Un renvoi remplace le lien précédent et ferme les accès ouverts avec celui-ci.
        </p>
      }
    }
    @if (message()) {
      <p role="status">{{ message() }}</p>
    }
    @if (error()) {
      <p role="alert">{{ error() }}</p>
      <div class="actions">
        <button gButton (click)="load()" [disabled]="busy()">Réessayer</button
        ><a routerLink="/suivi">Retrouver mes commandes avec mon numéro</a>
      </div>
    }
  </g-public-shell>`,
})
export class CustomerOrderPage {
  private readonly access = inject(CustomerAccessService);
  private readonly router = inject(Router);
  private readonly number = inject(ActivatedRoute).snapshot.paramMap.get('orderNumber') ?? '';
  readonly order = signal<OrderTrackingView | null>(null);
  readonly phoneVerified = signal(false);
  readonly canResendTrackingLink = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly message = signal('');
  readonly statusLabel = (value?: string) =>
    value === 'CONFIRMED' ? 'Commande confirmée' : orderStatus(value);
  readonly fulfillment = fulfillmentStatus;
  constructor() {
    void this.load();
  }
  async load(quiet = false) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    if (!quiet) this.order.set(null);
    this.phoneVerified.set(false);
    this.canResendTrackingLink.set(false);
    try {
      const session = await this.access.session();
      const [value, capabilities] = await Promise.all([
        this.access.order(this.number),
        this.access.capabilities(this.number),
      ]);
      this.canResendTrackingLink.set(capabilities.canResendTrackingLink === true);
      this.order.set(value);
      this.phoneVerified.set(session.scope === 'PHONE_VERIFIED');
    } catch (error) {
      this.error.set(accessError(error));
    } finally {
      this.busy.set(false);
    }
  }
  async resend() {
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    try {
      await this.access.resend(this.number);
      this.message.set('Le renvoi du lien de suivi a été demandé.');
    } catch (error) {
      this.error.set(accessError(error));
    } finally {
      this.busy.set(false);
    }
  }
  async logout() {
    this.busy.set(true);
    try {
      await this.access.logout();
      this.order.set(null);
      await this.router.navigateByUrl('/suivi', { replaceUrl: true });
    } catch (error) {
      this.error.set(accessError(error));
    } finally {
      this.busy.set(false);
    }
  }
}
