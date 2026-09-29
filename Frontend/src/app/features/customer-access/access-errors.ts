import { HttpErrorResponse } from '@angular/common/http';
export function accessError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) return 'Connexion interrompue. Vérifiez votre réseau puis réessayez.';
    if (error.status === 429) return 'Trop de tentatives. Patientez avant de réessayer.';
    if (error.status === 503)
      return 'La récupération des commandes sera bientôt disponible. Conservez votre suivi actuel.';
    if (error.error?.code === 'OTP_INVALID')
      return 'Code incorrect ou expiré. Vérifiez les six chiffres ou demandez un nouveau code.';
    if (error.status === 401 || error.status === 403)
      return 'Cet accès a expiré ou ne permet pas cette action. Retrouvez vos commandes avec votre numéro.';
    if (error.status === 404) return 'Cette commande n’est pas accessible avec votre session.';
  }
  return 'Impossible de terminer cette demande. Réessayez dans quelques instants.';
}
