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
            <h2>Accès aux nouveaux groupages</h2>
            <p class="status">{{ statusLabel(access.status) }}</p>
            @if (access.status === 'TRIALING') {
              <p>Votre essai gratuit se termine le {{ date(access.trialEndsAt) }}.</p>
            } @else if (access.status === 'ACTIVE') {
              <p>Votre période payée se termine le {{ date(access.currentPeriodEndsAt) }}.</p>
            } @else {
              <p>
                Un abonnement actif est nécessaire pour créer ou ouvrir un nouveau groupage. Les
                groupages déjà ouverts et les opérations en cours restent accessibles.
              </p>
            }
            @if (access.cancelAtPeriodEnd) {
              <p class="notice">L'abonnement ne sera pas renouvelé à la fin de la période.</p>
            }
            @if (!details.billingProviderAvailable) {
              <p class="notice">
                Le paiement d'abonnement n'est pas disponible pour le moment. Aucun nouveau
                paiement ne sera envoyé.
              </p>
            } @else if (!details.planConfigured) {
              <p class="notice">Le tarif et le plan ne sont pas encore configurés.</p>
            } @else if (!details.billingContact?.phoneE164) {
              <p class="notice">
                Renseignez un téléphone de facturation avant de configurer le paiement.
              </p>
            }
            @if (details.canManageSubscription) {
              <div class="actions">
                <button
                  gButton
                  [disabled]="
                    store.busy() ||
                    !details.billingProviderAvailable ||
                    !details.planConfigured ||
                    !details.billingContact?.phoneE164
                  "
                  (click)="store.start()"
                >
                  Configurer le paiement
                </button>
                @if (!access.cancelAtPeriodEnd) {
                  <button
                    gButton
                    variant="secondary"
                    [disabled]="
                      store.busy() ||
                      details.billingProviderSetupPending ||
                      (access.status === 'ACTIVE' && !details.billingProviderAvailable)
                    "
                    (click)="store.cancel()"
                  >
                    Annuler le renouvellement
                  </button>
                }
              </div>
              <p class="help">
                Le montant est déterminé par Groupia. La configuration d'un moyen de paiement ne
                confirme pas le règlement.
              </p>
            }
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
                  Courriel <span>(facultatif)</span>
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
            <h2>Historique des paiements</h2>
            @for (period of store.history(); track period.paidAt) {
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
      if (!organizationId) return;
      void this.store.load(organizationId).then(() => {
        if (this.context.selectedId() !== organizationId) return;
        const contact = this.store.details()?.billingContact;
        this.name = contact?.name ?? this.context.selected()?.name ?? '';
        this.phone = contact?.phoneE164 ?? '';
        this.email = contact?.email ?? '';
      });
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
