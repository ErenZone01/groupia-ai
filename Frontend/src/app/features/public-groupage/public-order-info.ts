import type { PublicGroupingView } from '../../api/generated/models';

/** Descriptions grounded in the public campaign's real shipping contract. */
export function publicShippingMessage(group: PublicGroupingView | null | undefined): string {
  switch (group?.shippingTerms?.shippingCollectionMode) {
    case 'AFTER_FINAL_ASSESSMENT':
      return 'Les frais de transport seront évalués après la commande, selon les conditions du groupage.';
    case 'ESTIMATED_AT_CHECKOUT':
      return 'Les frais de transport sont estimés lors de la commande. Vérifiez les montants avant de confirmer.';
    default:
      return 'Les frais de transport et les éventuels frais de service sont précisés dans le parcours de commande.';
  }
}

export function publicTransportLabel(group: PublicGroupingView | null | undefined): string {
  switch (group?.shippingTerms?.transportMode) {
    case 'AIR':
      return 'Transport aérien';
    case 'SEA':
      return 'Transport maritime';
    case 'ROAD':
      return 'Transport routier';
    case 'OTHER':
      return 'Autre mode de transport';
    default:
      return 'Transport à préciser';
  }
}
