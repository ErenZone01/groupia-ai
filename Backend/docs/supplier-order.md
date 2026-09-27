# Commande fournisseur figée — 26 septembre 2026

## Modèle et frontières

Le nouvel agrégat **SupplierOrder** appartient au module fermé **procurement**. Il représente une seule commande fournisseur initiale par couple boutique/groupage. Ce choix évite un cycle : ordering dépend déjà de grouping ; grouping ne peut donc pas dépendre à son tour de ordering. procurement orchestre uniquement les interfaces publiques grouping::api, ordering::api, organization::api et shared::api.

L’agrégat est un record immuable enregistré en une ligne PostgreSQL : identifiants, auteur, date, version initiale du groupage, compteurs, montant BigDecimal et devise ; lignes historiques en JSONB ; UUID des commandes clientes incluses dans un tableau interne. Ces UUID servent à reconnaître une confirmation tardive et ne sont pas exposés dans la réponse HTTP. Aucune association JPA entre modules.

Les lignes conservent productId, productVariantId et groupingOfferId ainsi que les champs de SupplierSummaryLine sous snapshot : nom, description, libellé commercial, attributs, SKU, unité de vente, quantité et montant. Les collections sont copiées. La DB interdit UPDATE et DELETE du bon. Les anomalies sont conservées séparément : leur apparition ne modifie jamais le bon.

**goodsAmount reste le montant historique des marchandises vendues aux clientes**, sans frais de plateforme ni livraison. Il ne représente pas le prix d’achat fournisseur, encore absent du domaine.

## Source et règles métier

SupplierOrdering.capture expose la projection ordering. JdbcSupplierSummary porte l’unique agrégation SQL utilisée pour le résumé dynamique et pour le gel : aucun second calcul divergent ni agrégation Angular.

CONFIRMED et COMPLETED restent les statuts acceptés. Le regroupement conserve les identités produit/variante/offre et les descriptions historiques ; des snapshots descriptifs différents restent des lignes distinctes. Les changements de prix n’introduisent pas une nouvelle clé de regroupement : les line_total historiques sont additionnés.

Décisions explicites :
- un groupage CLOSED sans commande acceptée est refusé : SUPPLIER_ORDER_EMPTY ;
- PENDING_PAYMENT bloque si payment_deadline est strictement postérieure à l’instant de décision, avec SUPPLIER_ORDER_PENDING_PAYMENTS et details.pendingOrderCount ;
- une échéance atteinte ne bloque plus, même si le traitement d’expiration n’a pas encore changé le statut ;
- EXPIRED et CANCELLED ne bloquent pas et ne sont pas inclus ;
- les autres états de groupage interdisent une nouvelle création ;
- orderedAt est l’instant de déclaration dans Groupia, pas une date fournisseur saisie librement.

## Contrat HTTP

Chemin commun :

`/api/organizations/{organizationId}/grouping-campaigns/{campaignId}/supplier-order`

**POST**, JWT requis, permissions MANAGE_GROUPINGS et VIEW_ORDERS :

```json
{ "campaignVersion": 5 }
```

La version doit provenir du serveur. Réponse 201 à la création, 200 pour le rejeu de la même intention. Enveloppe :
- supplierOrder : bon immuable et lignes ;
- campaign : id, organizationId, status et version actuels ;
- lateAcceptedOrderCount : nombre d’anomalies opérationnelles ;
- replayed : indication de rejeu.

Refus 409 : INVALID_GROUPING_TRANSITION, GROUPING_CAMPAIGN_VERSION_CONFLICT, SUPPLIER_ORDER_PENDING_PAYMENTS, SUPPLIER_ORDER_EMPTY ou SUPPLIER_ORDER_ALREADY_EXISTS selon le cas. Le compteur de paiements en attente ne contient aucune donnée cliente.

**GET**, JWT et VIEW_ORDERS : 200 avec cette enveloppe ; 404 SUPPLIER_ORDER_NOT_FOUND avant création. L’appartenance du groupage à la boutique est contrôlée. Le GET lit uniquement le bon enregistré, les métadonnées du groupage et le compteur d’anomalies ; il ne recalcule jamais supplier-summary. Les réponses sont no-store.

GET .../supplier-summary reste disponible avec ses droits actuels comme projection dynamique, notamment pour diagnostic. Après création, la source officielle des articles commandés est supplier-order.

## Atomicité, concurrence et idempotence

La règle exige explicitement une transaction atomique entre modules. SupplierPurchases ouvre une transaction d’écriture et appelle des façades publiques MANDATORY, qui participent à cette même transaction :
1. contrôler utilisateur actif, boutique, adhésion et permissions ;
2. verrouiller le groupage appartenant à la boutique, avec le même verrou de ligne utilisé pour admettre un checkout ;
3. rechercher un bon existant, puis vérifier CLOSED et la version serveur ;
4. verrouiller les commandes du groupage dans un ordre UUID stable ;
5. lire une projection cohérente, refuser les paiements encore actifs et un résultat vide ;
6. insérer le bon ;
7. faire évoluer le groupage vers SUPPLIER_ORDERED et flusher sa nouvelle version ;
8. committer l’ensemble.

