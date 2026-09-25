import { HttpErrorResponse } from '@angular/common/http';
const messages: Record<string, string> = {
  INVALID_CREDENTIALS: 'Le numéro ou le mot de passe est incorrect.',
  PHONE_ALREADY_REGISTERED: 'Ce numéro possède déjà un compte. Connectez-vous.',
  INVALID_PHONE_NUMBER: 'Vérifiez le numéro de téléphone, avec son indicatif.',
  INVALID_PASSWORD: 'Le mot de passe ne respecte pas les critères indiqués.',
  PRODUCT_VERSION_CONFLICT: 'Ce produit a été modifié. Actualisez les informations.',
  GROUPING_CAMPAIGN_VERSION_CONFLICT:
    'Ce groupage a été modifié. Nous avons actualisé les informations.',
  GROUPING_OFFER_PRICE_CONFLICT: 'Le prix a changé. Nous avons actualisé les informations.',
  GROUPING_OFFER_PRICE_CHANGED: 'Le prix a changé.',
  DUPLICATE_VARIANT: 'Cette combinaison d’options existe déjà.',
  SKU_ALREADY_EXISTS: 'Cette référence produit est déjà utilisée dans votre boutique.',
  INVALID_SKU: 'La référence doit contenir au plus 64 lettres, chiffres ou caractères . _ / -.',
  INVALID_VARIANT_ATTRIBUTES: 'Vérifiez les options de vos variantes.',
  INVALID_VARIANT_ESTIMATE: 'Le poids et le volume doivent être strictement positifs.',
  PRODUCT_ARCHIVED: 'Ce produit est archivé.',
  GROUPING_OFFER_ALREADY_EXISTS: 'Cette variante est déjà dans le groupage.',
  GROUPING_OFFER_NOT_AVAILABLE: 'Ce produit n’est plus disponible pour ce groupage.',
  GROUPING_HAS_NO_ACTIVE_OFFERS: 'Ajoutez au moins un produit actif avant de publier.',
  GROUPING_CAMPAIGN_NOT_OPENABLE: 'Vérifiez la livraison et choisissez une clôture future.',
  INVALID_GROUPING_DEADLINE: 'La date de clôture doit être future.',
  INVALID_GROUPING_OFFER_PRICE: 'Indiquez un prix entier en francs CFA, positif ou nul.',
  SHIPPING_TERMS_FROZEN: 'La livraison ne peut plus être modifiée après publication.',
  INVALID_SHIPPING_TERMS:
    'Vérifiez les conditions de livraison et les estimations de poids ou volume des produits.',
  INVALID_GROUPING_TRANSITION: 'Cette action n’est plus disponible pour ce groupage.',
  GROUPING_ALREADY_CLOSED: 'Ce groupage est déjà fermé.',
  GROUPING_CANCELLED: 'Ce groupage est annulé.',
};
export function apiCode(error: unknown): string {
  return error instanceof HttpErrorResponse && typeof error.error?.code === 'string'
    ? error.error.code
    : '';
}
export function versionConflict(error: unknown): boolean {
  return ['GROUPING_CAMPAIGN_VERSION_CONFLICT', 'GROUPING_OFFER_PRICE_CONFLICT'].includes(
    apiCode(error),
  );
}
export function apiMessage(error: unknown): string {
  const code = apiCode(error);
  if (messages[code]) return messages[code];
  if (error instanceof HttpErrorResponse) {
    const status: Record<number, string> = {
      0: 'Connexion au serveur impossible. Réessayez dans un instant.',
      400: 'Vérifiez les informations saisies.',
      401: 'Votre session a expiré. Connectez-vous à nouveau.',
      403: 'Vous n’êtes pas autorisée à effectuer cette action.',
      404: 'Cette ressource est introuvable.',
      409: 'Les informations ont changé ou cette action n’est plus possible. Actualisez avant de réessayer.',
      422: 'Vérifiez les informations saisies.',
      429: 'Trop de tentatives. Patientez avant de réessayer.',
    };
    return status[error.status] ?? 'Une erreur est survenue. Réessayez dans un instant.';
  }
  return 'Une erreur est survenue. Réessayez dans un instant.';
}
