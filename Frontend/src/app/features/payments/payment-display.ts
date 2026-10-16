import type { PaymentRequestSummaryView } from '../../api/generated/models';
export { money } from '../../shared/utils/display';
const labels: Record<string, string> = {
  PENDING: 'En attente',
  SETTLED: 'Réglé',
  REVIEW_REQUIRED: 'À vérifier',
  TERMINAL_UNPAID: 'Terminé sans règlement',
  INITIAL_ORDER: 'Commande',
  SHIPPING_BALANCE: 'Transport',
  SUBSCRIPTION_MONTHLY: 'Abonnement',
  OPEN: 'Ouverte',
  CLOSED: 'Fermée',
  CANCELLED: 'Annulée',
  SUPERSEDED: 'Remplacée',
  CREATED: 'Créée',
  SUCCEEDED: 'Réussie',
  FAILED: 'Échouée',
  EXPIRED: 'Expirée',
  RESOLVED_REFUNDED: 'Régularisé',
  PAYMENT_REVIEW_OPEN: 'Un paiement supplémentaire est à examiner.',
  PAYMENT_RECONCILIATION_REQUIRED: 'Une vérification financière est nécessaire.',
  PAYMENT_PROVIDER_REVERSAL: 'Le prestataire signale une annulation de fonds.',
  PAYMENT_PROVIDER_STATE_UNCERTAIN: 'Le statut du prestataire reste incertain.',
  PAYMENT_TRANSACTION_INCONSISTENT: 'Les preuves de la transaction sont incohérentes.',
  PAYMENT_ALLOCATION_INCONSISTENT: 'Le règlement principal est incohérent.',
  PAYMENT_INCONSISTENT: 'Le montant, la devise ou la transaction est incohérent.',
  PAYMENT_UNALLOCATED_SUCCESS: 'Un succès supplémentaire reste à régulariser.',
  PAYMENT_IN_PROGRESS: 'Une tentative de paiement est en cours.',
  PAYMENT_ZERO_AMOUNT_REQUIRES_REVIEW: 'Le montant nul nécessite un examen.',
  PAYMENT_CLASSIFICATION_UNCERTAIN: 'Cette combinaison financière nécessite un examen.',
  DEADLINE_EXCEEDED: 'Échéance dépassée',
  DUPLICATE_PROVIDER_SUCCESS: 'Paiement supplémentaire',
  PROVIDER_REVERSAL: 'Annulation de fonds signalée',
  PROVIDER_STATE_UNCERTAIN: 'Statut du prestataire incertain',
  TRANSACTION_MISMATCH: 'Transaction incohérente',
};
export function paymentLabel(value?: string | null) {
  return value ? (labels[value] ?? 'À examiner avec l’équipe') : 'Non renseigné';
}
export function paymentDate(value?: string | null) {
  if (!value) return 'Non renseignée';
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? 'Date indisponible'
    : new Intl.DateTimeFormat('fr-FR', { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}
export function paymentTone(
  status?: PaymentRequestSummaryView['operationalStatus'],
): 'neutral' | 'positive' | 'warning' | 'danger' {
  return status === 'SETTLED'
    ? 'positive'
    : status === 'REVIEW_REQUIRED'
      ? 'danger'
      : status === 'PENDING'
        ? 'warning'
        : 'neutral';
}
