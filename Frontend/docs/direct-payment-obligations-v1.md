# Vente directe — préparation d'une obligation de paiement V1

## Périmètre
La commande `DIR-...` est déjà créée via le checkout public, mais aucune
réconciliation Bictorys ni validation de paiement n'est encore possible.

Cette tranche ajoute un **service interne** `DirectPaymentObligations.prepare`
qui peut produire l'obligation financière `DIRECT_ORDER_INITIAL` depuis la
commande existante, en réutilisant exclusivement l'API du module `payment`.

**Aucun contrôleur HTTP n'appelle ce service.** La garde
`DIRECT_PAYMENT_NOT_ENABLED` reste active avant tout appel fournisseur.

## Protections
- Isolation : recherche par `organization_id` + UUID de commande ;
  verrouillage transactionnel de la boutique avant la commande.
- Organisation active ; seuls les états `PENDING_PAYMENT_INTEGRATION` et
  les commandes de moins de **24 heures** sont éligibles.
- Total accepté, frais de livraison et tarif enregistrés à la commande ;
  aucun prix, frais ou total envoyé par le navigateur n'est utilisé.
- Égalité stricte entre sous-total, frais plateforme, frais livraison,
  total client accepté et somme des lignes historiques.
- Retrait : aucun point de livraison, frais livraison nuls.
- Livraison : position `MAP` et `REGION_MATCHED` enregistrée, coordonnées
  revérifiées localement par polygones sénégalais, même région, zone actuelle
  autorisée par la gérante. Les saisies purement manuelles sont rejetées.
- Refus des brouillons historiques sans total accepté ; des montants faux ;
  des commandes périmées et des organisations absentes.
- Expiration **déterministe** : `placed_at + 24h`, ce qui permet le rejeu
  idempotent du même `business_reference` dans `payment`.
- Unicité en base de l'obligation par commande directe, FK tenant, et
  transactions atomiques.

## Limites explicites
- Les anciennes commandes de vente directe restent `PENDING_PAYMENT_INTEGRATION`,
  sans preuve de paiement.
- Aucun appel Bictorys, aucune réservation de stock, aucun décaissement,
  aucune redirection ni confirmation automatique.
- La tranche suivante devra connecter l'authentification client, la création
  d'obligation au checkout, et les statuts/événements de paiement vérifiés
  **avant** toute activation de `PaymentTransactions.claim`.

## Tests
`DirectPaymentObligationsTests` couvre les montants d'un retrait, la livraison
par carte, les incohérences du total, les anciens brouillons, la restriction
par organisation, l'expiration, la somme des lignes et la région GPS discordante.
La CI complète continue de valider PostgreSQL, Modulith et Spotless.
