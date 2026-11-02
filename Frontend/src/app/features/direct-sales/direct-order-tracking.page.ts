import { Component, DestroyRef, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DirectSalesApi, DirectTrackingView } from './direct-sales.api';
import { PublicShell } from '../checkout/public-shell';
import { money } from '../../shared/utils/display';

/**
 * A private capability link is held only in the URL fragment, never passed in a GET
 * URL or stored in the local order cart. POST the token only to the lookup endpoint.
 */
@Component({
  selector: 'g-direct-order-tracking',
  imports: [PublicShell, RouterLink],
  template: `
    <g-public-shell [wide]="false">
      <section class="direct-tracking" aria-labelledby="tracking-heading">
        <p class="eyebrow">Groupia · Vente directe</p>
        <h1 id="tracking-heading">Suivi de ma commande</h1>
        @if (busy()) {
          <p role="status">Chargement sécurisé du statut…</p>
        } @else if (order(); as data) {
          <p class="tracking-ref">Référence : <strong>{{ data.orderNumber }}</strong></p>
          <h2>Commande enregistrée</h2>
          <p>La boutique doit encore confirmer la suite de votre commande.
            Aucun paiement n'a été demandé ni effectué.</p>
          <dl class="tracking-data">
            <div><dt>Statut</dt><dd>En attente de finalisation</dd></div>
            <div><dt>Remise</dt><dd>{{ data.fulfillmentMethod === 'PICKUP'
              ? 'Retrait à convenir' : 'Livraison à confirmer' }}</dd></div>
            <div><dt>Articles</dt><dd>{{ money(data.goodsSubtotalXof, data.currency) }}</dd></div>
            <div><dt>Frais de plateforme</dt><dd>{{ money(data.platformFeeXof, data.currency) }}</dd></div>
            @if (data.shippingFeeXof !== null) {
              <div><dt>Livraison</dt><dd>{{ money(data.shippingFeeXof, data.currency) }}</dd></div>
            }
            @if (data.acceptedTotalXof !== null) {
              <div><dt>Total accepté</dt><dd>{{ money(data.acceptedTotalXof, data.currency) }}</dd></div>
            }
            <div><dt>Paiement</dt><dd>Indisponible pour le moment</dd></div>
          </dl>
          <p class="notice">Conservez ce lien privé pour revenir consulter votre commande.
            Toute personne qui possède ce lien peut voir ce résumé.</p>
          <button type="button" class="action-link" (click)="reload()">Actualiser le statut</button>
        } @else {
          <p role="alert">{{ message() || 'Lien de commande indisponible.' }}</p>
          <p>Le numéro de commande seul ne donne pas accès au suivi.
            Utilisez le lien privé reçu lors de la confirmation.</p>
        }
        <a class="back-link" [routerLink]="['/v', publicId()]">Retour à la boutique</a>
      </section>
    </g-public-shell>
  `,
  styles: [`
    :host {display:block;min-width:0}
    .direct-tracking {max-width:740px;margin:24px auto;padding:clamp(20px,4vw,36px);
      border:1px solid var(--border);border-radius:18px;background:var(--surface);
      display:grid;gap:18px;min-width:0}
    .eyebrow {font-size:11px;text-transform:uppercase;color:var(--primary);font-weight:700}
    h1 {font-size:clamp(24px,4vw,34px);letter-spacing:-.035em}
    h2 {font-size:18px}
    p {font-size:14px;line-height:1.6;color:var(--muted)}
    .tracking-ref strong {color:var(--text);overflow-wrap:anywhere}
    .tracking-data {display:grid;grid-template-columns:repeat(2,minmax(0,1fr));
      gap:16px;margin:0}
    .tracking-data div {min-width:0}
    .tracking-data dt {font-size:12px;color:var(--muted)}
    .tracking-data dd {margin:5px 0 0;font-size:14px;font-weight:700;overflow-wrap:anywhere}
    .notice {padding:14px;border:1px solid var(--hero-border);border-radius:12px;
      background:var(--primary-soft)}
    .action-link {display:inline-flex;align-items:center;justify-content:center;
      width:fit-content;min-height:44px;padding:10px 16px;border:0;border-radius:10px;
      background:var(--primary);color:var(--on-primary);cursor:pointer;font:inherit;font-weight:650}
    .back-link {font-size:13px;color:var(--primary);font-weight:650}
    @media(max-width:620px){.tracking-data{grid-template-columns:1fr}}
  `],
})
export class DirectOrderTrackingPage {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(DirectSalesApi);
  readonly publicId = signal('');
  readonly money = money;
  readonly busy = signal(false);
  readonly order = signal<DirectTrackingView | null>(null);
  readonly message = signal('');
  private token: string | null = null;
  private sequence = 0;

  constructor() {
    const params = this.route.paramMap.subscribe((map) => {
      this.publicId.set(map.get('publicIdentifier') ?? '');
    });
    const fragment = this.route.fragment.subscribe((value) => {
      const token = value?.startsWith('access=') ? value.slice('access='.length) : '';
      this.token = /^[A-Za-z0-9_-]{43}$/.test(token) ? token : null;
      void this.reload();
    });
    inject(DestroyRef).onDestroy(() => {
      this.sequence++;
      this.token = null;
      params.unsubscribe();
      fragment.unsubscribe();
    });
  }

  async reload() {
    const sequence = ++this.sequence;
    const token = this.token;
    this.order.set(null);
    this.message.set('');
    if (!token) {
      this.busy.set(false);
      this.message.set('Ce lien ne contient pas de jeton de suivi valide.');
      return;
    }
    this.busy.set(true);
    try {
      const data = await this.api.trackDirectOrder(token);
      if (sequence === this.sequence) this.order.set(data);
    } catch {
      if (sequence === this.sequence) {
        this.message.set('Ce lien est invalide ou cette commande n’est pas accessible.');
      }
    } finally {
      if (sequence === this.sequence) this.busy.set(false);
    }
  }
}
