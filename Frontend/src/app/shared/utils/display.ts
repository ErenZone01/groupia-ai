const statuses: Record<string, string> = {
  DRAFT: 'Brouillon',
  OPEN: 'Ouvert aux commandes',
  CLOSED: 'Commandes fermées',
  SUPPLIER_ORDERED: 'Commandé chez le fournisseur',
  IN_TRANSIT: 'En transport',
  ARRIVED: 'Arrivé',
  DISTRIBUTING: 'Distribution',
  COMPLETED: 'Terminé',
  CANCELLED: 'Annulé',
  ACTIVE: 'Actif',
  ARCHIVED: 'Archivé',
  SUSPENDED: 'Suspendu',
};
export function statusLabel(value?: string) {
  return statuses[value ?? ''] ?? 'Indisponible';
}
const attributes: Record<string, string> = {
  color: 'Couleur',
  size: 'Taille',
  shoesize: 'Pointure',
  capacity: 'Capacité',
  material: 'Matière',
};
export function variantLabel(value?: Record<string, string>) {
  const entries = Object.entries(value ?? {});
  return entries.length
    ? entries.map(([key, v]) => (attributes[key.toLowerCase()] ?? key) + ' : ' + v).join(' · ')
    : 'Standard';
}
export function money(value?: number, currency = 'XOF') {
  if (value === undefined || !Number.isFinite(value)) return '—';
  if (Math.abs(value) > Number.MAX_SAFE_INTEGER) return 'Montant indisponible';
  return new Intl.NumberFormat('fr-FR', {
    style: 'currency',
    currency,
    maximumFractionDigits: 18,
  }).format(value);
}
export function dateLabel(value?: string) {
  return value
    ? new Intl.DateTimeFormat('fr-FR', { dateStyle: 'medium', timeStyle: 'short' }).format(
        new Date(value),
      )
    : 'Sans date de clôture';
}
export function safePrice(value: number | null): value is number {
  return value !== null && Number.isSafeInteger(value) && value >= 0;
}
