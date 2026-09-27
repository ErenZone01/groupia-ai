# Logistique du groupage — 27 septembre 2026

## Modèle retenu et responsabilités

Le module shipping possédait déjà ShippingTerms, TransportMode et l’estimation financière, mais aucun modèle d’expédition. Il porte désormais **Shipment**, une expédition unique par boutique/groupage :
- id, organizationId, groupingCampaignId ;
- transportMode, copié des conditions existantes du groupage lors du départ ;
- departedAt, expectedArrivalAt facultatif, arrivedAt facultatif ;
- trackingReference facultative (160 caractères), note privée facultative (2 000 caractères) ;
- createdAt et updatedAt.

GroupingCampaign reste l’autorité du cycle métier. Ses deux nouvelles transitions sont exclusivement SUPPLIER_ORDERED → IN_TRANSIT et IN_TRANSIT → ARRIVED. Aucune association JPA entre modules. Grouping appelle Shipments, interface publique de shipping ; shipping ne dépend pas de grouping. Le graphe de modules existant reste acyclique, sans nouveau module.

La permission existante MANAGE_GROUPINGS protège les mutations (OWNER/MANAGER selon la politique centrale), VIEW_GROUPINGS protège la lecture gérante. Les contrôles d’utilisateur actif, d’adhésion, de boutique active et d’appartenance du groupage sont conservés. Aucune comparaison de rôle dans le contrôleur.

## API

Préfixe privé :

`/api/organizations/{organizationId}/grouping-campaigns/{campaignId}`

| Opération | Contrat |
| --- | --- |
| POST /ship | version obligatoire, departedAt obligatoire, expectedArrivalAt/trackingReference/note facultatifs |
| POST /arrive | version obligatoire, arrivedAt facultatif ; défaut : heure serveur |
| GET /shipment | état/version actuels et expédition privée ; shipment null avant départ |

Exemple de départ :

```json
{
  "version": 7,
  "departedAt": "2026-09-26T09:00:00Z",
  "expectedArrivalAt": "2026-10-01T09:00:00Z",
  "trackingReference": "Référence privée",
  "note": "Consigne interne"
}
```

Le mode de transport n’est pas une nouvelle saisie : il vient de shippingTerms. Les réponses 200, no-store, contiennent :
- campaign : id, organizationId, status, version **retournés par le serveur** ;
- shipment : données de l’expédition, dont les dates réellement enregistrées.

Une version périmée donne 409 GROUPING_CAMPAIGN_VERSION_CONFLICT. Une transition invalide donne 409 INVALID_GROUPING_TRANSITION. Les dates incohérentes donnent 400 INVALID_SHIPMENT_DATES. Le départ et l’arrivée ne peuvent pas être futurs ; l’arrivée réelle et l’arrivée prévue ne peuvent pas précéder le départ. Une arrivée prévue passée reste admissible lors d’une déclaration tardive. Les instants sont conservés en UTC, à la microseconde PostgreSQL ; le formulaire affiche les dates dans le fuseau de l’appareil.

Les références et notes ne sont pas retournées dans le suivi cliente.

## Transaction, concurrence et historique

CampaignLogistics ouvre une transaction unique :
1. contrôle d’autorisation ;
2. verrou scoped de la ligne GroupingCampaign et contrôle de sa version ;
3. validation de la transition par le domaine ;
4. création ou enregistrement de la première arrivée via Shipments, qui exige la transaction existante ;
5. sauvegarde/flush du groupage, produisant sa nouvelle version ;
6. ajout d’une entrée GroupingChange ;
7. commit de l’ensemble.

Une erreur d’écriture de l’historique annule à la fois l’expédition et le changement de statut. Les verrous sérialisent les demandes concurrentes ; le contrôle optimiste existant reste actif. Une contrainte unique boutique/groupage protège aussi l’expédition.

**Rejeu sûr par refus** : un second POST avec la version d’origine renvoie 409 ; avec la nouvelle version, la transition déjà effectuée reste interdite. Aucun changement de date ni deuxième expédition. Le frontend ne rejoue jamais automatiquement le POST : il demande une actualisation GET, puis une nouvelle action explicite si elle est encore admissible.

