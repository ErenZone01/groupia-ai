# ADR-0001 — Obligations, tentatives et remboursements distincts

- Statut : accepté
- Date : 2026-09-17
- Portée : payment et coordination ordering
- Référence : [modèle métier](../docs/domain-model.md#payment)

## Contexte

Une commande peut devoir régler un achat initial puis un solde de transport. Plusieurs essais peuvent être nécessaires, et deux essais peuvent réellement réussir. Un statut unique sur Order ou une seule ligne Payment par commande ne représente pas ces faits sans ambiguïté.

## Décision validée

- PaymentRequest exprime une obligation précise ; Payment est une tentative auprès d'un prestataire.
- Plusieurs Payment peuvent appartenir à une PaymentRequest.
- Les purposes V1 sont INITIAL_ORDER et SHIPPING_BALANCE.
- Un deuxième succès réel est conservé et déclenche une réconciliation même si la demande est réglée.
- PaymentRefund représente la restitution. Payment ne remplace pas SUCCEEDED par REFUNDED.
- PaymentWebhookEvent est une réception technique conservée et traitée de manière idempotente.
- Un paiement tardif après EXPIRED/CANCELLED ne réactive jamais automatiquement Order.
- payment ne dépend ni de ordering ni de shipping. ordering coordonne toutes les obligations à partir des faits de commande et de transport.

## Application et invariants

Les montants et devises attendus sont immuables pour une obligation donnée. La ventilation marchandises/frais/transport permet d'affecter les règlements et restitutions. Chaque obligation/révision possède une référence métier stable pour résister aux reprises.

L'affectation d'un règlement coordonne la demande : au plus une tentative règle la demande, mais aucune unicité sur « succès par demande » n'interdit d'enregistrer un mouvement réel supplémentaire. L'excédent n'est pas réaffecté silencieusement.

Avant SUCCEEDED, vérifier référence interne, fournisseur, transaction, statut, montant, devise, commande et purpose. Une signature valide seule ne suffit pas. Un événement incohérent est investigable et ne confirme pas automatiquement une commande.

L'événement d'un succès est publié avec la transaction qui l'enregistre. ordering consomme les contrats publics de payment ; payment n'écoute pas OrderPlaced, ce qui créerait une dépendance inverse. ordering déclenche ses demandes via l'API payment depuis une coordination durable et idempotente après placement.

Les remboursements en cours réservent leur montant afin d'éviter deux restitutions concurrentes au-delà de l'encaissement. Leur exécution réussie reste distincte de leur demande. Un timeout prestataire exige une réconciliation, pas un débit automatiquement relancé.

## Conséquences

Le modèle contient plus de concepts qu'un simple Payment, mais rend explicites la dette, les tentatives et les mouvements. Le rapprochement transport/finance appartient à ordering, jamais à shipping.

Les clés d'idempotence, doublons de webhook, désordre des événements, incohérences montant/devise/référence et reprises après crash font partie des validations obligatoires avant utilisation réelle.

## Options écartées

Un seul Payment par commande ; état REFUNDED effaçant le succès ; rejet du deuxième succès réel ; orchestration payment vers ordering ; portefeuille implicite pour absorber les trop-perçus.

## Questions commerciales restantes

Composition du total initial, frais, compte marchand bénéficiaire, délais et réconciliation à la clôture, politique d'annulation et restitution. Voir [registre des questions](../docs/business-rules.md#questions-ouvertes-et-blocage-par-slice). Le paiement fractionné volontaire est hors MVP.
