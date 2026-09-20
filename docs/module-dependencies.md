# Dépendances autorisées des modules

Statut : graphe cible documenté le 17 septembre, actualisé le 20 septembre 2026 pour les tranches identity/organization, catalog, grouping, ordering, shipping et payment. Les annotations de ces modules encodent désormais leurs dépendances autorisées ; les autres dépendances restent une cible. Le test vérifie frontières, cycles, modules détectés et interfaces publiques.

## Sens des flèches

A → B signifie : A peut importer les contrats publics de B, appeler son API ou écouter ses événements. Le sens de circulation d'un événement est inverse de la dépendance de son consommateur.

Seuls api, api.dto et api.event, réunis dans l'interface nommée api, sont publics. Aucun accès aux repositories, entités, services internes ou infrastructure d'un autre module. Aucune classe publique métier à la racine d'un module.

## Matrice cible

| Module | Dépendances directes autorisées |
| --- | --- |
| shared | aucune ; support technique uniquement |
| identity | shared::api |
| organization | identity::api, shared::api |
| media | organization::api |
| catalog | organization::api, shared::api ; media::api reste une cible future |
| shipping | shared::api pour le calculateur pur ; organization::api reste une cible future |
| grouping | organization::api, catalog::api, shipping::api, shared::api |
| payment | organization::api, shared::api |
| ordering | organization::api, grouping::api, shipping::api, shared::api, payment::api |
| notification | organization::api, grouping::api, ordering::api, shipping::api, payment::api |
| audit | identity::api, organization::api, media::api, catalog::api, grouping::api, shipping::api, payment::api, ordering::api, notification::api |

Une autorisation n'impose pas d'ajouter la dépendance : chaque usage doit répondre au slice en cours. identity, organization, catalog, grouping, ordering et shipping utilisent shared::api pour les erreurs HTTP stables. shared fournit aussi une horloge injectable sans dépendance métier. Cette nécessité concrète est consignée dans ADR-0006 ; catalog réutilise ce contrat technique conformément à ADR-0007 ; aucune dépendance vers media n'est ajoutée dans cette tranche.

L'ordre suivant démontre l'absence de cycle ; chaque dépendance pointe vers un module antérieur :
shared, identity, organization, media, catalog, shipping, grouping, payment, ordering, notification, audit.

organization centralise les permissions. identity ne dépend pas d'organization pour l'onboarding ; organization peut consommer un fait d'identité ou être appelé depuis un point d'entrée qu'il possède. media et catalog ne dépendent pas des modules commerciaux qui les utilisent.

## Contraintes impératives

- shipping ne dépend PAS de payment ni de ordering ni de grouping.
- payment ne dépend PAS de ordering ni de shipping.
- grouping ne dépend pas de ordering : une campagne peut changer d'état sans importer une commande.
- ordering est propriétaire de la coordination des obligations INITIAL_ORDER et SHIPPING_BALANCE.
- audit et notification consomment des faits ; les modules producteurs ne les appellent pas en retour.
- Aucun DTO, enum ou événement métier n'est déplacé dans shared pour contourner une dépendance interdite.
- Les types contenus dans une signature publique doivent eux-mêmes appartenir à une interface publique explicitement exposée.

grouping peut utiliser les contrats publics de tarification de shipping. shipping reçoit seulement ses propres DTO de conditions/quantités et des UUID ; il ne lit pas GroupingCampaign ou Order. payment reçoit ses propres DTO de demande et des identifiants ; il ne calcule ni prix d'offre ni coût du transport.

## Contrats d'événements envisagés

Sauf PaymentRequestSettled désormais implémenté, ces noms désignent des contrats cibles. Chaque événement transactionnel comporte eventId, occurredAt, organisation quand applicable, identifiants minimaux et version/révision pertinente. Aucune donnée personnelle inutile.

