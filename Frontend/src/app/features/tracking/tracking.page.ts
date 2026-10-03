import { OrderTimeline } from './order-timeline';
import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PublicShell } from '../checkout/public-shell';
import { PublicTrackingStore } from './public-tracking.store';
import { OrderSummary } from './order-summary';
import { orderStatus, fulfillmentStatus } from '../checkout/public-messages';
import { Button } from '../../shared/ui/button';
import { Skeleton } from '../../shared/feedback/skeleton';
@Component({
  selector: 'g-tracking-page',
  providers: [PublicTrackingStore],
  imports: [OrderTimeline, PublicShell, OrderSummary, Button, Skeleton, RouterLink],
  template: ` <g-public-shell>
    @if (!store.session.current()?.trackingToken || store.unavailable()) {
      <h1>Votre suivi</h1>
      <p class="notice">Le suivi de cette commande n’est plus disponible sur cet appareil.</p>
      <p>
        Le lien privé est conservé uniquement pour cette session navigateur.
        <a routerLink="/suivi">Retrouver mes commandes avec mon numéro</a>.
      </p>
    } @else {
      @if (store.order(); as order) {
        <h1 aria-live="polite">{{ statusLabel(order.status) }}</h1>
        @if (order.status === 'CONFIRMED') {
          <p class="notice" role="status">✓ Votre commande est confirmée.</p>
        }
        <g-order-timeline [order]="order" />
        <p>Retrait : {{ fulfillment(order.fulfillmentStatus) }}</p>
      } @else {
        <h1>Vérification de votre commande</h1>
        <g-skeleton />
      }
      <g-order-summary
        [receipt]="store.order() ?? store.session.current()?.receipt ?? null"
        [shippingLater]="!!store.session.current()?.shippingLater && !store.order()?.shippingFinal"
        [confirmed]="store.order()?.status === 'CONFIRMED'"
      />
      @if (store.error()) {
        <p class="form-error" role="alert">{{ store.error() }}</p>
      }
      @if (store.order()?.status === 'PENDING_PAYMENT') {
        @if (store.session.current()?.payment; as payment) {
          @if (payment.reconciliationRequired) {
            <p class="notice" role="status">
              Nous vérifions votre paiement. Ne recommencez pas le paiement.
            </p>
          } @else if (store.checkoutUrl(); as url) {
            <h2>Paiement sécurisé</h2>
            <p>
              Terminez votre paiement dans la page Bictorys, puis revenez ici pour suivre votre
              commande.
            </p>
            <a
              gButton
              [href]="url"
              target="_blank"
              rel="noopener noreferrer"
              referrerpolicy="no-referrer"
              >Payer avec Wave / Orange Money</a
            >
            <p class="muted">
              S’ouvre dans un nouvel onglet. Vous pourrez retourner au paiement avec ce même bouton.
            </p>
          } @else {
            <p class="notice">
              Le lien de paiement n’est pas disponible. Nous conservons votre tentative ; actualisez
              le suivi sans recommencer le paiement.
            </p>
          }
        } @else {
          <button
            gButton
            [loading]="store.busy()"
            [disabled]="store.busy()"
            (click)="store.initiate()"
          >
            Préparer mon paiement sécurisé
          </button>
        }
      }
      @if (store.polling()) {
        <p class="muted" role="status">
          Le suivi est actualisé automatiquement pendant trois minutes au maximum.
        </p>
      } @else {
        <p class="muted">Vous pouvez actualiser le suivi à tout moment.</p>
      }
      <div class="actions">
        <button gButton variant="secondary" [loading]="store.loading()" (click)="store.refresh()">
          Actualiser le suivi
        </button>
        <a [routerLink]="['/public/groupages', store.session.current()?.publicIdentifier]"
          >Revenir au groupage</a
        >
      </div>
    }
  </g-public-shell>`,
  styles: `
    h1,
    h2 {
      margin: var(--s5) 0;
    }
    p {
      margin: var(--s4) 0;
    }
    .g-button {
      white-space: normal;
      max-width: 100%;
      min-height: 44px;
    }
  `,
})
export class TrackingPage {
  readonly store = inject(PublicTrackingStore);
  readonly statusLabel = orderStatus;
  readonly fulfillment = fulfillmentStatus;
}
