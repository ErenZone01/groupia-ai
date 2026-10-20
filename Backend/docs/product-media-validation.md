# Product Media V1 — livraison et validation

Branche : `product-media-v1`. Base : `558c1208b99fb3a628f1fd211aef57c31830e58f`.

Dépendances ajoutées : BOM AWS SDK Java v2 **2.56.0**, `s3` et `url-connection-client` ; TwelveMonkeys **3.15.3** (`imageio-webp`, `imageio-jpeg`, `common-image`) ; `metadata-extractor` **2.21.0**. Aucune nouvelle dépendance npm ni bibliothèque native.

## Architecture et comportement livré

1. **Architecture** : Media possède la galerie, les métadonnées, la normalisation et le cycle de stockage. PostgreSQL reste l'autorité des états.
2. **Dépendances** : `media → catalog::api`, `organization::api`, `shared::api` uniquement. Aucune dépendance inverse du catalogue et aucune association JPA inter-module.
3. **Migration** : seule `029-product-media.sql` est ajoutée au changelog maître.
4. **Modèle** : `product_media_galleries` porte la version ; `product_media` porte les deux rendus, empreintes, positions, idempotence, échéances et états UPLOADING / READY / DELETE_PENDING / DELETED. Une FK composite garantit organisation/produit ; la position unique différable et bornée garantit cinq réservations maximum.
5. **Filesystem** : racine canonique, clés serveur validées, refus des liens symboliques, écriture temporaire puis déplacement atomique ; endpoint public limité aux rendus READY résolus par UUID.
6. **S3** : port neutre `MediaObjectStorage`, adapter AWS SDK Java v2, endpoint/région/bucket configurables, PUT/HEAD/DELETE hors transaction, erreurs expurgées.
7. **R2** : cible initiale Cloudflare R2 Standard ; chunked encoding désactivé explicitement par défaut, adressage path-style configurable. Aucun compte AWS/R2 requis pour développer et tester. Un essai R2 réel reste à faire en préproduction.
8. **Pipeline** : octets bornés → signature/container → dimensions/pixels → décodage → orientation → pixels sRGB → redimensionnement → réencodage → réservation → stockage → publication.
9. **Formats** : JPEG, PNG statique et WebP statique en entrée ; JPEG opaque (qualité 0,85) ou PNG transparent en sortie. Refus des formats/animations non admis.
10. **Vie privée** : réencodage sans métadonnées source EXIF/XMP/commentaires ; orientations 2–8, miroirs compris. Aucun original conservé dans le stockage média.
11. **Limites** : fichier 8 MiB, requête 9 MiB, un fichier/requête, axes 6000 px, 24 millions de pixels ; display 2000 px / 4 MiB, thumbnail 480 px / 512 KiB. Pas d'agrandissement. Un traitement lourd simultané par instance, sans file d'attente infinie.
12. **Upload/idempotence** : UUID et empreinte du contenu ; même opération retrouvée après réponse perdue, contenu différent rejeté. Réservation durable avant PUT, publication READY après revalidation.
13. **Compensation** : aucune écriture objet si réservation échouée ; HEAD en cas d'incertitude ; 202 sans URL si résultat non prouvé ; jamais de suppression aveugle après erreur de finalisation DB.
14. **Cleanup** : maximum 100 opérations par exécution, SKIP LOCKED, bail de deux minutes et jeton de possession. Reprise après cinq minutes ; tombstones réévaluées quotidiennement pendant sept jours après expiration de l'upload. Les uploads expirés sont supprimés, sans publication automatique.
15. **Suppression** : DELETE_PENDING et compactage commités avant suppression physique ; absence objet acceptée ; reprise durable si stockage indisponible ; suppression idempotente.
16. **Ordre/principale** : seule READY position 0 définit l'image principale. Reorder exige les IDs READY exacts et la version courante ; conflit 409. Suppression compacte les positions.
17. **Permissions** : VIEW_CATALOG en lecture, MANAGE_CATALOG en mutation ; organisation/produit/média corrélés. Produit archivé : lecture/suppression permises, upload/reorder refusés.
18. **Endpoints** : GET/POST `…/products/{productId}/media`, GET/DELETE `…/media/{mediaId}`, PUT `…/media/order`. Préfixe privé `/api/organizations/{organizationId}` ; GET public local `/api/public/media/{mediaId}/{rendition}`.
19. **Batch** : GET `…/product-media?productIds=…`, maximum 100 IDs uniques, requêtes SQL groupées ; aucun nouvel appel par carte. Le N+1 catalogue préexistant reste hors périmètre.
20. **Distribution** : URLs reconstruites depuis les clés ; URL publiée après READY ; cache immutable d'un an. Retrait stockage et cache résiduel expressément acceptés. Le CDN devra ajouter `nosniff` ; l'endpoint local le fournit déjà.
21. **Frontend** : cartes avec miniature/placeholder, galerie accessible, uploads séquentiels, principale, monter/descendre, confirmation de suppression, retry avec même clé, aperçu ObjectURL révoqué, protections de contexte/auth génération contre réponses obsolètes.
22. **CSP** : `blob:` pour les aperçus et origine HTTPS exacte configurée pour les images ; aucun wildcard.
23. **OpenAPI** : snapshot obtenu du backend puis client Angular régénéré ; multipart avec boundary produit par le navigateur, aucun DTO généré édité manuellement.
24. **Configuration** : médias désactivés par défaut, mappings globaux `GROUPIA_MEDIA_*`, credentials expurgés des `toString`. En production activée, S3 complet/HTTPS obligatoire et filesystem/memory refusés.
25. **Sans cloud** : mode local filesystem et tests PostgreSQL/Testcontainers + serveur HTTP S3 local. Aucune credential cloud réelle ni dépense requise.