Une erreur d’insertion ou de transition annule l’ensemble. CLOSED empêche l’admission de nouvelles commandes ; les verrous des commandes sérialisent les confirmations et expirations pendant le gel. Les lectures de projection partagent le snapshot MVCC d’une même instruction SQL. La lecture GET utilise REPEATABLE_READ afin de ne pas mélanger un état du groupage antérieur au commit avec un bon postérieur.

L’unicité DB (organization_id, grouping_campaign_id) garantit un seul bon. Deux créations concurrentes sont sérialisées par le verrou du groupage. La **version d’origine** identifie l’intention : un second POST avec cette même version retourne le même bon, sans nouvelle transition. Un POST avec une autre version après création est refusé. Un rejeu reste possible lorsque le groupage a progressé au-delà de SUPPLIER_ORDERED ; ce n’est pas une autorisation de recréer un bon depuis ces états.

Le bouton désactivé n’est donc pas le mécanisme de protection métier. Après réponse réseau perdue, le frontend tente uniquement GET supplier-order. En cas de conflit non résolu, il exige une relecture du groupage avant une nouvelle action explicite.

## Confirmations tardives et événements fiables

Après une confirmation effective déjà vérifiée par le flux de paiement existant, OrderSettlementListener publie OrderCommerciallyAccepted dans sa transaction : eventId, occurredAt, organizationId, groupingCampaignId et orderId, sans donnée cliente. Aucun changement des contrôles financiers.

Le consumer procurement utilise ApplicationModuleListener et le registre persistant Spring Modulith. Si un bon existe et que orderId n’appartient pas aux commandes incluses, il insère une anomalie. L’unicité bon/commande et ON CONFLICT DO NOTHING rendent les livraisons répétées idempotentes. Une livraison tardive d’un événement concernant une commande déjà incluse ne produit pas d’anomalie.

Cas résiduel : un paiement reçu dans les délais peut être confirmé commercialement après le gel si sa publication a été retardée. Le bon reste identique ; l’interface signale le nombre de commandes à traiter séparément. Aucun deuxième achat automatique.

La reprise utilise PublicationRecovery existant : publications échouées, âge minimal une minute, batch 100, au plus 20 en vol, moins de cinq tentatives. Au plafond, un log ERROR demande une investigation ; les publications restent disponibles pour une resoumission ciblée après correction. Voir [procédure opérationnelle des événements](payment-operations.md). Un test provoque l’échec du consumer puis vérifie sa reprise.

## Migration

Nouvelle migration `010-supplier-orders.sql`, incluse dans db.changelog-master.yaml :
- supplier_orders : FK scalaires vers le groupage de la boutique et l’auteur, unicité boutique/groupage, compteurs positifs, montant XOF entier/non négatif/fini, lignes non vides et cohérence des sommes ;
- supplier_order_anomalies : FK bon/commande, clé primaire composite pour déduplication ;
- fonction et trigger d’immutabilité ;
- validation des lignes JSONB, y compris les champs numériques absents/null.

Les clés primaires et l’index unique couvrent les lectures et le comptage : aucun index additionnel spéculatif. Les anciens changesets 001–009 sont inchangés. Le durcissement final de la fonction JSONB est un changeset additionnel, sans réécriture du premier changeset 010. Aucune réparation SQL des données existantes ni modification de checksum.

## Frontend et client généré

Le panneau fournisseur affiche :
- OPEN : projection en lecture et accès à la gestion des commandes ;
- CLOSED : « À commander », confirmation explicite et création désactivée pendant l’appel ;
- après succès : « Commande fournisseur », badge « Commandée », date et mention d’immutabilité.

Le statut et la version sont remplacés par les valeurs retournées par le backend. Les états suivants lisent eux aussi le bon figé. Une erreur de lecture du bon ne déclenche jamais un remplacement par la projection dynamique. Le nombre de confirmations tardives est affiché séparément.

Le client Angular a été régénéré avec npm run api:generate depuis l’export OpenAPI réel produit par les tests Spring, servi localement pendant la génération, le serveur de développement 8080 étant indisponible. 60 modèles, 9 services ; aucun DTO TypeScript parallèle. Les deux nouvelles opérations sont createSupplierOrder/readSupplierOrder.

## Recette manuelle

