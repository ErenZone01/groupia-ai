# Vente directe — commande prête après préparation (V1)

## Commande marchande privée

`POST /api/organizations/{organizationId}/direct-orders/{orderId}/mark-ready`

Permission obligatoire : `MANAGE_FULFILLMENT`. Aucun champ de montant,
statut, mode de livraison ou preuve de paiement n'est accepté depuis le client.
Le mode est déterminé exclusivement à partir du snapshot serveur du checkout.

| Mode enregistré | Transition permise |
| --- | --- |
| `PICKUP` | `PREPARING → READY_FOR_PICKUP` |
| `DELIVERY` | `PREPARING → READY_FOR_DISPATCH` |

La réponse renvoie `orderNumber`, `status`, `fulfillmentMethod` et
`readyAt`. La migration `044` ajoute `ready_at` et `ready_by`, et les
contraintes PostgreSQL garantissent la cohérence du mode, de l'audit et de la
chronologie avec `preparing_at`.

## Garde-fous

- Transaction unique : permission de la gérante, verrou vitrine puis
  commande (même ordre que checkout/annulation), enfin verrou de
  l'obligation de paiement.
- `PaymentOperations.lockRequestState` doit être `SETTLED`, sans
  tentative en cours ni réconciliation nécessaire.
- `settlementEvidence` doit correspondre au même vendeur, à la
  même commande, au paiement accepté, au montant exact en XOF,
  au fournisseur, à la référence et à l'instant de confirmation
  déjà inscrits dans le journal immuable des règlements.
- Un paiement inversé, contesté, contradictoire ou non confirmé bloque
  le passage à `READY_*`.
- La mise à jour SQL exige `PREPARING`, la bonne méthode enregistrée,
  et la présence du reçu financier exact. Le serveur journalise l'auteur.
- Rejouer la même action pour une commande déjà prête renvoie son
  état et sa date sans créer une nouvelle transition.
- Un événement `PaymentObligationSettled` rejoué après l'état prêt
  ne peut pas faire revenir la commande à un état antérieur.
- Aucune opération entre organisations, aucun lien public et aucune
  session anonyme ne peuvent déclencher la commande.

## Limites importantes

`READY_FOR_PICKUP` signifie **articles prêts pour le retrait** ;
`READY_FOR_DISPATCH` signifie **colis prêt à être expédié**. Aucun de ces
états ne signifie « remis », « expédié » ou « livré ».

La remise, l'expédition, la confirmation de réception et les reversements
exigeront des commandes séparées et une **nouvelle vérification financière**
au moment de l'acte. Un litige ou reversal apparu après la mise à disposition
doit pouvoir empêcher une remise future.

Les autres protections restent en place : `payment-status` expose toujours
`fulfillmentAllowed=false` et la garde
`DIRECT_PAYMENT_NOT_ENABLED` bloque le paiement Bictorys en vente directe.

## Tests

- Tests unitaires : modes retrait/livraison, montants, reversals,
  statut financier incertain, protection multi-boutiques, requête
  répétée et écriture SQL refusée.
- Tests d'intégration PostgreSQL : refus impayé, annulé, anonyme et
  cross-tenant.
- Test du listener Modulith : aucun changement d'état au rejeu d'une
  confirmation pour une commande déjà prête.
- Vérifications obligatoires avant fusion : Security, Backend (tests,
  Spotless, Liquibase, Modulith) et Frontend.