| Producteur / propriétaire du contrat | Fait | Consommateurs pertinents |
| --- | --- | --- |
| identity | UserAccountCreated / Disabled | organization, audit |
| grouping | CampaignOpened / Closed / Arrived / Cancelled | ordering, notification, audit |
| grouping | OfferPriceChanged | audit ; ordering seulement si un besoin de projection le justifie |
| ordering | OrderPlaced / Confirmed / Cancelled / Completed | notification, audit ; traitements locaux ordering |
| shipping | ShippingAssessmentFinalized / Revised | ordering, audit |
| payment | PaymentRequestSettled (implémenté) | ordering (implémenté) ; notification/audit futurs |
| payment | PaymentSucceeded / Failed / ReconciliationRequired (cibles futures) | notification, audit |
| payment | PaymentRefundSucceeded / Failed | ordering, audit |
| notification | NotificationDeliveryRecorded | audit |

L'audit de prix doit disposer des valeurs précédente/nouvelle, de la devise, de l'acteur et de la révision ; ce sont des données commerciales nécessaires à ce fait. Une commande ne se recalcule jamais à partir de OfferPriceChanged.

Interdiction de faire écouter OrderPlaced à payment ou shipping dans ce graphe : ordering importe déjà leurs APIs. Interdiction de faire écouter PaymentSucceeded à shipping. Un événement ne supprime pas la dépendance Java créée par son import.

## Placement et coordination

1. ordering résout le contexte public via grouping. organizationId n'est jamais une autorité provenant du navigateur.
2. grouping valide la campagne, les offres, les versions/prix et arbitre leurs modifications concurrentes par son API publique.
3. ordering obtient si nécessaire un devis de shipping à partir de données validées, puis conserve les snapshots et place la commande.
4. L'admission et le prix accepté sont atomiques avec le placement. Une courte transaction commune, explicitement limitée à cet invariant, est permise dans le monolithe ; aucun accès direct aux repositories voisins.
5. INITIAL_ORDER est créée via payment::api dans la transaction de checkout (ADR-0010, amendement explicite). Les futures évaluations transport et obligations supplémentaires restent des étapes idempotentes séparées du placement.
6. payment confirme les faits prestataires dans sa transaction et publie ses événements. ordering les consomme sans faire régresser une commande expirée/annulée.
7. shipping publie les calculs finalisés/révisés. ordering rapproche ces faits des obligations et mouvements payment, puis crée si nécessaire une demande SHIPPING_BALANCE ou coordonne une régularisation autorisée.
8. Les notifications et l'audit se traitent indépendamment. Leur panne n'annule pas un paiement réussi ou une commande déjà placée.

Cette coordination concerne des étapes liées au même achat, sans cascade synchrone vers notification/audit. La politique de reprise et le suivi des étapes sont nécessaires avant la mise en production d'un tel flux.

## Fiabilité et concurrence

Les événements transactionnels utilisent la publication persistante Spring Modulith. Le consommateur applique son changement et sa marque d'idempotence dans une même transaction. Une livraison répétée peut survenir même avec le registre.

Les clés métier des obligations distinguent commande, purpose et révision/ajustement. Une unicité sur eventId seule ne suffit pas si deux événements distincts décrivent la même obligation. Une révision ancienne reçue après une nouvelle ne remplace pas la nouvelle.

Une projection des règlements dans ordering reste une projection, pas une deuxième source de vérité financière. Avant une action sensible, sa cohérence avec les révisions et faits de payment doit être garantie par le protocole du slice.

Reprise : repérer publications/étapes incomplètes, reprendre de manière bornée et idempotente, conserver les raisons d'échec et alerter sur blocage/ancienneté. Le nombre de tentatives, délais et responsable d'intervention restent des paramètres opérationnels à définir avant activation du workflow. Pas de boucle infinie silencieuse.

## Graphe Java et graphe SQL

Une FK scalaire payment.orderId vers ordering n'importe aucune classe ordering et n'autorise aucun accès à son repository. Le graphe SQL peut donc différer du graphe Java.

