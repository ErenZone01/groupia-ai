# Groupia — Totaux exacts dans les commandes directes (espace gérante)

## Pourquoi
Depuis les migrations 036–037, un brouillon direct peut conserver un forfait de livraison et le montant exact accepté par la cliente. La vue commerçante créée avant ces migrations continuait pourtant d'afficher seulement `amountBeforeShippingXof`, y compris pour les commandes livrées.

## Contrat de lecture
Les réponses **privées** de `GET /api/organizations/{organizationId}/direct-orders` et `/{orderId}` exposent désormais, en plus des anciennes valeurs :

- `shippingFeeXof: number | null`
- `acceptedTotalXof: number | null`
- `shippingRateVersion: number | null`

`null` signifie **montant ou version non historisés**, et jamais « zéro », notamment pour les anciens brouillons. Les contrôles existants `VIEW_ORDERS`, pagination et isolation par organisation restent inchangés. Le détail de l'adresse privée de la gérante indique `locationMethod` / `coverageStatus` pour distinguer un point carte géométriquement vérifié d'une adresse manuelle déclarée. Aucune preuve de présence physique n'en découle.

## Interface
- Liste : « Total accepté · Non payé » uniquement quand le total est présent ; sinon « Montant historique hors livraison · Non payé ».
- Détail : articles, frais plateforme, frais de livraison (0 explicite accepté), et total accepté.
- Adresse manuelle : avertissement de vérification avant remise ; point carte : région correspondante sans preuve de présence.
- Aucun bouton de paiement, aucun statut `PAID`, aucune expédition enclenchée.

## Validation
- Tests Java intégration : commandes historiques sans total, commande GPS tarifée à 1 500 XOF, montant accepté côté gérante identique au snapshot serveur, région calculée, liste isolée.
- Tests Angular HTTP : types et valeurs nuls, zéro explicite et total accepté.
- CI Security, backend Maven verify / Spotless / migrations, frontend lint / unit / build / Playwright.

## Suite
Cette tranche prépare la transparence du montant marchand avant l'intégration Bictorys. Le statut reste `PENDING_PAYMENT_INTEGRATION` : aucun paiement ni réservation de stock.
