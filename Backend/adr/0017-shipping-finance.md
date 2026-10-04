# ADR-0017 — Position transport dérivée et obligations révisables

Date : 2026-10-04. Statut : accepté pour Shipping Finance V1.

La position financière appartient au coordinateur ordering : FINAL courant de shipping moins snapshot initial payé et obligations transport allouées de payment.
Aucune table de projection monétaire supplémentaire ni moteur provider parallèle.

La finalisation et le remplacement de l'obligation sont atomiques, exception demandée par l'invariant financier.
Ordre des verrous : campagne, commande, obligations triées par createdAt/id. L'initiation verrouille les obligations transport dans le même ordre avant de réserver sa tentative.
Les appels réseau restent hors transaction ; les événements de règlement utilisent le registre durable Modulith.

Une obligation SHIPPING_BALANCE positive n'a pas de deadline métier ni frais plateforme. Les deadlines INITIAL_ORDER restent obligatoires.
Le couple commande/calcul final est la référence déterministe, complété par une unicité partielle sur l'obligation ouverte.
Les tentatives incertaines ou non terminales bloquent une correction. Les allocations réglées restent historiques et contribuent aux révisions suivantes.

Le parcours cookie/CSRF customeraccess résout la commande et appelle ordering::api. Un reçu ordering évite une nouvelle dépendance customeraccess → payment.
Le frontend ouvre le checkout dans un nouvel onglet comme le parcours initial, et rafraîchit le suivi au retour ; aucune URL ne prouve un paiement.
Les finals antérieurs sans obligation sont provisionnés lors de la première initiation autorisée, sans écriture à la lecture ni backfill de données financières.

CREDIT_DUE est informatif : remboursement et retrait sont reportés. Détails et recette : ../docs/shipping-balance.md.
