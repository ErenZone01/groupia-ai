# Fondation modulaire de Groupia

Le point d'entrée est `sn.groupia.GroupiaApplication`. Les modules sont ses
sous-packages directs : identity, organization, catalog, grouping, ordering,
payment, shipping, notification, media, audit et shared.

## Frontières

Chaque module est déclaré par `@ApplicationModule` et reste fermé.
Les dix modules métier suivent intégralement la structure de `AGENTS.md`.
Les fichiers `package-info.java` conservent les packages dans Git sans ajouter
d'entités, de services fictifs, de controllers ou de repositories.

`api`, `api.dto` et `api.event` sont explicitement réunis dans l'interface
nommée `api` avec `@NamedInterface(name = "api", propagate = false)`.
La propagation implicite aux types des signatures est désactivée pour qu'un
type interne ne devienne pas une API par accident. Tout nouveau sous-package
public doit être exposé explicitement. Aucune classe publique ne doit être
placée à la racine d'un module métier : Modulith l'exposerait par défaut.

La fondation initiale ne prédéfinissait pas les dépendances métier. Le graphe
cible est désormais documenté dans [module-dependencies.md](module-dependencies.md).
Il impose notamment l'indépendance de shipping vis-à-vis de payment, et celle
de payment vis-à-vis de ordering et shipping. ordering coordonne les obligations.
Les annotations actuelles ne matérialisent pas encore toute cette matrice ;
les futurs slices devront respecter les interfaces publiques et rester acycliques.
Ne pas ouvrir les modules ni exclure des packages pour faire passer la vérification.

## Décisions complémentaires à AGENTS.md

- `shared` était initialement une réserve technique vide, sans les quatre couches métier et
  sans dépendance autorisée vers un module métier (`allowedDependencies = {}`).
  Il n'est pas déclaré comme module partagé automatiquement chargé dans tous
  les tests Modulith. Tout futur contenu doit avoir une justification concrète.
- Les trois packages publics d'un module constituent une seule interface `api`.
- Le test contrôle aussi la détection exacte des onze modules, leur fermeture
  et l'absence de types publics à la racine des modules métier.
- Le point d'entrée et les tests existants sont déplacés depuis
  `sn.groupia.groupia_ai` vers `sn.groupia` pour couvrir tous les modules.

## Dépendances

Aucune dépendance ajoutée ni modifiée. Java 21, Spring Boot 4.1.1 et Spring
Modulith 2.1.1 sont conservés. Le BOM Modulith gère les versions.

- `spring-modulith-starter-core` : annotations et modèle architectural.
- `spring-modulith-starter-jpa` : infrastructure de publication persistante.
- `spring-modulith-starter-test` (test) : vérification et support de test.
- Les dépendances d'observabilité et d'Actuator existantes sont conservées.

L'arbre Maven résolu confirme ces versions ; le POM publié du starter de test
Modulith 2.1.1 dépend de Spring Boot 4.1.1. Aucun événement ni traitement métier
n'est créé dans cette fondation.

## Vérification

