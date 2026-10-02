import { Component, input } from '@angular/core';
import { LogisticsFactView } from '../../api/generated/models';
import { dateLabel } from '../../shared/utils/display';
import { logisticsFactLabel, logisticsSourceLabel } from './logistics-labels';
@Component({
  selector: 'g-logistics-timeline',
  template: `<section aria-label="Historique logistique">
    <h3>Historique logistique</h3>
    <p class="muted">Informations renseignées par votre boutique, dans l’ordre de saisie.</p>
    <ol>
      @for (fact of facts(); track fact.id) {
        <li>
          <strong>{{ label(fact.type) }}</strong>
          <p class="muted">Enregistré le {{ date(fact.recordedAt) }} · {{ source(fact.source) }}</p>
          @if (fact.occurredAt) {
            <p>Date communiquée : {{ date(fact.occurredAt) }}</p>
          }
          @if (fact.legacy) {
            <p class="muted">Historique importé ; précision de la date d’origine non garantie.</p>
          }
          @if (fact.type === 'ETA_UPDATED') {
            <p>
              Estimation précédente :
              {{
                fact.previousExpectedArrivalAt
                  ? date(fact.previousExpectedArrivalAt)
                  : 'Non renseignée'
              }}
            </p>
            <p>
              Nouvelle estimation :
              {{ fact.expectedArrivalAt ? date(fact.expectedArrivalAt) : 'Non renseignée' }}
            </p>
          }
          @if (fact.type === 'DOSSIER_UPDATED') {
            <p>Prestataire : {{ fact.providerName || 'Non renseigné' }}</p>
            @if (fact.providerContact) {
              <p>Contact privé : {{ fact.providerContact }}</p>
            }
            @if (fact.trackingReference) {
              <p>Référence privée : {{ fact.trackingReference }}</p>
            }
          }
          @if (fact.note) {
            <p class="preserve">{{ fact.note }}</p>
          }
        </li>
      } @empty {
        <li>Aucune information enregistrée.</li>
      }
    </ol>
  </section>`,
  styles: `
    section {
      margin-top: var(--s6);
    }
    ol {
      padding-left: var(--s5);
    }
    li {
      padding: var(--s3) 0;
      overflow-wrap: anywhere;
    }
    p {
      margin: var(--s2) 0;
      font-size: var(--text-sm);
    }
  `,
})
export class LogisticsTimeline {
  readonly facts = input<LogisticsFactView[]>([]);
  readonly label = logisticsFactLabel;
  readonly source = logisticsSourceLabel;
  readonly date = dateLabel;
}
