import { AddShipmentFact, ShipCampaign } from '../../api/generated/models';
export const logisticsSources: { value: NonNullable<ShipCampaign['source']>; label: string }[] = [
  { value: 'UNKNOWN', label: 'Non précisé' },
  { value: 'FORWARDER', label: 'Le transitaire / prestataire' },
  { value: 'SUPPLIER', label: 'Le fournisseur' },
  { value: 'MANAGER', label: 'Constatée par moi' },
  { value: 'DOCUMENT', label: 'Un document' },
  { value: 'OTHER', label: 'Autre' },
];
export const logisticsFactOptions: {
  value: NonNullable<AddShipmentFact['type']>;
  label: string;
}[] = [
  { value: 'INFORMATION', label: 'Information / correction' },
  { value: 'FORWARDER_RECEIVED', label: 'Marchandises reçues par le prestataire' },
  { value: 'CUSTOMS_STARTED', label: 'Formalités en cours' },
  { value: 'CUSTOMS_COMPLETED', label: 'Formalités terminées' },
  { value: 'AVAILABLE_AT_FORWARDER', label: 'Disponible chez le prestataire' },
  { value: 'COLLECTED_BY_MANAGER', label: 'Récupéré par la gérante' },
];
const factNames: Record<string, string> = {
  ...Object.fromEntries(logisticsFactOptions.map((o) => [o.value, o.label])),
  DOSSIER_UPDATED: 'Dossier renseigné',
  DEPARTURE_CONFIRMED: 'Départ confirmé',
  ETA_UPDATED: 'Arrivée estimée révisée',
  ARRIVAL_CONFIRMED: 'Arrivée à destination confirmée',
};
export const logisticsFactLabel = (type?: string) =>
  factNames[type ?? ''] ?? 'Information logistique';
export const logisticsSourceLabel = (source?: string) =>
  logisticsSources.find((o) => o.value === source)?.label ?? 'Non précisé';
