import { HttpErrorResponse } from '@angular/common/http';
/** These errors are raised before a durable financial attempt exists. */
export function rejectedFeeQuote(error: unknown): boolean {
  return (
    error instanceof HttpErrorResponse &&
    error.status === 409 &&
    ['PAYMENT_QUOTE_INVALID', 'PAYMENT_QUOTE_REQUIRED', 'SASPAY_PAYER_REQUIRED'].includes(
      error.error?.code,
    )
  );
}
const messages: Record<string, string> = {
  MEDIA_DISABLED: 'Les images ne sont pas encore disponibles pour cette boutique.',
  MEDIA_LIMIT_REACHED: 'Un produit peut contenir cinq images maximum.',
  MEDIA_GALLERY_VERSION_CONFLICT: 'La galerie a changé. Son ordre a été rechargé ; réessayez.',
  MEDIA_IDEMPOTENCY_CONFLICT:
    'Cet envoi correspond à une autre image. Sélectionnez à nouveau le fichier.',
  MEDIA_PROCESSING_BUSY: 'Un traitement est en cours. Réessayez dans quelques instants.',
  MEDIA_TOO_LARGE: 'Cette image dépasse la limite de 8 Mo.',
  MEDIA_DIMENSIONS_EXCEEDED:
    'Cette image est trop grande : 6 000 pixels par côté et 24 mégapixels maximum.',
  MEDIA_UNSUPPORTED_FORMAT: 'Choisissez une image JPEG, PNG ou WebP statique.',
  MEDIA_CONTENT_TYPE_MISMATCH: 'Le format réel du fichier ne correspond pas au format annoncé.',
  MEDIA_INVALID_IMAGE: 'Cette image est illisible ou corrompue. Choisissez un autre fichier.',
  MEDIA_ANIMATION_UNSUPPORTED: 'Les images animées ne sont pas prises en charge.',
  SASPAY_PAYER_REQUIRED:
    'Pour utiliser SasPay, créez une commande avec prénom, nom, téléphone sénégalais et email valides.',
  SASPAY_PILOT_ORGANIZATION_NOT_ALLOWED:
    "Le paiement pilote SasPay n'est pas encore autorisé pour cette boutique.",
  SASPAY_NO_COMPATIBLE_QUOTE:
    "Aucun moyen SasPay compatible avec ce montant. Les frais ou les limites du pilote peuvent en être la cause.",
  SUBSCRIPTION_REQUIRED:
    "L'essai gratuit est terminé. Un abonnement actif est requis pour ouvrir de nouvelles ventes directes ou de nouveaux groupages.",
  SUBSCRIPTION_BILLING_UNAVAILABLE:
    "Le paiement d'abonnement n'est pas encore disponible. Aucun paiement n'a été initié.",
  SUBSCRIPTION_BILLING_CONTACT_REQUIRED:
    'Ajoutez un téléphone de facturation avant de configurer le paiement.',
  SUBSCRIPTION_PLAN_NOT_CONFIGURED:
    "Le tarif et le plan d'abonnement ne sont pas encore configurés.",
  SUBSCRIPTION_PROVIDER_SETUP_PENDING:
    "La configuration provider est en cours de vérification. N'envoyez pas une nouvelle demande.",
  INVALID_SUBSCRIPTION_BILLING_CONTACT:
    'Vérifiez le nom, le téléphone sénégalais et le format du courriel.',
  MERCHANT_PAYOUT_DISABLED: 'Les versements automatiques sont actuellement indisponibles.',
  MERCHANT_FUNDS_NOT_AVAILABLE:
    'Aucun montant disponible. Vérifiez la clôture et le solde du groupage.',
  MERCHANT_PAYOUT_IN_PROGRESS: 'Un versement est déjà en cours. Les fonds restent réservés.',
  PAYOUT_DESTINATION_REQUIRED: 'La propriétaire doit renseigner une destination de versement.',
  PAYOUT_DESTINATION_CHANGED:
    'La destination a changé. Actualisez et vérifiez-la avant de confirmer.',
  PAYOUT_DESTINATION_INVALID: 'Vérifiez la méthode, le titulaire et le numéro sénégalais.',
  PAYOUT_CONFIRMATION_REQUIRED: 'Confirmez explicitement cette opération.',
  PAYOUT_IDEMPOTENCY_CONFLICT:
    'Cette demande correspond à un autre versement. Actualisez son état.',
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
  PRODUCT_NOT_ARCHIVED: 'Ce produit est déjà actif. Actualisez le catalogue.',
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
