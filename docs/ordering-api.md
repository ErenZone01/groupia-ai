# API commande et checkout — quatrième tranche

Implémentation backend uniquement. Les autres états décrits dans le modèle restent
sans transition exécutable : toute nouvelle commande est PENDING_PAYMENT / NOT_READY.

## Routes

| Méthode et route | Accès | Réponse |
| --- | --- | --- |
| POST /api/public/groupings/{publicIdentifier}/checkout | Anonyme + Idempotency-Key | 201 création ; 200 rejeu |
| GET /api/public/orders/track | X-Order-Tracking-Token obligatoire | 200 reçu privé ; 404 secret invalide/expiré/révoqué |
| GET /api/organizations/{organizationId}/orders | JWT + VIEW_ORDERS | Page de résumés |
| GET /api/organizations/{organizationId}/orders/{orderId} | JWT + VIEW_ORDERS | Détail organisateur avec contact snapshoté |

VIEW_ORDERS : OWNER, MANAGER, OPERATOR, VIEWER ; compte actif, adhésion ACTIVE
et organisation ACTIVE requis. Chaque lecture vérifie explicitement organisation
et permission ; un UUID de commande d'une autre organisation donne 404 dans un
contexte autorisé. Aucun endpoint de modification de statut.

## Requête de checkout

Header Idempotency-Key : UUID créée par le client pour cet achat logique,
réutilisée à chaque nouvelle tentative du même achat. Ne pas partager ni journaliser.

Exemple de corps (remplacer l'UUID d'offre par une offre de la campagne) :

~~~json
{
  "contact": {
    "firstName": "Awa",
    "lastName": "Diop",
    "phone": "77 123 45 67",
    "email": "awa@example.test"
  },
  "lines": [
    {
      "offerId": "11111111-1111-4111-8111-111111111111",
      "quantity": 2,
      "expectedUnitPrice": 14500,
      "expectedPriceRevision": 0
    }
  ]
}
~~~

Ne pas envoyer organizationId, statut, frais, total ou données produit.
Les champs inconnus sont refusés. firstName/lastName : 1 à 100 caractères après
trim ; téléphone valide normalisé E.164, pays de l'organisation pour les numéros
nationaux ; email facultatif, au plus 254 caractères. Une offre ne peut apparaître
qu'une fois. 1 à 100 offres, quantité entière 1 à 1 000 000. L'ordre des lignes
n'influence pas l'empreinte ; la confirmation est triée par UUID d'offre.

Le prix observé et priceRevision viennent du storefront. Le serveur résout campagne,
organisation et offres, revalide disponibilité, statut OPEN, échéance exclusive,
catalogue et devise XOF. Il n'utilise le prix client que comme consentement.
409 GROUPING_OFFER_PRICE_CHANGED contient details.offerId, unitPrice,
priceRevision, version et currency courants. Aucune commande/contact créé en conflit.
Le frontend doit présenter le nouveau prix et demander un nouveau consentement.

## Réponses et rejeu

Le 201 retourne orderNumber, status, fulfillmentStatus, goodsSubtotal,
platformFeeAmount, estimatedShippingAmount (null si inconnue),
initialShippingChargeAmount, initialAmountDue, currency, paymentDeadline,
placedAt, trackingToken et items. Chaque item contient lineNumber,
productNameSnapshot, productDescriptionSnapshot, commercialLabelSnapshot,
variantAttributesSnapshot, skuSnapshot, saleUnitSnapshot, quantity,
unitPriceSnapshot, lineTotal, estimatedUnitWeightKgSnapshot,
estimatedUnitVolumeM3Snapshot. Pas d'UUID interne de commande requis pour l'acheteur.

Le secret de 43 caractères est remis une seule fois. Il faut le conserver
confidentiellement ; ne pas le placer dans une URL, un journal, une télémétrie ou
un message d'erreur. GET /api/public/orders/track avec le header dédié retourne
le même reçu commercial historique, sans trackingToken et sans contact privé,
organisation, hash, audit ou paiement. Cache-Control: no-store.

Même clé + même contenu normalisé : 200 avec seulement :

~~~json
{"orderNumber":"GRP-000000000001","replayed":true}
~~~

Le serveur ne recalcule pas la commande et ne crée aucun autre Customer.
Même clé + contenu différent : 409 IDEMPOTENCY_KEY_REUSED, y compris si la
nouvelle campagne appartient à la même organisation. Le client possédant le
secret utilise le suivi pour retrouver les détails. Si la première réponse est
perdue, le secret est irrécupérable dans cette tranche ; ne pas recommander une
nouvelle clé et une deuxième commande. La récupération avec preuve est un futur
parcours explicite (ADR-0009).

