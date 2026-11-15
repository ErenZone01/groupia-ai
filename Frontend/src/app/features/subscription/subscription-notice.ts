import { Component, effect, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { dateLabel } from '../../shared/utils/display';
import { SubscriptionStore } from './subscription.store';

@Component({
  selector: 'g-subscription-notice',
  imports: [RouterLink],
  template: `
    @if (store.details()?.access; as access) {
      @if (
        access.status === 'TRIALING' ||
        access.canStartNewBusiness === false ||
        store.details()?.renewal?.reminder === 'DUE_SOON'
      ) {
        <aside role="status">
          <p>
            @if (access.status === 'TRIALING') {
              Essai gratuit jusqu'au {{ date(access.trialEndsAt) }}.
            } @else if (store.details()?.renewal?.reminder === 'DUE_SOON') {
              Votre abonnement arrive à échéance le {{ date(store.details()?.renewal?.dueAt) }}.
            } @else {
              Un abonnement actif est requis pour ouvrir de nouvelles ventes directes ou de nouveaux
              groupages. Les opérations déjà engagées restent disponibles.
            }
          </p>
          <a routerLink="/app/abonnement">Voir l'abonnement</a>
        </aside>
      }
    }
  `,
  styles: `
    :host {
      display: block;
      padding: 0 var(--s8);
    }
    aside {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--s4);
      margin-top: var(--s4);
      padding: var(--s3) var(--s4);
      border: 1px solid var(--border);
      border-radius: var(--radius-md);
      background: var(--surface-alt);
      color: var(--text);
      font-size: var(--text-sm);
    }
    p {
      margin: 0;
    }
    a {
      color: var(--primary);
      font-weight: 600;
      white-space: nowrap;
    }
    @media (max-width: 1023px) {
      :host {
        padding: 0 var(--s5);
      }
    }
    @media (max-width: 599px) {
      aside {
        align-items: flex-start;
        flex-direction: column;
        gap: var(--s2);
      }
    }
  `,
})
export class SubscriptionNotice {
  readonly store = inject(SubscriptionStore);
  private readonly context = inject(BoutiqueContextService);
  readonly date = (value?: string) => (value ? dateLabel(value) : '—');

  constructor() {
    effect(() => {
      const organizationId = this.context.selectedId();
      if (organizationId) void this.store.load(organizationId);
      else this.store.clear();
    });
  }
}
