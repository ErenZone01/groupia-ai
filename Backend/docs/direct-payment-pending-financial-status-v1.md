# Vente directe — tentative Bictorys en cours (V1)

Une initiation de paiement ne prouve **jamais** que la cliente a payé.

## États de lecture

- `PREPARED` : obligation ouverte, admissible, sans tentative active.
- `PAYMENT_IN_PROGRESS` : tentative fournisseur en état créé/en attente,
  sans anomalie de rapprochement. Ni nouveau paiement, ni annulation ni
  préparation ne sont ouverts sur cette seule lecture.
- `SETTLEMENT_PROCESSING` : règlement canonique attribué, mais preuve
  immuable/confirmation commande pas encore enregistrée.
- `SETTLEMENT_VERIFIED` : preuve persistée, source financière
  contradictoirement revalidée, aucun risque de remboursement/annulation.
- `REVIEW_REQUIRED` : anomalie, état contradictoire ou réconciliation
  nécessaire. Le simple état `PENDING` de Bictorys n'est plus considéré
  comme une anomalie.
- `EXPIRED`, `UNAVAILABLE`, `NOT_PREPARED` : aucune permission de payer.

Les modèles de lecture client et gérante sont cohérents, mais **ils ne
remplacent pas les vérifications transactionnelles** appliquées à chaque
action, dont l'annulation. `paymentAvailable` est strictement false pour
`PAYMENT_IN_PROGRESS`. Aucune remise, livraison ou reversement n'est
autorisé par cet état.

## Tests

Les tests unitaires vérifient que l'état en attente est distinct des
anomalies, que le bouton de payer n'apparaît pas et que la gérante ne
peut déclencher aucune action. Le test PostgreSQL + Bictorys HTTP simulé
contrôle ce statut immédiatement après l'initiation d'une vraie tentative
persistée, sans webhook de réussite.

Le feature flag `groupia.direct-sales.payment-initiation.enabled`
reste désactivé par défaut. Cette évolution ne lance aucun paiement réel.
