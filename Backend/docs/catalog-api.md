# API catalogue — tranche Product / ProductVariant

Implémentation du 20 septembre 2026. Tous les endpoints nécessitent un JWT Bearer et une organisation ACTIVE. Voir [authentification existante](identity-organization-api.md) et [ADR-0007](../adr/0007-catalog-variant-identity-and-concurrency.md).

## Endpoints

Préfixe commun : /api/organizations/{organizationId}/products.

| Méthode | Suffixe | Fonction / réponse |
| --- | --- | --- |
| POST | / | Créer produit et variantes atomiquement ; 201 + Location + détail |
| GET | / | Liste paginée de résumés ; 200 |
| GET | /{productId} | Détail avec variantes actives et désactivées ; 200 |
| PUT | /{productId} | Remplacer nom/description éditables ; 200 + détail |
| POST | /{productId}/archive | Archiver ; 200 + détail |
| POST | /{productId}/variants | Ajouter une variante active ; 200 + détail du produit |
| PUT | /{productId}/variants/{variantId} | Remplacer les données éditables d'une variante ; 200 + détail |
| POST | /{productId}/variants/{variantId}/deactivate | Désactiver ; 200 + détail |

Le suffixe / dans ce tableau représente le préfixe lui-même, sans slash final obligatoire. Aucun endpoint DELETE, réactivation ou CRUD générique.

## Création

Exemple de produit simple :

```json
{"name":"Sac","description":"Sac réutilisable"}
```

variants absent, null ou [] crée automatiquement DEFAULT, attributs {}, saleUnit UNIT, active true, sans SKU/poids/volume. Pour préciser unité, SKU ou estimation d'un produit sans options, fournir une variante avec attributes {}.

Exemple de produit à options :

```json
{
  "name": "Chaussures",
  "variants": [
    {"sku": " sport-noir-42 ", "attributes": {"color": "Black", "shoeSize": "EU 42"}, "saleUnit": "PAIR", "estimatedUnitWeightKg": 0.8255},
    {"sku": "SPORT-BLANC-42", "attributes": {"shoeSize": "EU 42", "color": "white"}, "saleUnit": "PAIR"}
  ]
}
```

Le nom est obligatoire, trim serveur, 160 caractères maximum, sans contrôles. Description facultative, 5000 caractères maximum ; blanche devient null. La création accepte au plus 100 variantes explicites par requête. L'identité du produit et de ses variantes, organizationId, status, active, dates, version et canonicalVariantKey sont calculés côté serveur. Les propriétés inconnues sont rejetées, notamment prix et identifiants injectés.

Une variante accepte sku facultatif, attributes objet de chaînes (absent/null = {}), saleUnit UNIT/PAIR/SET/PACK (absent/null = UNIT), estimatedUnitWeightKg et estimatedUnitVolumeM3 facultatifs. Les estimations doivent être strictement positives, au plus 18 chiffres entiers et 18 décimales significatives ; aucun arrondi automatique. Les attributs et SKU sont normalisés selon ADR-0007 ; les numéros de taille doivent être des chaînes.

## Version et modifications

Le détail expose version, utilisée pour toutes les écritures suivantes. Une écriture obsolète donne 409 PRODUCT_VERSION_CONFLICT. Après succès, utiliser la nouvelle version retournée, même pour modifier une autre variante.

PUT produit :

```json
{"version":0,"name":"Nouveau nom","description":"Nouvelle description"}
```

Ajout ou PUT variante :

```json
{
  "version":1,
  "variant":{
    "sku":"SPORT-NOIR-43",
    "attributes":{"color":"black","shoeSize":"EU 43"},
    "saleUnit":"PAIR",
    "estimatedUnitWeightKg":0.83
  }
}
```

Archivage ou désactivation :

```json
{"version":2}
```

PUT remplace l'ensemble des informations éditables : un champ facultatif omis est effacé. Mettre à jour une variante désactivée ne la réactive pas. L'UUID et les dates de création restent stables. La dernière variante active ne peut pas être désactivée. Les attributs/SKU désactivés restent soumis à l'unicité.

Un produit archivé reste consultable mais toutes ses éditions sont refusées. Archiver de nouveau avec la version courante conserve ARCHIVED et incrémente la version comme toute commande acceptée. Il n'existe pas de suppression physique via l'application.

## Liste et réponses

GET accepte page (défaut 0, maximum 1 000 000), size (défaut 20, 1 à 100), status facultatif ACTIVE/ARCHIVED. Sans filtre, les deux statuts sont inclus pour la consultation historique. Tri createdAt décroissant, puis id croissant.

