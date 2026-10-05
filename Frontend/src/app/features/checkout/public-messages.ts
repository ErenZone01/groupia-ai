import { HttpErrorResponse } from '@angular/common/http';
import { apiMessage } from '../../core/http/api-error';
export function publicMessage(error: unknown): string {
  if (error instanceof DOMException)
    return 'Le stockage de session est indisponible. Autorisez-le pour conserver votre suivi avant de commander.';
  if (error instanceof HttpErrorResponse && [401, 403].includes(error.status))
    return 'Le suivi de cette commande n’est plus disponible sur cet appareil.';
  if (error instanceof Error && error.name === 'TimeoutError')
    return 'Le serveur ne répond pas. Réessayez : votre tentative sera conservée.';
  return apiMessage(error);
}
export const orderStatus = (status?: string) =>
  ({
    PENDING_PAYMENT: 'Paiement en attente',
    CONFIRMED: 'Paiement confirmé',
    EXPIRED: 'Délai de paiement expiré',
    CANCELLED: 'Commande annulée',
    COMPLETED: 'Commande terminée',
  })[status ?? ''] ?? 'Vérification de la commande';
export const fulfillmentStatus = (status?: string) =>
  ({
    NOT_READY: 'En préparation',
    READY_FOR_PICKUP: 'Prête à être récupérée',
    PICKED_UP: 'Commande récupérée',
    CANCELLED: 'Annulée',
  })[status ?? ''] ?? 'En préparation';
