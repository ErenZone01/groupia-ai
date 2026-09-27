# Récapitulatif fournisseur — 26 septembre 2026

> Complément : la [commande fournisseur figée](supplier-order.md) est désormais implémentée. Ce document décrit la projection dynamique et la validation de sa tranche initiale ; après création du bon, l’interface lit le bon enregistré.

## Contrat

GET /api/organizations/{organizationId}/grouping-campaigns/{campaignId}/supplier-summary

JWT Bearer obligatoire. Le module ordering vérifie l’utilisateur actif, l’appartenance active à la boutique, VIEW_ORDERS et la boutique active via OrganizationAccess. Il vérifie ensuite l’appartenance du groupage via GroupingQueries. Une campagne d’une autre boutique est introuvable (404) ; une boutique non accessible est interdite (403). Réponse Cache-Control: no-store.

La responsabilité de la projection reste dans ordering. grouping expose uniquement un contrôle d’appartenance, sans lire ordering. Aucune nouvelle relation JPA, aucun appel HTTP entre modules.

Exemple de réponse :

```json
{
  "groupingCampaignId": "44444444-4444-4444-8444-444444444444",
  "acceptedOrderCount": 2,
  "totalUnits": 7,
  "goodsAmount": 35000,
  "currency": "XOF",
  "lines": [
    {
      "productNameSnapshot": "Chemise",
      "productDescriptionSnapshot": "Description lors de la commande",
      "commercialLabelSnapshot": "Collection de rentrée",
      "variantAttributesSnapshot": { "color": "noir", "size": "m" },
      "skuSnapshot": "CHEMISE-NOIR-M",
      "saleUnitSnapshot": "UNIT",
      "totalQuantity": 7,
      "goodsAmount": 35000
    }
  ]
}
```

Aucune coordonnée cliente, aucun identifiant de paiement. Les champs facultatifs de snapshot peuvent être nuls. Sans commande acceptée : compteurs et montant à zéro, currency XOF, lines vide.

## Statuts et identité

OrderStatus.commerciallyAcceptedStatuses() centralise CONFIRMED et COMPLETED. Selon domain-model.md, COMPLETED suit la confirmation, la remise complète et la résolution financière : cette transition ne retire donc pas la vente du résumé. PENDING_PAYMENT, EXPIRED et CANCELLED sont exclus. Aucune transition de commande n’est modifiée.

Identité de regroupement : product_id_snapshot + product_variant_id_snapshot + grouping_offer_id, complétée par les snapshots retournés (nom, description, libellé, attributs JSONB, SKU et unité de vente). Deux produits portant exactement les mêmes textes/attributs ne fusionnent jamais.

Décision explicite : si une même variante a été vendue avec des descriptions/libellés/attributs historiques différents, conserver des lignes distinctes plutôt que choisir arbitrairement une ancienne ou nouvelle description. Les prix et révisions ne sont pas des clés : les quantités sont additionnées et les montants historiques line_total sont sommés même après un changement de prix.

Ce montant représente la marchandise vendue aux clientes, pas un coût d’achat fournisseur ou une marge. Les frais de plateforme, PSP et transport ne sont jamais ajoutés. Les contraintes PostgreSQL existantes imposent XOF et les montants entiers ; le read-model conserve BigDecimal côté Java.

## SQL et index

JdbcSupplierSummary exécute **une requête SQL** :
1. CTE accepted : commandes du couple boutique/groupage dans les statuts acceptés.
2. CTE grouped : jointure interne order_items/order_id, second filtrage boutique/groupage, GROUP BY identifiants et snapshots, SUM(quantity), SUM(line_total).
3. CTE totals : nombre des commandes acceptées et sommes des lignes.
4. LEFT JOIN des totaux avec les lignes pour conserver une réponse même à vide.

Lignes et totaux utilisent le même snapshot MVCC d’une seule instruction PostgreSQL. Aucun chargement des agrégats Order, aucune requête par commande, aucun accès au catalogue ou au paiement. Transaction de lecture seule, pas de verrou d’écriture.

**5 lectures DB par appel autorisé dans l’implémentation actuelle** :
- utilisateur actif ;
- adhésion/permission ;
- boutique active ;
- appartenance du groupage ;
- agrégation SQL.

Le test mesure les quatre préparations Hibernate plus l’unique appel JDBC natif, avec 1 puis 26 commandes ; le total reste exactement 5, sans chargement de collection. Un refus d’autorisation interrompt avant la projection.

Aucune migration ni nouvel index. Inspection du schéma et du chemin d’accès :
- ix_orders_org_campaign_status_placed couvre boutique, groupage et statut ;
- l’index unique uk_order_items_line(order_id, line_number) couvre la jointure par order_id ;
- les identifiants historiques sont déjà présents dans order_items.

Ce choix repose sur les index existants et le plan logique, pas sur une mesure de charge production. Le nombre de requêtes est constant ; leur travail dépend naturellement du volume des commandes acceptées.

## OpenAPI et frontend

L’opération supplierSummary est documentée dans l’OpenAPI Spring, avec les schémas SupplierSummaryView et SupplierSummaryLine. Le client a été régénéré avec npm run api:generate depuis http://localhost:8080/v3/api-docs.

