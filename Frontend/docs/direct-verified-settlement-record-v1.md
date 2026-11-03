# Vente directe — journal des règlements vérifiés (V1)

## Objectif
Accepter **uniquement** l'événement interne `PaymentObligationSettled` portant
sur une cible `DIRECT_ORDER`, en relisant la preuve financière canonique depuis
`PaymentOperations.settlementEvidence`. Conserver ensuite un reçu durable
et immuable, sans débloquer la commande.

## Comportement
- Les événements `ORDER` et `SUBSCRIPTION_INVOICE` sont ignorés.
- Pour une commande directe, l'événement doit porter sur
  `DIRECT_ORDER_INITIAL` et correspondre exactement à la preuve financière.
- Exigences : paiement `SUCCEEDED`, acceptation `CLOSED`, absence de
  `reversal`, transaction fournisseur identifiée, même montant et même devise,
  même organisation, même commande, même tentative et même horodatage de confirmation.
- La commande doit exister dans l'organisation et son montant accepté
  doit correspondre au montant réellement vérifié.
- La commande est verrouillée avant l'enregistrement ; le reçu est inscrit
  dans `direct_order_verified_settlements`.
- Un rejeu identique est sans effet ; un second paiement contradictoire
  est refusé et doit faire l'objet d'une investigation.
- L'historique financier est append-only par contrainte/trigger PostgreSQL ;
  les clés étrangères lient strictement boutique, commande, obligation et paiement.

## Ce que cette tranche ne fait PAS
- **Aucun débit n'est rendu possible :** `PaymentTransactions.claim` continue
  de bloquer `DIRECT_ORDER` (`DIRECT_PAYMENT_NOT_ENABLED`).
- Aucun statut commercial `CONFIRMED` ni `PAID` n'est donné à la commande.
- Aucune préparation de livraison, comptabilisation des fonds du marchand,
  autorisation de retrait, notification ni reversement n'est déclenché.
- Aucun reçu financier interne n'est exposé à l'acheteur.
- Une opposition ou un remboursement ultérieur doit disposer d'un circuit
  d'annulation / revue dédié **avant** d'utiliser cette preuve pour exécuter
  le service. Le reçu historique n'est pas une preuve d'absence de litige.

## Tests
`DirectOrderSettlementListenerTests` : paiement validé, autres cibles ignorées,
reversals refusés, mauvais total refusé, rejeu identique et rejeu contradictoire.
La CI vérifie migrations, sécurité, architecture Modulith, tests Maven et Spotless.