Les FK intermodules sont autorisées dans le PostgreSQL unique, sans cascade de suppression ni de mise à jour intermodule. Les migrations sont coordonnées ; les données continuent d'appartenir à leur module. Les lectures transversales passent par les APIs ou projections autorisées, pas par des jointures cachées vers les tables voisines.

## Vérification future

identity, organization, catalog et grouping matérialisent déjà leurs allowedDependencies ; les prochains slices devront compléter uniquement leurs propres déclarations conformément à cette matrice. Le test de modularité n'a été ni filtré ni affaibli.

ApplicationModules.of(GroupiaApplication.class).verify() reste dans les tests normaux, sans exclusions ni ouverture artificielle des modules. Il vérifie les dépendances de code, pas les autorisations métier, FK, effets des événements ou règles de concurrence : ces derniers exigent leurs propres tests.

Voir [fondation existante](architecture.md) et [vérification Spring Modulith](https://docs.spring.io/spring-modulith/reference/verification.html).

Décisions et périmètre de la tranche implémentée : [ADR-0006](../adr/0006-identity-organization-slice.md).

## Contrats utilisés par la troisième tranche

grouping dépend exactement de :
- organization::api : permissions VIEW_GROUPINGS/MANAGE_GROUPINGS, acteur autorisé et disponibilité ACTIVE ;
- catalog::api : CatalogVariants.findByIds(organizationId, variantIds) et CatalogVariantView, sans repository/entité/service interne ;
- shipping::api : vocabulaires et ShippingTerms, sans calcul, table ou évaluation de transport ;
- shared::api : erreurs techniques stables.

Le storefront passe par GroupingQueries et un DTO public dédié ; les APIs Java catalog et organization ne sont pas des routes HTTP anonymes. Seul GET /api/public/groupings/{publicIdentifier} est ouvert dans la sécurité. Les données privées restent derrière leurs routes JWT et contrôles de permission.

Le journal grouping_changes est une écriture locale atomique à grouping. Aucun événement/consommateur intermodule supplémentaire n'est créé ; on ne présente pas une publication sans consommateur comme une trace d'audit persistée. Voir ADR-0008.

## Contrats utilisés par la quatrième tranche

- ordering → grouping::api : CheckoutAdmission.lockCampaign puis quote, sous la même transaction ; DTO commerciaux autoritaires sans entités JPA.
- ordering → organization::api : VIEW_ORDERS pour les lectures organisateur.
- ordering → shipping::api : ShippingEstimator et ses entrées décimales, calcul pur.
- ordering → shared::api ; shipping → shared::api : erreurs stables.
- grouping conserve son graphe : commercialSnapshots dans catalog::api fournit uniquement les données nécessaires aux snapshots ; countryCode dans organization::api fournit le contexte de téléphone.
- Aucune dépendance directe ordering → catalog, aucune dépendance payment et aucun cycle.

Les trois routes publiques autorisées sont désormais le storefront GET, le checkout
POST et le suivi GET avec secret en header. Les endpoints organisateur restent
authentifiés et vérifient une permission explicite. Voir API commandes et ADR-0009.

## Contrats utilisés par la cinquième tranche

- ordering → payment::api : PaymentOperations, DTO, PaymentRequestSettled ; dépendances précédentes conservées.
- payment → organization::api : VIEW_PAYMENTS ; payment → shared::api : erreurs stables.
- Aucun import ordering/shipping dans payment, aucun lien JPA intermodule.
- payment.api, api.dto et api.event restent l'interface nommée api fermée explicitement ; le port provider reste interne à payment.
- shared.infrastructure active async/scheduling et reprend uniquement le registre technique Modulith, sans dépendance métier.

La confirmation par événement remplace toute hypothèse d'appel de Payment vers
OrderRepository. La création INITIAL_ORDER dans le checkout est l'exception
atomique explicitement demandée ; initiation/verify s'exécutent hors transaction.
Voir [ADR-0010](../adr/0010-financial-core-and-settlement.md).
