import { ShippingTerms } from '../../api/generated/models';
export const transportOptions: {
  value: NonNullable<ShippingTerms['transportMode']>;
  label: string;
}[] = [
  { value: 'AIR', label: 'Avion' },
  { value: 'SEA', label: 'Bateau' },
  { value: 'ROAD', label: 'Route' },
  { value: 'OTHER', label: 'Autre' },
];
export const pricingOptions: {
  value: NonNullable<ShippingTerms['shippingPricingMethod']>;
  label: string;
}[] = [
  { value: 'PER_KG', label: 'Au kilogramme' },
  { value: 'PER_CBM', label: 'Au mètre cube' },
  { value: 'PER_ITEM', label: 'Par article' },
  { value: 'FIXED', label: 'Forfait' },
];
export const collectionOptions: {
  value: NonNullable<ShippingTerms['shippingCollectionMode']>;
  label: string;
}[] = [
  { value: 'AFTER_FINAL_ASSESSMENT', label: 'Après évaluation finale' },
  { value: 'ESTIMATED_AT_CHECKOUT', label: 'Estimation à la commande' },
];
export const optionLabel = (options: readonly { value: string; label: string }[], value?: string) =>
  options.find((o) => o.value === value)?.label ?? 'Non renseigné';