Depuis la racine sous PowerShell, avec Java 21 et Docker démarré :

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
```

`ModularityTests` est exécuté par la suite Maven normale. Il appelle
`ApplicationModules.of(GroupiaApplication.class).verify()` sans filtre ni
exclusion. Il fonctionne sans démarrer Spring ni PostgreSQL. Le test de
contexte existant utilise PostgreSQL via Testcontainers et nécessite Docker.

Pour exécuter seulement la vérification architecturale :

```powershell
.\mvnw.cmd "-Dtest=ModularityTests" test
```

Pour lancer l'application localement avec le service du dépôt :

```powershell
docker compose up -d postgres
.\mvnw.cmd spring-boot:run
```

Le support Docker Compose de Spring Boot fournit les informations de
connexion. Les fonctionnalités métier et leur infrastructure spécifique
seront ajoutées au fur et à mesure des besoins.

## Socle de persistance technique

Le démarrage initial échouait parce que Liquibase était activé sans changelog.
La fondation ajoute un changelog et une seule table technique,
`event_publication`, correspondant au mapping JPA de Modulith 2.1.1.
Hibernate utilise `ddl-auto=validate` : il contrôle le schéma créé par
Liquibase sans le créer ni le modifier. Il n'existe aucune table métier.
Ce choix complète AGENTS.md pour permettre un démarrage réellement vérifié.

Les futures publications transactionnelles utiliseront ce registre. Avant
le premier flux événementiel, il faudra implémenter et tester les consommateurs
idempotents et les outils de reprise. La stratégie retenue est de recenser
les publications incomplètes/échouées via les API Modulith, autoriser une
resoumission contrôlée et supervisée, suivre les tentatives et leur ancienneté,
et arrêter les reprises automatiques au-delà d'un seuil documenté pour
investigation. Aucun ordonnanceur ni mécanisme de reprise métier n'est ajouté
à ce squelette.

L'arborescence complète des sources est disponible dans [source-tree.md](source-tree.md).

## Validation de la fondation (16 septembre 2026)

- `.\mvnw.cmd test` : succès, 4 tests, 0 échec, 0 erreur, 0 test ignoré.
- `.\mvnw.cmd verify` : succès, mêmes 4 tests et JAR exécutable produit.
- Test négatif : une dépendance temporaire de `ordering.application` vers un
  type interne de `catalog.domain` a fait échouer `verify()` avec une violation
  de module. Les deux classes temporaires ont ensuite été supprimées et un
  nettoyage Maven a précédé les validations finales.
- Le test de contexte démarre réellement PostgreSQL avec Testcontainers et
  applique la migration Liquibase avant la validation Hibernate.
## Conception métier validée (17 septembre 2026)

- [Modèle métier V1](domain-model.md) : agrégats, données, invariants, snapshots et transitions.
- [Dépendances des modules](module-dependencies.md) : graphe cible et coordination sans cycles.
- [Règles métier](business-rules.md) : comportements attendus et questions ouvertes par slice.
- [Registre des ADR](../adr/README.md) : paiement, transport, références intermodules, checkout public et prix évolutifs.

La présente étape est documentaire. Elle ne crée aucune entité, migration métier,
API, repository ou service. Les résultats Maven consignés plus haut concernent
la fondation du 16 septembre et ne constituent pas une validation des workflows
métier futurs. Les prix peuvent évoluer pendant OPEN ; chaque nouveau checkout
revalide le prix et la cohérence de l'offre, sans réécrire les commandes existantes.

## Première tranche métier (18 septembre 2026)

La tranche identity/organization implémente inscription, connexion JWT,
création atomique d'une organisation avec son OWNER et lectures autorisées.
Voir [contrat API](identity-organization-api.md), [fichiers](identity-organization-files.md)
et [ADR-0006](../adr/0006-identity-organization-slice.md).

Deux migrations métier sont ajoutées après le registre technique : 002-identity
et 003-organizations. Hibernate conserve ddl-auto=validate. shared contient
maintenant uniquement le support HTTP d'erreurs et une horloge technique ;
identity et organization déclarent leurs dépendances à ses contrats publics.
Le lancement nécessite GROUPIA_JWT_SECRET_BASE64, fourni extérieurement et sans
valeur de repli ; les tests génèrent une clé éphémère dans leur propre contexte.
## Deuxième tranche métier (20 septembre 2026)

catalog implémente Product et ses ProductVariant dans un seul agrégat versionné.
Les endpoints sont scoped par organisation et utilisent les permissions centralisées
VIEW_CATALOG / MANAGE_CATALOG. Les références d'organisation restent scalaires ;
la relation JPA Product-ProductVariant est interne au module.

La migration 004-catalog ajoute products et product_variants, avec attributs JSONB,
unicités, FK composée de cohérence organisationnelle et index de lecture.
Aucune dépendance Maven ajoutée ; aucune modification de ModularityTests.
Le nettoyage des fixtures identity/organization inclut désormais les nouvelles tables
afin de respecter leurs FK, sans changer les assertions de la première tranche.

Voir [API catalogue](catalog-api.md) et [ADR-0007](../adr/0007-catalog-variant-identity-and-concurrency.md).
Aucune fonctionnalité de campagne, offre, achat, transport, paiement ou média.

## Troisième tranche métier (20 septembre 2026)

grouping implémente GroupingCampaign et GroupingOffer comme racines distinctes,
les transitions DRAFT/OPEN/CLOSED/CANCELLED demandées et la lecture publique filtrée.
Le versioning de campagne coordonne aussi les mutations d'offres dans une courte
transaction. Un journal local conserve atomiquement prix et échéances modifiés.

La migration 005-grouping ajoute grouping_campaigns, grouping_offers et grouping_changes,
ainsi que la contrainte unique de référence nécessaire sur product_variants. Aucun
changelog déjà appliqué n'est réécrit. Hibernate reste en ddl-auto=validate.

Les extensions des modules existants restent limitées à leurs contrats publics :
CatalogVariants pour la lecture minimale par organisation, permissions/disponibilité
dans organization, vocabulaires et ShippingTerms dans shipping. Aucun workflow,
table, service de calcul ou endpoint shipping n'est implémenté.

Seul GET /api/public/groupings/{publicIdentifier} devient anonyme. Ses DTO n'exposent
aucun identifiant interne d'organisation/campagne, membre, acteur ou historique privé.
ModularityTests est inchangé. Aucune dépendance Maven supplémentaire.

Voir [API groupage](grouping-api.md) et [ADR-0008](../adr/0008-grouping-publication-and-coordination.md).

## Quatrième tranche métier (20 septembre 2026)

ordering implémente Customer et l'agrégat Order/OrderItem, checkout sans compte,
suivi privé par secret et consultations organisateur avec VIEW_ORDERS.
La transaction d'admission utilise exclusivement grouping::api pour garder le
verrou PostgreSQL de campagne jusqu'au commit. Les mêmes clés sont sérialisées
dans leur organisation et protégées aussi par UNIQUE en base.

shipping expose uniquement un calculateur d'estimation pur. Aucun paiement,
événement aval, évaluation persistante, scheduler ou notification. L'absence de
consommateur dans cette tranche rend les tests de reprise d'événements non
applicables ; les rollback et rejeux du placement sont vérifiés.

Migration 006-ordering, FK scalaires sans cascade, JSONB pour les snapshots.
Aucune dépendance Maven ajoutée ou modifiée. ModularityTests demeure inchangé.
Les enums conservent les états cibles documentés, y compris OrderStatus.EXPIRED
et FulfillmentStatus.CANCELLED ; aucune transition vers ces états n'est exposée.
Contrat complet : [API commandes](ordering-api.md).
Décisions et limites : [ADR-0009](../adr/0009-checkout-admission-idempotency-and-tracking.md).
