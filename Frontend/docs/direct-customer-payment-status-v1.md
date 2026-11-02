# Vente directe — statut de préparation du paiement accessible par lien sécurisé

## Contrat public

`POST /api/public/direct-orders/payment-status`

Corps JSON : `{"token": "<jeton opaque de 256 bits>"}`.

Réponse sans cache ni référencement, limitée à la commande correspondant au hash
du jeton, avec uniquement :

- `orderNumber` (DIR-…)
- `currency` (XOF)
- `acceptedTotalXof` (montant total que le client a accepté, facultatif pour les anciennes commandes)
- `preparationStatus` : `NOT_PREPARED`, `PREPARED`, `EXPIRED` ou `UNAVAILABLE`
- `paymentAvailable` : **toujours `false`** tant que le parcours de paiement direct n'est pas activé

Le service fait une jointure par `organization_id` et `direct_order_id`
sur `DIRECT_ORDER_INITIAL`; il ne prend en entrée aucun UUID d'obligation,
identifiant d'organisation ou numéro de commande. Les jetons inconnus ou mal
formés retournent le même code `DIRECT_ORDER_NOT_FOUND` (404).

## Sens des statuts
- `NOT_PREPARED` : aucune obligation financière ne correspond à cette commande.
- `PREPARED` : une obligation est ouverte, correspond au total accepté et sa
  date d'expiration n'est pas dépassée. **Ce n'est pas une preuve de paiement.**
- `EXPIRED` : l'obligation ouverte a atteint sa date d'expiration.
- `UNAVAILABLE` : obligation non ouverte, incomplète ou incohérente avec
  le montant de la commande.

Aucun état n'affirme que le client a payé, et aucun n'autorise l'encaissement.

## Protection des données
Ni contact, ni adresse GPS, ni identifiant d'organisation, ni identifiant
d'obligation, ni référence de paiement Bictorys, ni lien marchand ne sont renvoyés.
Les jetons restent exclusivement dans le corps POST, jamais dans une URL.
Les en-têtes `Cache-Control: no-store`, `Referrer-Policy: no-referrer` et
`X-Robots-Tag: noindex, nofollow` protègent les réponses.

La garde `DIRECT_PAYMENT_NOT_ENABLED` du module paiement reste inchangée.
L'affichage d'un statut ne déclenche aucune transaction et ne réserve aucun stock.

## Tests
Tests unitaires : jetons invalides, commandes inconnues, statuts préparé,
expiré, incohérent, absent, paiement toujours indisponible.
Tests d'intégration PostgreSQL : lecture de l'obligation créée par le checkout
de vente directe, absence de données sensibles et refus d'un faux jeton.
