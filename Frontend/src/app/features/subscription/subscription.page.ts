import { Component, effect, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import type { SubscriptionAccessView } from '../../api/generated/models';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { Card } from '../../shared/ui/card';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { Skeleton } from '../../shared/feedback/skeleton';
import { dateLabel, money } from '../../shared/utils/display';
import { SubscriptionStore } from './subscription.store';

const statusLabels: Record<string, string> = {
  TRIALING: 'Essai gratuit',
  ACTIVE: 'Actif',
  PAST_DUE: 'À renouveler',
  SUSPENDED: 'Suspendu',
  CANCELED: 'Annulé',
};

@Component({
  selector: 'g-subscription-page',
  imports: [RouterLink, Card, Button, Input, Skeleton],
  template: `
    <div class="page-heading">
      <div>
        <p class="eyebrow">Votre boutique</p>
        <h1>Abonnement</h1>
      </div>
      <a gButton variant="secondary" routerLink="/app/boutique">Ma boutique</a>
    </div>
    @if (store.error()) {
      <p class="notice" role="alert">{{ store.error() }}</p>
    }
    @if (store.loading() && !store.details()) {
      <g-skeleton />
      <g-skeleton />
    }
    @if (store.details(); as details) {
      @if (details.access; as access) {
        <div class="sections">
          <g-card>
            <h2>Accès aux nouvelles ventes et aux nouveaux groupages</h2>
            <p class="status">{{ statusLabel(access.status) }}</p>
            @if (access.status === 'TRIALING') {
              <p>Votre essai gratuit se termine le {{ date(access.trialEndsAt) }}.</p>
            } @else if (access.status === 'ACTIVE') {
              <p>Votre période payée se termine le {{ date(access.currentPeriodEndsAt) }}.</p>
            } @else {
              <p>
                Votre abonnement a expiré ou nécessite une vérification. Renouvelez pour un nouveau
                mois d’accès. Les commandes, les groupages déjà ouverts et les opérations en cours
                restent accessibles.
              </p>
            }
            @if (!details.billingProviderAvailable) {
              <p class="notice">
                Le paiement d'abonnement n'est pas disponible pour le moment. Aucun nouveau paiement
                ne sera envoyé.
              </p>
            } @else if (!details.planConfigured) {
              <p class="notice">Le tarif n'est pas encore configuré.</p>
            }
            @if (store.futurePaid(); as paid) {
              <p class="notice">
                Prochaine période déjà réglée : {{ date(paid.periodStart) }} →
                {{ date(paid.periodEnd) }}.
              </p>
            } @else if (store.currentInvoice(); as invoice) {
              <p>
                {{ date(invoice.periodStart) }} → {{ date(invoice.periodEnd) }} ·
                {{ money(invoice.amount, invoice.currency) }}
              </p>
              @if (invoice.paymentStatus === 'REVIEW_REQUIRED') {
                <p class="notice" role="status">Paiement à vérifier.</p>
              } @else if (invoice.paymentStatus === 'SETTLED') {
                <p class="notice" role="status">
                  Paiement reçu. Activation en cours de traitement.
                </p>
              } @else if (invoice.attemptInProgress) {
                <p class="notice" role="status">Paiement en attente de confirmation.</p>
              }
              @if (
                details.canManageSubscription &&
                invoice.canPay &&
                (details.availableProviders ?? ['BICTORYS']).includes('BICTORYS')
              ) {
                <button
                  gButton
                  [disabled]="store.busy() || !details.billingProviderAvailable"
                  (click)="store.pay()"
                >
                  Payer avec Bictorys
                </button>
              }
            } @else {
              @if (store.preview(); as preview) {
                <p>
                  Prochaine période : {{ date(preview.periodStart) }} →
                  {{ date(preview.periodEnd) }}
                </p>
                <p>{{ money(preview.amount, preview.currency) }}</p>
              }
              @if (
                details.canManageSubscription &&
                (details.availableProviders ?? ['BICTORYS']).includes('BICTORYS')
              ) {
                <button
                  gButton
                  [disabled]="
                    store.busy() ||
                    !details.billingProviderAvailable ||
                    !details.planConfigured ||
                    !details.billingContact?.phoneE164
                  "
                  (click)="store.pay()"
                >
                  {{ access.status === 'TRIALING' ? 'Payer maintenant' : 'Payer avec Bictorys' }}
                </button>
              }
            }
            @if (
              details.renewal?.reminder === 'DUE_SOON' || details.renewal?.reminder === 'OVERDUE'
            ) {
              <p class="notice" role="status">
                Renouvellement à prévoir : {{ date(details.renewal?.dueAt) }}.
              </p>
            }
            @if (
              details.canManageSubscription &&
              store.hasProvider('SASPAY') &&
              !store.futurePaid() &&
              (!store.currentInvoice() || store.currentInvoice()?.canPay)
            ) {
              <button
                gButton
                variant="secondary"
                [disabled]="store.busy()"
                (click)="store.prepareSaspay()"
              >
                Voir les frais SasPay
              </button>
            }
            @if (details.canManageSubscription) {
              @for (quote of store.quotes(); track quote.id) {
                <article>
                  <h3>{{ quote.networkLabel }}</h3>
                  <p>
                    Abonnement : {{ money(quote.saleAmount, quote.currency) }} · Frais
                    d’encaissement à votre charge : {{ money(quote.collectionFee, quote.currency) }}
                  </p>
                  <p>
                    Total à payer : <strong>{{ money(quote.totalCharged, quote.currency) }}</strong>
                  </p>
                  <button
                    gButton
                    [disabled]="
                      store.busy() ||
                      !!store.currentInvoice()?.reconciliationRequired ||
                      store.currentInvoice()?.paymentStatus === 'SETTLED' ||
                      !!store.futurePaid()
                    "
                    (click)="store.pay(quote)"
                  >
                    Accepter les frais et payer {{ money(quote.totalCharged, quote.currency) }}
                  </button>
                </article>
              }
              @if (store.acceptedQuote(); as quote) {
                <p>
                  Montant accepté : {{ money(quote.totalCharged, quote.currency) }}, dont
                  {{ money(quote.collectionFee, quote.currency) }} de frais.
                </p>
              }
              @if (store.phoneConfirmation()) {
                <p role="status">
                  Confirmez la demande sur votre téléphone, puis vérifiez le paiement.
                </p>
              }
            }
            @if (details.canManageSubscription && store.checkoutUrl(); as url) {
              <a gButton [href]="url" target="_blank" rel="noopener noreferrer"
                >Ouvrir le paiement sécurisé</a
              >
            }
            <button
              gButton
              variant="secondary"
              [disabled]="store.loading() || store.busy()"
              (click)="store.refresh()"
            >
              Vérifier le paiement
            </button>
            <p class="help">
              Renouvellement manuel : chaque période est payée à votre initiative, sans prélèvement
              automatique. Seule la confirmation serveur valide le règlement.
            </p>
          </g-card>

          <g-card>
            <h2>Coordonnées de facturation</h2>
            @if (details.canManageSubscription) {
              <form (submit)="saveContact($event)">
                <label>
                  Nom du titulaire
                  <input
                    gInput
                    name="name"
                    required
                    maxlength="160"
                    [value]="name"
                    (input)="name = inputValue($event)"
                  />
                </label>
                <label>
                  Téléphone sénégalais
                  <input
                    gInput
                    name="phone"
                    type="tel"
                    required
                    pattern="\\+221[0-9]{9}"
                    placeholder="+221771234567"
                    [value]="phone"
                    (input)="phone = inputValue($event)"
                  />
                </label>
                <label>
                  Courriel <span>(nécessaire avec SasPay, ainsi que le prénom et le nom)</span>
                  <input
                    gInput
                    name="email"
                    type="email"
                    maxlength="254"
                    [value]="email"
                    (input)="email = inputValue($event)"
                  />
                </label>
                <button gButton type="submit" [disabled]="store.busy()">
                  Enregistrer les coordonnées
                </button>
              </form>
            } @else {
              <p>{{ details.billingContact?.name }}</p>
              <p>{{ details.billingContact?.phoneE164 }}</p>
              <p>{{ details.billingContact?.email || 'Aucun courriel renseigné.' }}</p>
              <p>Seule la propriétaire peut modifier les coordonnées de facturation.</p>
            }
          </g-card>

          <g-card>
            <h2>Factures</h2>
            @for (invoice of store.invoices(); track invoice.id) {
              <article>
                <p>
                  {{ date(invoice.periodStart) }} → {{ date(invoice.periodEnd) }} ·
                  {{ money(invoice.amount, invoice.currency) }}
                </p>
                <p>
                  {{
                    invoice.status === 'PAID'
                      ? 'Payée'
                      : invoice.status === 'EXPIRED'
                        ? 'Expirée'
                        : 'À régler'
                  }}
                </p>
              </article>
            } @empty {
              <p>Aucune facture émise.</p>
            }
            <h2>Historique des paiements</h2>
            @for (period of store.history(); track period.periodStart) {
              <article>
                <h3>{{ money(period.amount, period.currency) }} · {{ period.planCode }}</h3>
                <p>
                  {{ date(period.periodStart) }} – {{ date(period.periodEnd) }} ·
                  {{ period.status === 'PAID' ? 'Réglé' : 'En attente' }}
                </p>
                @if (period.paidAt) {
                  <p>Confirmation : {{ date(period.paidAt) }}</p>
                }
              </article>
            } @empty {
              <p>Aucune période payée enregistrée.</p>
            }
          </g-card>
        </div>
      }
    }
  `,
  styles: `
    .sections {
      display: grid;
      gap: var(--s5);
      min-width: 0;
    }
    h2 {
      margin-bottom: var(--s3);
    }
    .status {
      color: var(--primary);
      font-weight: 600;
    }
    p {
      margin: var(--s3) 0;
    }
    .notice {
      padding: var(--s3);
      border-radius: var(--radius-sm);
      background: var(--surface-alt);
    }
    .help,
    label span {
      color: var(--muted);
      font-size: var(--text-xs);
    }
    form {
      display: grid;
      gap: var(--s4);
      max-width: 32rem;
    }
    label {
      display: grid;
      gap: var(--s2);
      font-size: var(--text-sm);
    }
    article {
      padding: var(--s4) 0;
      border-bottom: 1px solid var(--border);
    }
    article:last-child {
      border-bottom: 0;
    }
  `,
})
export class SubscriptionPage {
  readonly store = inject(SubscriptionStore);
  private readonly context = inject(BoutiqueContextService);
  name = '';
  phone = '';
  email = '';
  readonly money = money;
  readonly date = (value?: string) => (value ? dateLabel(value) : '—');
  readonly statusLabel = (value?: SubscriptionAccessView['status']) =>
    statusLabels[value ?? ''] ?? 'Indisponible';

  constructor() {
    effect(() => {
      const organizationId = this.context.selectedId();
      if (!organizationId) {
        this.store.clear();
        return;
      }
      void this.store.load(organizationId);
    });
    effect(() => {
      const contact = this.store.details()?.billingContact;
      this.name = contact?.name ?? '';
      this.phone = contact?.phoneE164 ?? '';
      this.email = contact?.email ?? '';
    });
  }

  inputValue(event: Event): string {
    return event.target instanceof HTMLInputElement ? event.target.value : '';
  }

  async saveContact(event: Event) {
    event.preventDefault();
    await this.store.saveBillingContact({
      name: this.name.trim(),
      phoneE164: this.phone.trim(),
      email: this.email.trim() || undefined,
    });
  }
}