GroupingChange était déjà le journal local des changements de prix/échéance. Il est étendu avec LOGISTICS_TRANSITION, fromStatus et toStatus ; il conserve acteur, instant de déclaration et nouvelle version du groupage. Cet instant est distinct de la date réelle de départ/arrivée éventuellement déclarée a posteriori.

Aucun nouvel événement Modulith n’est introduit : il n’y a aucun effet aval asynchrone requis dans cette tranche. Le suivi lit l’état confirmé en base. La cohérence statut/expédition/historique exige ici une transaction atomique, autorisée par AGENTS.md. Le registre persistant d’événements et ses consommateurs existants restent inchangés.

## Migration 011

Nouvelle migration Liquibase **011-shipments.sql**, ajoutée au master :
- table shipments avec FK scalaire composite vers le groupage de la boutique ;
- unicité (organization_id, grouping_campaign_id) et contrôles du mode et des dates ;
- colonnes de transition et extension de la contrainte de forme de grouping_changes ;
- trigger protégeant les données du départ et la première arrivée ;
- trigger protégeant les nouvelles entrées d’historique logistique contre modification/suppression.

En V1, le départ (y compris référence/note/estimation) est conservé sans endpoint d’édition. La seule mise à jour permise est la première arrivée. Aucun ancien changeset 001–010 ni donnée financière existante n’est modifié. Pas d’index supplémentaire inutile : l’unicité sert les lectures scoped et l’index existant du journal sert son accès par groupage/date.

## Suivi public et frontière de confidentialité

Le GET existant **/api/public/orders/track** conserve son accès par X-Order-Tracking-Token, son contrôle d’expiration, sa limitation des tentatives et no-store. Les UUID boutique/groupage sont dérivés de la commande autorisée, jamais fournis librement par la cliente.

Son nouveau DTO spécifique OrderTrackingView conserve les champs de suivi existants et ajoute groupingProgress :
- status du groupage ;
- departedAt ;
- expectedArrivalAt ;
- arrivedAt.

Cette liste explicite exclut référence fournisseur, note, auteur, identifiants internes et jeton de suivi. OrderReceipt et le checkout restent inchangés. Les lectures cohérentes utilisent REPEATABLE_READ.

La timeline Angular traduit les états en français. Elle n’affiche une commande confirmée que selon Order.status ; les étapes suivantes suivent le groupage. **Arrivée n’implique jamais Prête à récupérer** : cette dernière étape nécessite le véritable FulfillmentStatus READY_FOR_PICKUP/PICKED_UP. NOT_READY reste inchangé. La progression peut être relue avec « Actualiser le suivi » ; le comportement de polling existant est conservé, sans nouveau suivi permanent.

## Interface gérante

Dans l’onglet Aperçu du détail groupage :
- Commandée au fournisseur : CTA « Marquer comme en transport », formulaire avec transport déjà connu, départ, arrivée prévue, référence et note ;
- En transport : dates et informations utiles, CTA « Marquer comme arrivée » et confirmation courte ;
- Marchandise arrivée : première date d’arrivée conservée ; annonce textuelle de la préparation future.

La version vient toujours de la dernière réponse serveur. Les erreurs bloquent une nouvelle mutation jusqu’à relecture. Une réponse perdue se récupère en GET ; aucune simulation locale du statut. Double clic protégé côté interface et backend. Changer de boutique efface les champs privés en cours et annule la souscription précédente.

Les composants utilisent les tokens existants, les contrôles HTML accessibles et les thèmes clair/sombre. Aucun coût de transport final, nouveau paiement ou retrait n’est créé.

## Recette manuelle

Depuis Backend, démarrer les dépendances avec `docker compose up -d`, puis `.\mvnw.cmd spring-boot:run`. Depuis Frontend, lancer `npm start`.

