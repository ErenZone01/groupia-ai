import { Component, input } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ShippingExplanation as Explanation } from '../../api/generated/models';
import { money } from '../utils/display';
const modes: Record<string, string> = {
  AIR: 'Avion',
  SEA: 'Bateau',
  ROAD: 'Route',
  OTHER: 'Autre',
};
const methods: Record<string, string> = {
  ACTUAL_WEIGHT: 'Poids réel communiqué',
  COMMUNICATED_CHARGEABLE_WEIGHT: 'Poids facturable communiqué',
  MAX_ACTUAL_VOLUMETRIC: 'Maximum du poids réel et volumétrique',
  COMMUNICATED_VOLUME: 'Volume communiqué',
  DIMENSION_VOLUME: 'Volume calculé depuis les dimensions',
  ITEM_COUNT: 'Quantités de la commande',
  FIXED: 'Forfait par commande',
};
const sources: Record<string, string> = {
  FORWARDER: 'Prestataire logistique',
  SUPPLIER: 'Fournisseur',
  MANAGER: 'Boutique',
  DOCUMENT: 'Document reçu',
  OTHER: 'Autre source',
  UNKNOWN: 'Non précisée',
};
@Component({
  selector: 'g-shipping-explanation',
  imports: [DecimalPipe],
  template: ` <div class="explanation">
    <p>
      <strong>{{ money(value().amount, value().currency) }}</strong>
    </p>
    @if (value().origin === 'PROVIDER_QUOTED') {
      <p>Montant communiqué tel quel, sans formule de calcul fournie.</p>
    } @else {
      <p>Calculé par Groupia selon les conditions du groupage.</p>
      <p>{{ methods[value().method ?? ''] }}</p>
      @if (value().actualWeightKg != null) {
        <p>Poids réel : {{ value().actualWeightKg | number: '1.0-9' }} kg</p>
      }
      @if (value().volumetricWeightKg != null) {
        <p>Poids volumétrique : {{ value().volumetricWeightKg | number: '1.0-9' }} kg</p>
        <p>
          Dimensions : {{ value().lengthCm }} × {{ value().widthCm }} × {{ value().heightCm }} cm ·
          Diviseur : {{ value().volumetricDivisor }}
        </p>
      }
      @if (value().method === 'DIMENSION_VOLUME') {
        <p>
          Dimensions : {{ value().lengthCm }} × {{ value().widthCm }} × {{ value().heightCm }} cm ÷
          1 000 000
        </p>
      }
      <p>Quantité retenue : {{ value().billableQuantity | number: '1.0-9' }} {{ unit() }}</p>
      <p>Tarif convenu : {{ money(value().rate, value().currency) }} / {{ unit() }}</p>
      <p>
        Calcul : {{ value().billableQuantity | number: '1.0-9' }} ×
        {{ money(value().rate, value().currency) }}
      </p>
      <p class="muted">
        Montant arrondi au franc CFA le plus proche. Les quantités affichées peuvent être abrégées.
      </p>
    }
    <p>
      Mode : {{ modes[value().transportMode ?? ''] }} · Source :
      {{ sources[value().source ?? 'UNKNOWN'] }}
    </p>
  </div>`,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    .explanation {
      padding: var(--s4);
      background: var(--surface-alt);
      border-radius: var(--radius-md);
      overflow-wrap: anywhere;
    }
    p {
      margin: 0 0 var(--s2);
    }
  `,
})
export class ShippingExplanation {
  readonly value = input.required<Explanation>();
  readonly money = money;
  readonly modes = modes;
  readonly methods = methods;
  readonly sources = sources;
  unit() {
    return (
      ({ KG: 'kg', CBM: 'm³', ITEM: 'article', ORDER: 'commande' } as Record<string, string>)[
        this.value().billableUnit ?? ''
      ] ?? ''
    );
  }
}