Dans /app/groupages/:id, l’onglet **À commander** utilise OrdersApi et SupplierSummaryControllerService généré. Il affiche les snapshots, les quantités et les montants déjà calculés par PostgreSQL. Chargement, état vide, erreur/réessai, actualisation, navigation clavier, thèmes et écran 360 px sont couverts. Changer de boutique/groupage ou quitter le panneau annule la requête périmée. Aucun DTO TypeScript parallèle, aucune agrégation Angular, aucun N+1 de détails.

## Recette manuelle

1. Démarrer backend et frontend, se connecter comme gérante d’une boutique active.
2. Créer/publier un groupage avec une chemise (variante noir/M) vendue 5 000 XOF.
3. Depuis le lien public, créer trois commandes clientes distinctes : quantités 2, 5 et 3.
4. Régler les deux premières via le parcours normal, attendre leur confirmation réelle. Laisser la troisième en attente ou la laisser expirer. Ne pas forcer de statut en SQL.
5. Se reconnecter comme gérante et ouvrir le détail du groupage.
6. Ouvrir « À commander » : attendre 2 commandes acceptées, 7 articles, 35 000 FCFA de marchandises, une ligne noir/M de quantité 7.
7. Comparer avec les snapshots des deux détails de commande : somme des quantités et lineTotal. Ne pas comparer avec initialAmountDue, qui peut inclure des frais.
8. Vérifier que la commande en attente/expirée n’ajoute ni ses 3 articles ni ses 15 000 XOF.
9. Renommer le produit, changer son prix ou désactiver la variante après les ventes : actualiser ; le résumé historique reste inchangé.
10. Vérifier clair/sombre et 360 px ; une autre boutique ne doit pas pouvoir consulter le même groupage.

Les tests automatiques utilisent uniquement les bases éphémères Testcontainers et des réponses navigateur isolées. Cette recette de paiement réel reste à exécuter manuellement ; aucun paiement Bictorys réel n’est déclenché par cette livraison.

## Fichiers et portée

Backend :
- grouping/api/GroupingQueries.java et grouping/application/service/GroupingService.java : contrôle d’appartenance ;
- ordering/domain/model/OrderStatus.java : statuts acceptés ;
- ordering/api/dto/SupplierSummaryView.java et SupplierSummaryLine.java : contrat ;
- ordering/application/query/SupplierSummaryReadModel.java : port de lecture ;
- ordering/application/service/SupplierSummaryQueries.java : orchestration/autorisation ;
- ordering/infrastructure/persistence/JdbcSupplierSummary.java : projection SQL ;
- ordering/infrastructure/web/SupplierSummaryController.java : endpoint ;
- SupplierSummaryIntegrationTests.java et OpenApiIntegrationTests.java : intégration, sécurité, coût SQL et contrat.

Frontend :
- openapi.json et api/generated : contrat et client générés ;
- features/orders/orders-api.ts et supplier-summary-panel.{ts,html,scss,spec.ts} ;
- features/groupages/groupage-detail.page.{ts,html} : nouvel onglet ;
- features/orders/orders-panel.{ts,html} : retrait de l’ancien message d’indisponibilité ;
- e2e/supplier-summary.spec.ts et e2e/manager-orders.spec.ts ;
- docs/manager-orders.md et README.md : liens vers ce complément.

Aucune dépendance ajoutée, aucune migration, aucune modification du paiement Bictorys ou du checkout public.

## Limites

Le récapitulatif est une lecture à un instant donné, pas un bon de commande fournisseur figé. Actualiser après un paiement ou changement de statut. Pas de réservation, export ou suivi d’achat ajouté.

Le résultat n’est pas paginé : il contient toutes les partitions de snapshots d’un groupage. Le nombre SQL est borné, mais un groupage exceptionnellement volumineux pourra justifier ultérieurement une pagination/export dédiés. Les valeurs dépassant les entiers sûrs JavaScript sont signalées indisponibles à l’écran au lieu d’être présentées comme exactes ; le backend conserve la précision BigDecimal.

## Validation exécutée

| Commande | Résultat |
| --- | --- |
| Backend : .\mvnw.cmd test | 406 tests, 0 échec, 0 erreur, 0 ignoré |
| Backend : .\mvnw.cmd verify | 406 tests, 0 échec, 0 erreur, 0 ignoré ; BUILD SUCCESS et JAR produit |
| ApplicationModules.verify() / ModularityTests | 3 tests verts, sans contournement |
| Frontend : npm run api:generate | Succès depuis l’OpenAPI vivant ; 55 modèles et 8 services générés |
| Frontend : npm test | 140 tests, 19 fichiers verts |
| Frontend : npm run test:e2e | 61 scénarios Chromium verts |
| Frontend : npm run build -- --configuration production | Succès |

Ajouts : 21 tests PostgreSQL du récapitulatif et 1 test OpenAPI ; 7 tests unitaires Angular ; 5 scénarios navigateur. Le test navigateur existant de l’onglet Commandes a été adapté au déplacement du récapitulatif vers son propre onglet.

Captures inspectées dans Frontend/artifacts : supplier-summary-360-light.png, supplier-summary-360-dark.png et supplier-summary-1440-light.png. Les quatre variantes 360/1440 clair/sombre vérifient aussi l’absence de débordement horizontal. Les fixtures navigateur restent isolées dans e2e.

La comparaison structurelle du snapshot OpenAPI confirme que seul le nouvel endpoint et ses deux schémas modifient le contrat métier. L’URL serveur générée reflète désormais localhost:8080 ; l’application continue de fournir sa configuration d’API existante.