1. Créer et publier un groupage avec ses conditions de transport.
2. Obtenir des commandes clientes payées et confirmées par le parcours normal. Conserver une session cliente avec son jeton de suivi.
3. Fermer le groupage ; attendre le règlement ou l’expiration des commandes encore actives.
4. Déclarer la commande fournisseur dans « À commander » ; constater le bon figé.
5. Revenir à Aperçu et cliquer « Marquer comme en transport ».
6. Renseigner le départ, éventuellement l’arrivée prévue et une référence privée ; confirmer.
7. Vérifier « En transport », les dates et la version serveur actualisée. Recharger : les mêmes données doivent apparaître.
8. Dans la session cliente, ouvrir le suivi privé puis actualiser.
9. Vérifier « Commandée au fournisseur » franchie, « En transport » actuelle, arrivée/retrait encore en attente ; aucune note/référence privée visible.
10. Côté gérante, cliquer « Marquer comme arrivée » et confirmer.
11. Vérifier « Marchandise arrivée » et la date serveur. Actualiser : la première date reste identique.
12. Actualiser le suivi cliente : « Arrivée » actuelle, « Prête à récupérer » encore en attente. Le statut commercial, les montants et le bon fournisseur restent identiques.

Contrôles complémentaires : refus d’une autre boutique, d’un compte sans permission, d’une version périmée, de dates inversées et de doubles clics. Tester 360 px et clair/sombre.

Cette recette sur le serveur et le sandbox réels reste manuelle. Les tests automatisés utilisent PostgreSQL Testcontainers et des réponses HTTP navigateur isolées ; ils n’effectuent aucun paiement Bictorys réel.

## Validation

- Backend mvnw test : **458 tests réussis**, zéro échec/erreur/ignoré.
- Backend mvnw verify : **458 tests réussis**, zéro échec/erreur/ignoré ; BUILD SUCCESS et JAR produit.
- ModularityTests : trois tests, dont ApplicationModules.of(GroupiaApplication.class).verify(), sans exclusion ni modification des frontières autorisées.
- npm run api:generate : réussi depuis l’export réel /v3/api-docs produit par les tests Spring, servi localement pendant la génération ; **66 modèles et 10 services**, aucun DTO Angular manuel.
- Angular : **171 tests réussis**, 22 fichiers.
- Navigateur : **79 scénarios réussis**, dont neuf nouveaux scénarios logistiques.
- Build production : **réussi**, budgets Angular respectés.

Les ajouts comprennent 27 cas d’intégration logistique, un test OpenAPI, 19 tests unitaires Angular et neuf scénarios navigateur. Les tests backend vérifient aussi rollback, concurrence, dates immuables, isolation et invariance financière/SupplierOrder. Les fixtures de nettoyage des anciens tests incluent shipments pour respecter la FK ; aucune logique de production Bictorys n’est modifiée.

## Fichiers et limites

Backend : shipping/api/Shipments, ShipmentView, Shipment, ShipmentRepository, ShipmentService et JdbcShipments ; grouping/CampaignLogistics, CampaignLogisticsController, GroupingProgressQueries et DTOs ; transitions GroupingCampaign et historique GroupingChange ; OrderQueries/OrderTrackingView pour le suivi ; migration 011 et tests.

Frontend : client généré et openapi.json ; logistics-api, logistics-panel et intégration dans le détail groupage ; order-timeline et suivi ; messages et libellés ; tests associés. Aucune dépendance ajoutée.

Hors tranche : expéditions partielles, correction des dates/références après déclaration, API transporteur, géolocalisation, notifications, répartition du transport, poids réel, solde transport, préparation et retrait. Aucun changement de Bictorys, du checkout public ou du modèle SupplierOrder.

Contrôle final : git diff --check réussi. La comparaison OpenAPI confirme que seule l’opération existante GET /api/public/orders/track change ; les trois autres opérations sont nouvelles. Captures mobile inspectées dans Frontend/artifacts (logistics-manager-360-light.png et logistics-tracking-transit-360-dark.png) ; les neuf scénarios logistiques ont aussi été rejoués avec succès après finalisation des captures.