## Résultats des validations

Les résultats finaux seront inscrits après la fin des commandes complètes. Aucun résultat non exécuté n'est considéré PASS.

| Point | Contrôle | Résultat |
|---|---|---|
| 26 | `Backend: .\mvnw.cmd test` | En cours |
| 26 | `Backend: .\mvnw.cmd verify` | À exécuter |
| 27 | `Frontend: npm run lint` | PASS |
| 27 | `Frontend: npm test` | PASS — 391 tests, 36 fichiers |
| 27 | `Frontend: npm run build` | PASS — avertissements NG8107 préexistants |
| 28 | `Frontend: npm run test:e2e` | PASS — 195 tests ; nouvelle galerie testée à 360, 390, 430, 768 et 1280 px |
| 29 | Contrat S3 SDK réel sur serveur HTTP local | PASS — PUT/HEAD/DELETE, corps non chunked, métadonnées, clés et URLs |
| 30 | `python tools/check_migrations.py` | PASS — 22 migrations du baseline figé de l'outil |
| 30 | `python -m unittest discover -s tools -p "test_*.py"` | PASS — 4 tests |
| 30 | Upgrade PostgreSQL 028 → 029 et checksums historiques | PASS ciblé ; inclus dans la suite complète |
| 31 | `ApplicationModules.of(GroupiaApplication.class).verify()` | PASS ciblé ; inclus dans la suite complète |
| 32 | `python tools/scan_secrets.py` | PASS — 0 résultat |
| — | Tests CSP Node | PASS — 2 tests |
| — | Readers ImageIO dans le JAR Spring Boot packagé | À exécuter après verify |
| — | `git diff --check` | PASS ; à confirmer avant commit |

33. **SHA final** : disponible dans la réponse de livraison après commit/push et succès de toutes les validations.
34. **Fichiers majeurs** : `media/application/service/ProductMediaService.java`, `media/infrastructure/image/ImageNormalizer.java`, `media/infrastructure/persistence/JdbcMediaRecords.java`, adapters `media/infrastructure/storage/`, contrôleurs `media/infrastructure/web/`, `catalog/api/CatalogProducts.java`, migration 029, `Frontend/src/app/features/catalog/product-media.*` et `product-gallery.ts`. ADR : `Backend/adr/0029-product-media.md`. Exploitation : `Backend/docs/product-media.md`.
35. **Risques résiduels** : intégration R2 réelle et configuration du CDN à valider avant activation cloud ; images publiques sans révocation instantanée ; charge mémoire/CPU à mesurer avec le heap de production ; sauvegardes DB/objets coordonnées ; inventaire des objets après restauration manuelle et purge des temporaires filesystem après crash à prévoir opérationnellement. Pas de référence historique aux images dans les commandes pour cette V1.
36. **Historique SQL** : les blobs Git 001–028 correspondent exactement au baseline ; aucun fichier historique édité. Les fins de ligne locales préexistantes sont gérées par Git. Seule 029 est nouvelle.
37. **Git** : main reste sur le SHA de base. Aucun merge, aucune PR. Commit/push uniquement après résultats verts.