1. Démarrer les dépendances Docker avec la commande du dépôt, le backend et le frontend ; se connecter comme gérante d’une boutique active.
2. Créer un groupage avec une variante, puis le publier.
3. Créer trois commandes depuis le parcours public, par exemple quantités 2, 5 et 3 à 5 000 XOF.
4. Payer réellement les deux premières par le parcours normal et attendre leur statut confirmé.
5. Laisser la troisième atteindre son échéance puis expirer via le traitement prévu. Ne forcer aucun paiement ni commande en SQL.
6. Fermer le groupage.
7. Ouvrir « À commander » : deux commandes acceptées, sept articles, 35 000 FCFA de marchandises ; les frais ne sont pas inclus.
8. Cliquer « J’ai passé la commande fournisseur », lire puis valider la confirmation.
9. Vérifier « Commandée au fournisseur », la nouvelle version serveur et l’onglet « Commande fournisseur ».
10. Vérifier date, quantités et montants du bon ; actualiser et recharger la page : ils restent identiques. Un rejeu du POST avec la version d’origine retourne le même identifiant.
11. Renommer le produit du catalogue ; pour un changement de prix, utiliser une offre modifiable dans un autre groupage/brouillon. Le catalogue ne porte pas de prix et le groupage déjà commandé ne doit pas être rouvert ni modifié artificiellement.
12. Revenir sur le bon, actualiser et constater que ses descriptions, quantités et montants restent identiques.

Variante de blocage : fermer un autre groupage pendant qu’une commande reste encore payable ; l’action doit afficher son nombre sans données clientes. Réessayer explicitement après règlement confirmé ou expiration.

Contrôler aussi 360 px, clair/sombre et l’impossibilité de lire/créer depuis une autre boutique. Cette recette sandbox reste à effectuer manuellement ; les tests automatisés ne déclenchent aucun achat fournisseur ni paiement réel.

## Validation exécutée

| Commande | Résultat |
| --- | --- |
| Backend : .\mvnw.cmd test | 430 tests, zéro échec/erreur/ignoré |
| Backend : .\mvnw.cmd verify | 430 tests, zéro échec/erreur/ignoré ; BUILD SUCCESS, JAR produit |
| ModularityTests | 3 tests verts, dont ApplicationModules.of(GroupiaApplication.class).verify(), sans exclusion |
| Frontend : npm run api:generate | Succès, export réel Spring ; 60 modèles / 9 services |
| Frontend : npm test | 152 tests, 20 fichiers verts |
| Frontend : npm run test:e2e | 70 scénarios Chromium verts |
| Frontend : npm run build -- --configuration production | Succès |

Ajouts de cette tranche : 23 cas d’intégration fournisseur, un test OpenAPI, 12 tests unitaires Angular et neuf scénarios navigateur. Les anciens scénarios restent verts. Les fixtures de nettoyage des tests d’intégration incluent les nouvelles tables pour respecter les FK. Les assertions de publication du paiement sont maintenant explicitement filtrées sur PaymentRequestSettled, avec une assertion supplémentaire garantissant une seule publication OrderCommerciallyAccepted.

Captures inspectées : Frontend/artifacts/supplier-order-360-dark.png et supplier-order-1440-light.png. Les scénarios 360/1440 clair/sombre vérifient aussi l’absence de débordement horizontal. Les E2E utilisent des réponses API isolées ; les transactions, verrous et migrations sont testés séparément sur PostgreSQL Testcontainers.

## Fichiers principaux et limites

Backend :
- procurement/{api,application,domain,infrastructure} : agrégat, orchestration, persistance, consumer et endpoints ;
- grouping : SupplierCampaignLifecycle, CampaignLifecycleView, SupplierCampaignService, verrou scoped et transition de domaine ;
- ordering : SupplierOrdering, SupplierProjection, SupplierProjectionLine, SupplierOrderingService, projection SQL partagée et OrderCommerciallyAccepted ;
- db/changelog/010-supplier-orders.sql et master ;
- SupplierOrderIntegrationTests, OpenApiIntegrationTests, ModularityTests et adaptations de fixtures.

Frontend :
- openapi.json et api/generated ;
- features/orders/orders-api.ts et supplier-summary-panel.{ts,html} ;
- features/groupages/groupage-detail.page.{ts,html} et shared/utils/display.ts ;
- features/orders/supplier-order.spec.ts et e2e/supplier-order.spec.ts.

Aucune dépendance ajoutée. Code Bictorys et checkout public inchangés ; aucun historique financier existant modifié.

Restent hors MVP : modification/annulation d’un bon, commande complémentaire, plusieurs fournisseurs, coût d’achat réel, export et traitement métier des anomalies. Le signalement tardif est asynchrone et visible à l’actualisation, sans push. Le bon complet n’est pas paginé ; des volumes très importants nécessiteront une stratégie dédiée. Les montants dépassant les entiers sûrs JavaScript restent protégés par l’affichage existant au lieu d’être présentés avec une précision trompeuse.
