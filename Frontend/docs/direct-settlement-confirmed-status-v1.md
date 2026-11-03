# Vente directe — confirmation financière après règlement vérifié (V1)

## Objectif

Après réception d'un événement interne `PaymentObligationSettled` pour une
`DIRECT_ORDER`, faire passer la commande à `PAYMENT_CONFIRMED` **uniquement**
si la preuve financière serveur a été acceptée et journalisée. Ce statut
n'autorise **pas** la préparation, la livraison, le retrait ni le reversement.

## Règles d'acceptation

- La preuve doit provenir de `PaymentOperations.settlementEvidence`, correspondre
  au même paiement, à la même organisation et à la même commande.
- L'obligation est `CLOSED`, la tentative `SUCCEEDED` et la devise `XOF`.
- `PaymentOperations.requestState` doit indiquer `SETTLED`, sans tentative
  active ni besoin de réconciliation.
- Le total accepté côté serveur doit correspondre au montant confirmé.
- Sous verrou de ligne de commande, le listener enregistre la preuve
  immuable `direct_order_verified_settlements`, puis fait la transition
  `PENDING_PAYMENT_INTEGRATION -> PAYMENT_CONFIRMED` dans **la même transaction**.
- L'UPDATE ne peut aboutir qu'avec une preuve déjà présente qui correspond
  exactement au montant, au paiement et à la confirmation fournisseur.
- Un événement identique peut être rejoué : si la confirmation a déjà eu lieu,
  aucune nouvelle mutation n'est faite ; une preuve contradictoire échoue.
- L'annulation marchande reste limitée aux commandes
  `PENDING_PAYMENT_INTEGRATION`, donc interdite une fois confirmées.
- La date `payment_confirmed_at` est la date de preuve fournisseur, pas celle
  d'une redirection navigateur.

## États et limites

- `PAYMENT_CONFIRMED` est **un constat historique**. Un chargeback, un
  remboursement ou un incident financier ultérieur peut exiger une revue.
- L'API marchande `payment-status` continue à calculer l'état financier
  courant ; elle renvoie toujours `fulfillmentAllowed=false`.
- Les actions physiques (retrait/livraison) exigent un **futur verrou métier**
  qui reverra sous transaction la preuve et l'absence de litige.
- Les anciennes preuves déjà journalisées peuvent être finalisées par le
  traitement idempotent de leur événement ; aucun changement rétroactif
  n'est fait sans vérification.
- Le paiement Bictorys des ventes directes reste **désactivé** par la garde
  `DIRECT_PAYMENT_NOT_ENABLED` ; cette tranche ne débite aucun client.
- Aucun remboursement ni versement marchand n'est activé.

## Tests

Les tests du listener couvrent les nouvelles confirmations, les preuves
réjouées, la reprise d'un règlement antérieurement journalisé, les preuves
contradictoires, les anomalies, les reversals et les commandes annulées.
Migration PostgreSQL `042` : état `PAYMENT_CONFIRMED` et timestamp obligatoire,
avec conservation de l'exclusivité des états d'annulation.

Ne fusionner qu'après Security + Backend + Frontend verts (incluant migrations,
architecture et Spotless).
