# Vente directe — fondation cible de paiement V1 (sans encaissement)

## Objectif
Le flux historique de groupage utilise `orders` et les cibles `ORDER`.
Les abonnements utilisent `SUBSCRIPTION_INVOICE`. Une commande directe
`DIR-...` appartient à `direct_orders` et **ne doit jamais être assimilée
à un ordre de groupage**. Cette tranche ajoute une identité financière dédiée,
sans rendre la commande payable.

## Contrat de domaine et de stockage
- `PaymentTargetType.DIRECT_ORDER` et `PaymentPurpose.DIRECT_ORDER_INITIAL`.
- `CreatePaymentRequest.forDirectOrder` prépare seulement un **objet interne**
  d'obligation et ne contacte aucun fournisseur.
- Migration additive `039-direct-payment-target.sql` :
  - `payment_requests.direct_order_id` facultatif, clé étrangère composée
    `(organization_id,direct_order_id)` vers `direct_orders`.
  - `target_id=direct_order_id` pour `DIRECT_ORDER`, `order_id=NULL`.
  - Unicité d'une obligation `DIRECT_ORDER_INITIAL` par commande directe.
  - Montant strictement positif, ventilé `goods + platform + shipping = expected`,
    plafonné, et date d'expiration obligatoire.
  - Les cibles de groupage et d'abonnement restent inchangées.
- Le modèle JPA reflète `direct_order_id` séparément de `order_id`.

## Verrou de sécurité (prioritaire)
`PaymentTransactions.claim` rejette **toutes** les initiations sur une cible
`DIRECT_ORDER` avec le code `DIRECT_PAYMENT_NOT_ENABLED`, avant la création
d'une tentative et avant tout appel Bictorys. Ce blocage s'applique même avec
la même clé d'idempotence. Aucun endpoint public de paiement direct, changement
de statut, reçu payé, réservation de stock ou webhook supplémentaire.

L'existence d'une obligation ne prouve aucun encaissement. Les commandes
`PENDING_PAYMENT_INTEGRATION` restent impayées.

## Tranche suivante (NE PAS activer avant)
Créer le service propriétaire dans `directsales`, qui doit :
1. Verrouiller et vérifier `organization_id`, boutique et commande.
2. Exiger un total historiquement accepté et intégralement coté ; refuser les
   anciens brouillons sans total.
3. Vérifier le statut et l'éligibilité : retrait ou GPS contrôlé côté serveur ;
   refuser `UNVERIFIED_MANUAL` et les destinations hors zone.
4. Créer l'obligation de manière idempotente depuis les montants **en base**
   (jamais ceux du navigateur) ; ne pas accepter une cible de paiement arbitraire.
5. Ajouter le suivi sécurisé des preuves Bictorys, le traitement des succès
   tardifs/doublons, l'état de commande et les règles de stock avant d'ouvrir
   `DIRECT_PAYMENT_NOT_ENABLED`.

## Vérifications
- Tests unitaires domaine : cible distincte, montant exact et échéance,
  incompatibilités et totaux invalides.
- Test de garde : aucune création d'une tentative financière par `claim`.
- CI complète : migrations PostgreSQL, Modulith, Spotless, Security,
  Maven verify, Angular lint/tests/build et Playwright.
