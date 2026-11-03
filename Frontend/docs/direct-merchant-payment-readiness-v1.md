# Vente directe — lecture financière pour la gérante (V1)

## Objectif

Proposer un statut financier prudent aux gérantes, sans donner l'autorisation de
remettre des produits, d'expédier, de payer un marchand ou d'encaisser Bictorys.

## API privée

`GET /api/organizations/{organizationId}/direct-orders/{orderId}/payment-status`

L'utilisateur doit être authentifié et disposer de `VIEW_ORDERS` pour cette
organisation. Les requêtes SQL sont contraintes par `organization_id` et
`direct_order_id`. Les commandes d'une autre organisation restent invisibles.

Réponse : `orderNumber`, `acceptedTotalXof`, `financialStatus` et
`fulfillmentAllowed` (toujours `false`).

## États

- `NOT_PREPARED` : aucune obligation initiale.
- `PREPARED` : obligation cohérente, ouverte et non expirée ; **pas payée**.
- `EXPIRED` : obligation ayant dépassé son délai.
- `UNAVAILABLE` : obligation close sans preuve de règlement ou indisponible.
- `SETTLEMENT_PROCESSING` : finance marquée réglée, mais le journal durable de
  la commande directe n'est pas encore à jour.
- `SETTLEMENT_VERIFIED` : journal append-only présent, paiement conforme
  aux preuves canoniques, sans reversal ni anomalie connue.
- `REVIEW_REQUIRED` : risque financier, divergence, inversion du paiement
  ou incohérence entre preuve, obligation et commande.

Les statuts sont calculés au moment de la lecture via `PaymentOperations` et
le journal de la PR #68. Aucun statut n'est une autorisation de livraison.

## Sécurité

- Les montants proviennent uniquement des snapshots serveur.
- Le client public n'utilise pas cette route ; les jetons de suivi client
  n'ouvrent pas l'interface gérante.
- Aucune tentative Bictorys n'est initiée.
- Aucune confirmation commerciale ou de retrait n'est créée.
- Aucun ID de paiement, transaction fournisseur ou compte marchand n'est
  exposé dans la réponse.
- L'évaluation doit être refaite en transaction lors du futur changement de
  statut commercial, et jamais basée sur cette simple lecture.

## Tests

Tests unitaires : absence d'obligation, préparation, confirmation avant journal,
preuve conforme, reversal, anomalie et permissions. Tests PostgreSQL du checkout :
lecture autorisée, utilisateur non authentifié refusé, anciens brouillons et
nouveaux paiements préparés restent non livrables.
