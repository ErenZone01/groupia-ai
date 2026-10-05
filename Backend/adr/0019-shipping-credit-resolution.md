# ADR 0019 — Restitution distincte et crédit stable après retrait

Statut : accepté pour cette tranche, 5 octobre 2026.

## Décision

Le trop-perçu transport se régularise après PICKED_UP, lorsque le calcul final est stable. ordering réserve le montant complet déterminé côté serveur ; payment possède un Refund distinct du paiement entrant. MANAGE_REFUNDS est réservée à OWNER/MANAGER, indépendante de MANAGE_FULFILLMENT.

Deux provenances sont explicites : payout Bictorys et déclaration MANUAL_EXTERNAL. Seule une preuve provider complète peut réussir un payout ; un HTTP d'initiation et un timeout ne règlent rien. La déclaration manuelle atteste une restitution déjà réalisée et conserve cette provenance jusque dans le suivi cliente.

La réservation locale précède l'appel réseau hors transaction. Un Refund logique par commande et une clé provider persistée empêchent les doubles sorties. Le remplacement d'une opération FAILED/CANCELLED n'est pas proposé dans cette V1 ; un succès tardif doit rester traitable sans risquer un second remboursement.

Le payé transport net déduit uniquement les restitutions SUCCEEDED. Leur événement durable ShippingCreditRefundSettled permet à ordering de compléter idempotemment une commande déjà retirée. Aucun Payment original n'est réécrit, aucune commission additionnelle n'est créée et le groupage reste DISTRIBUTING.

## Conséquences

019 est une migration additive ; 001–018 restent intactes. Toutes les migrations numérotées sont immutables dès leur création. Les intégrations réelles sont désactivées par défaut et les tests provider utilisent un serveur HTTP local.

Le contrat payout exige une direction explicite et un destinataire corrélé. Les variantes sandbox ne fournissant pas ces preuves restent à vérifier. Après timeout sans identifiant, aucun nouvel envoi automatique : attendre une preuve webhook ou investiguer auprès du provider.

Voir [shipping-credit-resolution.md](../docs/shipping-credit-resolution.md) pour API, opérations, limites et recette.

