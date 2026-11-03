# Vente directe — annulation marchande des commandes non payées (V1)

## Commande privée

`POST /api/organizations/{organizationId}/direct-orders/{orderId}/cancel`

Cette commande exige une session authentifiée et la permission
`MANAGE_FULFILLMENT` pour l'organisation. Aucun jeton client ni numéro de
commande ne permet de l'exécuter.

Réponse minimale : `orderNumber`, `status=CANCELLED`, `cancelledAt`.

## Propriétés financières

- Verrou de la vitrine, puis de la commande (même ordre que la préparation
  initiale d'obligation).
- L'absence de tout règlement vérifié est requise.
- Si une obligation `DIRECT_ORDER_INITIAL` existe, elle doit être annulable
  sous verrou dans la même transaction.
- Le module paiement refuse l'annulation si le paiement est réglé, en cours,
  nécessite une réconciliation, si une anomalie existe ou si l'obligation
  n'appartient pas à la commande et à l'organisation visées.
- Les remboursements, reversals et succès tardifs ne sont PAS supposés
  résolus : une preuve suspecte bloque l'annulation.
- L'écriture `CANCELLED`, `cancelled_at`, `cancelled_by` ne s'effectue
  qu'après l'annulation sûre de l'obligation. Un échec annule la transaction.
- Une seconde requête identique sur une commande déjà annulée rend le même
  résultat, sans nouvelle mutation.
- Les montants et les lignes commandées restent inchangés.

## Hors périmètre

- Le client ne peut pas annuler via un lien public dans cette tranche.
- Aucune tentative Bictorys n'est initiée ; le blocage
  `DIRECT_PAYMENT_NOT_ENABLED` reste en place.
- Aucun remboursement, aucune livraison ni confirmation de paiement n'est
  déclenché par l'annulation.
- L'annulation de commandes déjà réglées nécessite un processus dédié de
  remboursement et de contrôle des litiges.
- L'accès marchand ne dépend pas d'un simple `VIEW_ORDERS` : la permission
  `MANAGE_FULFILLMENT` est requise.

## Vérifications

Tests unitaires pour les autorisations, rejouements, règlements, anomalies et
absence d'obligation ; test d'intégration PostgreSQL pour la fermeture du
statut de commande et de son obligation, l'isolation et le suivi client.
Vérifier Maven `verify`, Liquibase, Modulith et Spotless avant fusion.
