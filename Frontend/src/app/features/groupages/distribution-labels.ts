import type { FulfillmentRow } from '../../api/generated/models';
export const physicalLabel = (value?: string) =>
  ({
    NOT_READY: 'À préparer',
    READY_FOR_PICKUP: 'Prête à être récupérée',
    PICKED_UP: 'Commande récupérée',
    CANCELLED: 'Annulée',
  })[value ?? ''] ?? 'À vérifier';
export const blockerLabel = (code?: string) =>
  ({
    FULFILLMENT_SHIPPING_NOT_FINALIZED: 'Transport à finaliser',
    FULFILLMENT_SHIPPING_BALANCE_DUE: 'En attente de paiement',
    SHIPPING_BALANCE_PAYMENT_IN_PROGRESS: 'Paiement en cours de vérification',
    FULFILLMENT_CAMPAIGN_NOT_ARRIVED: 'En attente de l’arrivée du groupage',
    FULFILLMENT_ORDER_NOT_CONFIRMED: 'Commande terminée ou non confirmée',
  })[code ?? ''] ?? '';
export const historyLabel = (type?: string) =>
  ({
    READY_FOR_PICKUP: 'Mise à disposition',
    READINESS_REVOKED: 'Mise à disposition retirée',
    PICKED_UP: 'Remise confirmée',
  })[type ?? ''] ?? 'Action enregistrée';
export function filterDistribution(rows: FulfillmentRow[], search: string, filter: string) {
  const normalize = (text: string) =>
    text.normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase('fr');
  const query = normalize(search).trim();
  return rows.filter((row) => {
    if (!normalize(row.orderNumber + ' ' + row.customerName).includes(query)) return false;
    if (filter === 'eligible')
      return row.fulfillmentStatus === 'NOT_READY' && !row.readinessBlocker;
    if (filter === 'payment')
      return (
        row.shippingSettlement?.status === 'BALANCE_DUE' ||
        row.shippingSettlement?.paymentInProgress
      );
    if (filter === 'credit') return row.shippingSettlement?.status === 'CREDIT_DUE';
    return filter === 'all' || row.fulfillmentStatus === filter;
  });
}
