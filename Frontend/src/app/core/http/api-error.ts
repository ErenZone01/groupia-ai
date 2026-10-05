import { HttpErrorResponse } from '@angular/common/http';
const messages: Record<string, string> = {
  CREDIT_NOT_STABLE_FOR_REFUND: 'Le crédit pourra être régularisé après la remise de la commande.',
  SHIPPING_CREDIT_NOT_DUE: 'Aucun crédit stable ne peut être régularisé. Actualisez la commande.',
  REFUND_ALREADY_EXISTS:
    'Une régularisation existe déjà. Consultez son état avant toute nouvelle action.',
  REFUND_EVIDENCE_REQUIRED:
    'Renseignez la référence externe ou la note de confirmation pour les espèces.',
  REFUND_CONFIRMATION_REQUIRED: 'Confirmez explicitement la restitution du crédit complet.',
  REFUND_RECIPIENT_UNSUPPORTED:
    'Le remboursement automatique exige le numéro sénégalais enregistré sur la commande.',
  PAYOUT_DISABLED:
    'Les remboursements Bictorys sont désactivés. Une restitution déjà effectuée peut être déclarée.',
  ORDER_FULFILLMENT_LOCKED:
    'Cette commande est prête ou déjà retirée. Retirez sa mise à disposition avant de corriger le transport.',
  ORDER_VERSION_CONFLICT: 'La commande a changé. Actualisez-la avant de confirmer à nouveau.',
  ORDER_FULFILLMENT_CONFLICT: 'Cette action n’est plus disponible. Actualisez la commande.',
  FULFILLMENT_SHIPPING_NOT_FINALIZED: 'Finalisez le transport avant de préparer le retrait.',
  FULFILLMENT_SHIPPING_BALANCE_DUE: 'Le solde transport doit être réglé avant la remise.',
  FULFILLMENT_CAMPAIGN_NOT_ARRIVED: 'Le groupage doit être arrivé avant la distribution.',
  FULFILLMENT_ORDER_NOT_CONFIRMED: 'Cette commande ne permet pas cette action.',
  FULFILLMENT_REASON_REQUIRED: 'Précisez le motif du retrait de la mise à disposition.',
  FULFILLMENT_CONFIRMATION_REQUIRED:
    'Confirmez explicitement la disponibilité ou la remise physique.',

  SHIPPING_BALANCE_PAYMENT_IN_PROGRESS:
    'Un paiement du transport est en cours de traitement. Attendez sa confirmation ou sa réconciliation avant de corriger le montant.',
  SHIPPING_VERSION_CONFLICT: 'Le transport a changé. Actualisez la commande.',
  SHIPPING_MEASUREMENT_STALE: 'La mesure a changé. Actualisez la commande.',
  SHIPPING_MEASUREMENT_REQUIRED:
    'Enregistrez les informations nécessaires au calcul : poids, volume ou dimensions complètes.',
  SHIPPING_CORRECTION_REASON_REQUIRED: 'Précisez le motif de cette correction.',
  SHIPPING_MEASUREMENT_CORRECTION_REQUIRED:
    'La correction doit désigner la dernière mesure et préciser son motif.',
  INVALID_SHIPPING_MEASUREMENT:
    'Renseignez des mesures strictement positives ; laissez vide ce qui est inconnu.',
  INVALID_SHIPPING_CALCULATION: 'Vérifiez le montant, la devise et le mode de calcul.',
  SHIPPING_ORDER_NOT_ELIGIBLE:
    'Cette commande ne peut plus être traitée. Actualisez les commandes.',
  SHIPPING_CAMPAIGN_NOT_ELIGIBLE: 'Le transport est disponible après la commande fournisseur.',

  INVALID_SHIPMENT_DATES: 'Vérifiez les dates de départ et d’arrivée.',
  INVALID_SHIPMENT_INFORMATION: 'Vérifiez les informations logistiques.',
  SHIPMENT_VERSION_CONFLICT: 'Le dossier logistique a changé. Actualisez-le.',
  INVALID_LOGISTICS_FACT: 'Vérifiez le type d’information et la note.',
  SHIPMENT_ALREADY_DEPARTED: 'Le départ est déjà confirmé. Actualisez le groupage.',
  SHIPMENT_ALREADY_ARRIVED: 'L’arrivée a déjà été enregistrée. Actualisez le groupage.',
  SHIPMENT_NOT_FOUND: 'Aucun départ enregistré. Actualisez le groupage.',
  INVALID_CUSTOMER_CONTACT: 'Vérifiez vos coordonnées, notamment le téléphone avec son indicatif.',
  INVALID_ORDER_QUANTITY: 'Vérifiez la quantité de chaque article.',
  INVALID_ORDER_LINES: 'Votre panier doit contenir entre 1 et 100 produits.',
  IDEMPOTENCY_KEY_REUSED: 'Cette tentative correspond à un autre contenu. Vérifiez votre panier.',
  GROUPING_NOT_ORDERABLE: 'Ce groupage ne reçoit plus de commandes.',
  ORDER_PAYMENT_EXPIRED: 'Le délai de paiement est expiré. Actualisez le suivi.',
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
