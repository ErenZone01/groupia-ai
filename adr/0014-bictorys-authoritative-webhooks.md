# ADR-0014 — Webhook Bictorys comme preuve principale

Date : 24 septembre 2026. Statut : accepté pour le sandbox Bictorys.

## Contexte

Le test réel rapporté par l'utilisateur montre un paiement Wave réussi dans le
dashboard et un webhook authentifié complet, tandis que `/status` retourne
`id=null, status=pending`. Le [guide Bictorys](https://docs.bictorys.com/docs/integration)
réserve ce GET aux contrôles ponctuels et recommande les webhooks pour le statut
final. La [validation des webhooks](https://docs.bictorys.com/docs/how-to-validate-webhooks)
exige l'authentification et le contrôle des données financières. Sources relues
le 24 septembre 2026 ; aucun appel de paiement distant effectué pour cette modification.

## Décision

Le port interne PaymentProvider expose une politique statique
`WebhookSuccessEvidence`. Par défaut, `SERVER_VERIFICATION` conserve l'obligation
de preuve serveur des autres adaptateurs. Seul l'adaptateur Bictorys sélectionne
`AUTHENTICATED_WEBHOOK`. Le registre capture cette politique et le compte au
démarrage : ni le payload ni le client ne peuvent les sélectionner.

Un receipt Bictorys authentique est validé dans la transaction financière sous
verrous : provider et compte, merchantReference exacte, paymentReference explicite
et exacte, montants Payment/PaymentRequest, devises, cible et purpose corrélés,
transaction présente/valide/unique, statut SUCCEEDED et demande éligible. Le champ
historique `reference` ne remplace plus `paymentReference` pour un succès webhook.
La vérification d'unicité conserve le verrou de coordination et la contrainte DB
existants. Une incohérence produit REVIEW_REQUIRED, jamais une nouvelle confirmation.

Un webhook valide règle sans GET `/status` ni `/transactions/{id}`. Le registre
Modulith et le consumer idempotent restent inchangés. Une nouvelle livraison ou
un doublon concurrent ne publie pas de deuxième règlement. Un succès déjà acquis
ne régresse jamais.

Cette décision remplace l'obligation systématique de GET décrite dans ADR-0012
pour les seuls webhooks Bictorys. `verify()` reste disponible en réconciliation
manuelle avec ses preuves strictes et, si nécessaire, les détails documentés ;
ces endpoints ne participent plus à l'acceptation du webhook.

## Éligibilité et historique

Conformément à la nouvelle instruction explicite, une tentative Bictorys non
confirmée reste non confirmée si sa demande est expirée/annulée (LATE_SUCCESS) ou
déjà réglée par une autre tentative (DUPLICATE_SUCCESS). Le receipt et l'anomalie
restent disponibles. Cela remplace pour ce chemin l'ancien comportement qui
enregistrait SUCCEEDED avant de refuser l'affectation. Les succès historiques déjà
confirmés et les autres chemins de preuve ne sont pas réécrits.

Le timestamp textuel non parsable d'un succès reste facultatif ; le log constant
utilise désormais `IGNORED_FOR_AUTHENTICATED_SUCCESS`. Les autres champs ne sont
pas rendus permissifs. Les réponses de réconciliation gardent un parsing strict.

Aucune migration, réparation SQL, réinitialisation de receipt ou action sur un
paiement existant n'est exécutée. Les receipts REVIEW_REQUIRED ne sont pas rejoués
automatiquement. Les receipts encore retryables suivent leur reprise normale,
avec revalidation complète selon la politique courante.
