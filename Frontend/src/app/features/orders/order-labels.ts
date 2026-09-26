export const orderFilters = [
  { value: '', label: 'Toutes', empty: 'Aucune commande pour le moment.' },
  { value: 'CONFIRMED', label: 'Payées', empty: 'Aucune commande payée.' },
  { value: 'PENDING_PAYMENT', label: 'En attente', empty: 'Aucune commande en attente.' },
  { value: 'EXPIRED', label: 'Expirées', empty: 'Aucune commande expirée.' },
  { value: 'CANCELLED', label: 'Annulées', empty: 'Aucune commande annulée.' },
] as const;
export function orderFilter(value: string | null | undefined) {
  return orderFilters.find((f) => f.value === value)?.value ?? '';
}
export function orderPage(value: string | null | undefined) {
  const n = Number(value);
  return Number.isInteger(n) && n >= 0 && n <= 1000000 ? n : 0;
}
export function orderLabel(status?: string) {
  return (
    (
      {
        PENDING_PAYMENT: 'En attente',
        CONFIRMED: 'Payée',
        EXPIRED: 'Expirée',
        CANCELLED: 'Annulée',
        COMPLETED: 'Terminée',
      } as Record<string, string>
    )[status ?? ''] ?? 'Statut indisponible'
  );
}
export function orderTone(status?: string): 'positive' | 'warning' | 'danger' | 'neutral' {
  return status === 'CONFIRMED'
    ? 'positive'
    : status === 'PENDING_PAYMENT'
      ? 'warning'
      : status === 'EXPIRED'
        ? 'danger'
        : 'neutral';
}
export function paymentLabel(status?: string) {
  return (
    (
      {
        PENDING_PAYMENT: 'En attente de paiement',
        CONFIRMED: 'Paiement confirmé',
        EXPIRED: 'Paiement expiré',
        CANCELLED: 'Commande annulée',
        COMPLETED: 'Commande terminée',
      } as Record<string, string>
    )[status ?? ''] ?? 'Information indisponible'
  );
}
export function orderDate(value?: string) {
  if (!value || !Number.isFinite(Date.parse(value))) return 'Date indisponible';
  return new Intl.DateTimeFormat('fr-FR', { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(value),
  );
}
export function fulfillmentLabel(value?: string) {
  return (
    (
      {
        NOT_READY: 'En préparation',
        READY_FOR_PICKUP: 'Prête à récupérer',
        PICKED_UP: 'Récupérée',
        CANCELLED: 'Annulée',
      } as Record<string, string>
    )[value ?? ''] ?? 'Information indisponible'
  );
}