## Calculs et configuration

goodsSubtotal = somme(prix serveur × quantité).
platformFeeAmount = GROUPIA_PLATFORM_FEE_XOF, défaut développement 300.
Le type FIXED, la devise, la valeur et la version de règle sont snapshotés.

| Méthode | Estimation avant arrondi |
| --- | --- |
| PER_KG | somme(quantité × poids unitaire kg) × tarif |
| PER_CBM | somme(quantité × volume unitaire m³) × tarif |
| PER_ITEM | somme(quantité) × tarif |
| FIXED | tarif par commande |

Aucun arrondi intermédiaire ; arrondi final HALF_UP à l'entier XOF.
AFTER_FINAL_ASSESSMENT : initialShippingChargeAmount=0, estimation informative
éventuellement null. ESTIMATED_AT_CHECKOUT : estimation obligatoire, exigible
initialement, ou 409 SHIPPING_ESTIMATE_UNAVAILABLE. Poids/volume absent jamais
remplacé par zéro. initialAmountDue = marchandises + frais + transport initial.

GROUPIA_ORDER_PAYMENT_WINDOW_MINUTES : défaut 30, échéance calculée au placement.
GROUPIA_ORDER_TRACKING_LIFETIME_DAYS : défaut 180. Ces propriétés peuvent être
définies dans l'environnement ou le fichier .env déjà chargé par l'application.
Modifier la configuration ne réécrit pas les commandes existantes.
Aucun scheduler d'expiration, obligation ou paiement n'est créé.

## Lecture organisateur et erreurs

Pagination : page=0, size=20, size entre 1 et 100 ; filtres campaignId et status.
Tri placedAt décroissant puis id. Page : items, page, size, totalElements,
totalPages. Résumés : id, campaignId, orderNumber, status, fulfillmentStatus,
initialAmountDue, currency, placedAt. Détail : id, organizationId, campaignId,
customerId, customerContactSnapshot, order (reçu sans jeton), version.

Erreurs ProblemDetail avec code stable, jamais de stack trace :
GROUPING_NOT_ORDERABLE, GROUPING_CLOSED, GROUPING_OFFER_NOT_AVAILABLE,
GROUPING_OFFER_PRICE_CHANGED, INVALID_ORDER_QUANTITY, INVALID_ORDER_LINES,
DUPLICATE_ORDER_OFFER, INVALID_EXPECTED_PRICE, INVALID_CUSTOMER_CONTACT,
IDEMPOTENCY_KEY_REQUIRED, INVALID_IDEMPOTENCY_KEY, IDEMPOTENCY_KEY_REUSED,
SHIPPING_ESTIMATE_UNAVAILABLE, ORDER_AMOUNT_OUT_OF_RANGE, ORDER_NOT_FOUND,
ORDER_TRACKING_INVALID et ORDER_TRACKING_RATE_LIMITED (429).
Une campagne inconnue donne 404 GROUPING_NOT_ORDERABLE ; campagne connue mais
inadmissible donne 409. Les erreurs de forme JSON donnent 400 INVALID_REQUEST.

Le suivi applique 60 essais/minute/adresse distante par processus, avec mémoire
bornée. Les limites derrière proxy sont décrites dans ADR-0009.

## Persistance et vérification

Migration 006-ordering : customers, orders, order_items, order_number_seq.
Références UUID scalaires, FK composées sans cascade, unicités commande/clé/hash/
ligne/offre, montants et quantités contrôlés. Index de consultation par organisation,
campagne, statut, placedAt ; contact et références de lignes. Pas de table shipping
ni payment supplémentaire.

Tests PostgreSQL/Testcontainers : .\\mvnw.cmd test puis .\\mvnw.cmd verify.
Docker Desktop doit être actif ; Testcontainers fournit sa base isolée. Le démarrage
local de l'application continue d'utiliser le compose du dépôt (voir contrat
identity/organization). Tests de concurrence : mêmes clés et contenus différents,
fermeture et changement de prix de part et d'autre de l'admission, rollback
intégral si insertion d'une ligne échoue. ApplicationModules.verify() inchangé.

Détails des décisions : [ADR-0009](../adr/0009-checkout-admission-idempotency-and-tracking.md).