Réponse : items, page, size, totalElements, totalPages. Chaque résumé contient id, organizationId, name, status, createdAt, updatedAt, version. Aucune variante ni description longue dans la liste ; aucune pagination en mémoire d'une jointure avec les variantes.

Le détail ajoute description et variants. Une variante expose id, organizationId, productId, sku, attributes normalisés, canonicalVariantKey, saleUnit, estimations, active, createdAt, updatedAt. Les DTO sont distincts des entités JPA.

## Permissions et isolation

- OWNER / MANAGER : VIEW_CATALOG et MANAGE_CATALOG, toutes les opérations ci-dessus.
- OPERATOR / VIEWER : VIEW_CATALOG uniquement.
- Compte désactivé : 401 ; adhésion révoquée ou permission absente : 403.
- Organisation archivée avec membre autorisé : 404 ORGANIZATION_NOT_FOUND.

La route détermine l'organisation à autoriser. Ensuite toute recherche filtre sur organizationId ET productId. Une variante est recherchée uniquement dans l'agrégat chargé. Une FK composée empêche aussi de stocker une variante sous une autre organisation. Aucune association JPA vers Organization ni lecture de ses tables depuis catalog.

## Erreurs

Même ProblemDetail avec code stable que la première tranche :

| HTTP | Code | Cas |
| --- | --- | --- |
| 400 | VALIDATION_FAILED / INVALID_REQUEST | corps, version ou pagination incorrects, propriété inconnue |
| 400 | INVALID_PRODUCT_INFORMATION | nom/description invalides |
| 400 | INVALID_VARIANT_ATTRIBUTES | attributs hors contrat ou clés normalisées en doublon |
| 400 | INVALID_SKU / INVALID_SALE_UNIT / INVALID_VARIANT_ESTIMATE | données de variante invalides |
| 401 | AUTHENTICATION_REQUIRED | authentification absente/invalide ou compte désactivé |
| 403 | ORGANIZATION_ACCESS_DENIED | organisation non autorisée ou permission absente |
| 404 | PRODUCT_NOT_FOUND / VARIANT_NOT_FOUND | ressource absente du périmètre autorisé |
| 404 | ORGANIZATION_NOT_FOUND | organisation archivée après contrôle d'autorisation |
| 409 | PRODUCT_ARCHIVED | édition/ajout sur produit archivé |
| 409 | DUPLICATE_VARIANT | combinaison déjà présente, même désactivée |
| 409 | SKU_ALREADY_EXISTS | SKU déjà présent dans cette organisation |
| 409 | INVALID_PRODUCT_STATE | désactivation de la dernière variante active |
| 409 | PRODUCT_VERSION_CONFLICT | modification concurrente ou version obsolète |

La version du produit est une protection contre les pertes de modifications, pas une clé d'idempotence pour les créations.

## Persistance et vérification

Migration [004-catalog.sql](../src/main/resources/db/changelog/004-catalog.sql) après 003, avec products et product_variants. FK scalaires sans cascade, unicités, CHECK et index (organisation/statut/date, organisation/date, produit/active, SKU). JSONB pour les attributs ; aucune colonne de prix, stock ou média.

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
```

Docker doit être démarré ; les tests utilisent leurs conteneurs PostgreSQL isolés et jamais la base locale Compose. Le test de nettoyage de la première tranche est étendu aux deux nouvelles tables pour respecter leurs FK, sans assouplir ses assertions. ModularityTests reste inchangé.

Les décisions durables, dont la clé canonique versionnée et la concurrence à la racine, sont consignées dans ADR-0007. Les flux catalogue n'ont pas de consommateur événementiel intermodule dans cette tranche. Aucun mécanisme de paiement, transport, média ou notification n'est ajouté.


## Fichiers de cette tranche

Les changements de la première tranche déjà présents dans le dépôt et la configuration locale .env ne font pas partie de cet inventaire.

- Créé : [src/main/java/sn/groupia/catalog/domain/model/ProductStatus.java](../src/main/java/sn/groupia/catalog/domain/model/ProductStatus.java).
- Créé : [src/main/java/sn/groupia/catalog/domain/model/SaleUnit.java](../src/main/java/sn/groupia/catalog/domain/model/SaleUnit.java).
- Créé : [src/main/java/sn/groupia/catalog/domain/model/VariantSpecification.java](../src/main/java/sn/groupia/catalog/domain/model/VariantSpecification.java).
- Créé : [src/main/java/sn/groupia/catalog/domain/model/ProductVariant.java](../src/main/java/sn/groupia/catalog/domain/model/ProductVariant.java).
- Créé : [src/main/java/sn/groupia/catalog/domain/model/Product.java](../src/main/java/sn/groupia/catalog/domain/model/Product.java).
- Créé : [src/main/java/sn/groupia/catalog/api/dto/ProductSummary.java](../src/main/java/sn/groupia/catalog/api/dto/ProductSummary.java).
- Créé : [src/main/java/sn/groupia/catalog/api/dto/ProductView.java](../src/main/java/sn/groupia/catalog/api/dto/ProductView.java).
- Créé : [src/main/java/sn/groupia/catalog/api/dto/ProductVariantView.java](../src/main/java/sn/groupia/catalog/api/dto/ProductVariantView.java).
- Créé : [src/main/java/sn/groupia/catalog/api/dto/ProductPage.java](../src/main/java/sn/groupia/catalog/api/dto/ProductPage.java).
- Créé : [src/main/java/sn/groupia/catalog/domain/repository/Products.java](../src/main/java/sn/groupia/catalog/domain/repository/Products.java).
- Créé : [src/main/java/sn/groupia/catalog/infrastructure/persistence/JpaProductRepository.java](../src/main/java/sn/groupia/catalog/infrastructure/persistence/JpaProductRepository.java).
- Créé : [src/main/java/sn/groupia/catalog/infrastructure/persistence/ProductPersistence.java](../src/main/java/sn/groupia/catalog/infrastructure/persistence/ProductPersistence.java).
- Créé : [src/main/java/sn/groupia/catalog/application/service/CatalogService.java](../src/main/java/sn/groupia/catalog/application/service/CatalogService.java).
- Créé : [src/main/java/sn/groupia/catalog/infrastructure/web/CatalogController.java](../src/main/java/sn/groupia/catalog/infrastructure/web/CatalogController.java).
- Créé : [src/main/resources/db/changelog/004-catalog.sql](../src/main/resources/db/changelog/004-catalog.sql).
- Créé : [src/test/java/sn/groupia/CatalogIntegrationTests.java](../src/test/java/sn/groupia/CatalogIntegrationTests.java).
- Créé : [adr/0007-catalog-variant-identity-and-concurrency.md](../adr/0007-catalog-variant-identity-and-concurrency.md).
- Créé : [docs/catalog-api.md](../docs/catalog-api.md).
- Modifié : [src/main/java/sn/groupia/catalog/package-info.java](../src/main/java/sn/groupia/catalog/package-info.java).
- Modifié : [src/main/java/sn/groupia/organization/api/OrganizationPermission.java](../src/main/java/sn/groupia/organization/api/OrganizationPermission.java).
- Modifié : [src/main/java/sn/groupia/organization/application/service/OrganizationAuthorization.java](../src/main/java/sn/groupia/organization/application/service/OrganizationAuthorization.java).
- Modifié : [src/main/resources/db/changelog/db.changelog-master.yaml](../src/main/resources/db/changelog/db.changelog-master.yaml).
- Modifié : [src/test/java/sn/groupia/IdentityOrganizationIntegrationTests.java](../src/test/java/sn/groupia/IdentityOrganizationIntegrationTests.java).
- Modifié : [docs/architecture.md](../docs/architecture.md).
- Modifié : [docs/domain-model.md](../docs/domain-model.md).
- Modifié : [docs/business-rules.md](../docs/business-rules.md).
- Modifié : [docs/module-dependencies.md](../docs/module-dependencies.md).
- Modifié : [adr/README.md](../adr/README.md).

## Vérification exécutée le 20 septembre 2026

- `.\mvnw.cmd test` : BUILD SUCCESS, 73 tests, 0 échec, 0 erreur, 0 ignoré.
- `.\mvnw.cmd verify` : BUILD SUCCESS, 73 tests, 0 échec, 0 erreur, 0 ignoré ; JAR exécutable produit.
- Répartition : 35 cas catalogue, 34 cas identity/organization existants, 3 tests d'architecture et 1 test de contexte.
- `ApplicationModules.of(GroupiaApplication.class).verify()` passe sans modification, exclusion ou ouverture de module.
- PostgreSQL/Testcontainers : JSONB, précision décimale, contraintes/FK, unicité et rollback, rôles, séparation des organisations, version obsolète et deux désactivations concurrentes effectivement testés.
- Les journaux locaux non versionnés sont dans target/catalog-final-test.log et target/catalog-final-verify.log. Les violations SQL provoquées volontairement par les tests sont attendues.
- Aucun écart avec AGENTS.md ou les ADR validés. Les décisions supplémentaires sont consignées dans ADR-0007 ; le graphe documenté inclut la dépendance technique catalog vers shared::api, conformément au support existant.
