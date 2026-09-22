# ADR-0011 — Compatibilité avec la première migration Ordering déployée

- Statut : accepté, correction de démarrage
- Date : 2026-09-21
- Complète ADR-0009 et ADR-0010

## Incident constaté

Le PostgreSQL local avait déjà exécuté 006-ordering avec le checksum
`9:aa0b0f88a758f6300ff30ca7ca1365aa`. Le SQL ensuite versionné porte le checksum
`9:e0bde6c3e9dc14001d170e1204b06a7b`. Liquibase refuse donc le démarrage avant 007.
Les tests précédents partaient de bases vides et ne couvraient pas cette mise à niveau.
Le message Maven BUILD SUCCESS avec DevTools n'était pas une preuve de démarrage.

La comparaison du schéma déployé identifie quatre contraintes divergentes :
ck_orders_status sans EXPIRED, ck_orders_fulfillment sans CANCELLED,
ck_orders_snapshots sans validation des clés obligatoires, et absence de
ck_orders_snapshot_currency. Le test reconstruit cette version historique et
vérifie son checksum réel avant la migration, sans falsifier DATABASECHANGELOG.

## Correction

006 conserve son SQL actuel et déclare uniquement le checksum historique précis
avec validCheckSum. Son checksum courant reste inchangé. Cette exception ciblée
permet à Liquibase de poursuivre ; elle ne rejoue pas CREATE TABLE et n'applique
pas, à elle seule, les contraintes manquantes.

008-ordering-constraint-upgrade réconcilie explicitement les quatre contraintes
pour que les bases anciennes et récentes convergent. L'opération reste une
transaction PostgreSQL : aucun effacement ni réécriture des commandes, aucun
changement global de checksums, aucune désactivation de Liquibase/Hibernate.
Les contraintes sont validées sur les lignes existantes. Si des snapshots
historiques sont incomplets/incohérents, la migration échoue et nécessite une
investigation explicite ; elle ne fabrique pas de valeurs historiques.

La migration ne propose pas de rollback automatique : retirer EXPIRED/CANCELLED
pourrait rendre invalides des commandes créées depuis leur introduction. Une
correction ultérieure passe par une nouvelle migration et une décision explicite.
Les prochains changements de schéma doivent toujours être ajoutés dans un nouveau
changeset ; cette exception documente seulement les deux versions déjà connues.

## Vérifications

OrderingMigrationUpgradeTests utilise PostgreSQL/Testcontainers et teste :

- ancienne 006 effectivement exécutée avec son checksum historique ;
- 006 actuelle exécutée ;
- 007 déjà exécutée.

Chaque scénario vérifie la conservation intégrale d'une commande existante,
le maintien du checksum historique, les nouvelles contraintes, l'absence de
débit et l'absence de nouvelle exécution au second passage. Les cas antérieurs
à 007 vérifient aussi la création de l'obligation à partir des snapshots.
La suite normale continue de couvrir le démarrage sur une base vierge.

Référence : [checksums Liquibase 5.0](https://docs.liquibase.com/community/user-guide-5-0/what-is-a-changeset-checksum).

## Vérification exécutée le 21 septembre 2026

- `.\mvnw.cmd test` : 260 tests, zéro échec/erreur/ignoré, BUILD SUCCESS.
- `.\mvnw.cmd verify` : mêmes 260 tests, BUILD SUCCESS et JAR produit.
- Base locale sauvegardée dans target/before-ordering-upgrade.sql avant démarrage.
- `docker compose up -d postgres`, puis `.\mvnw.cmd spring-boot:run` sur le port
  temporaire 18080 : Started GroupiaApplication, validation Hibernate réussie.
- 007/008 appliquées ; ancien checksum 006 intact ; quatre contraintes corrigées
  présentes et validées. Aucune commande ni client supprimé.
- Requête storefront pour un UUID inexistant : HTTP 404 GROUPING_CAMPAIGN_NOT_FOUND,
  réponse métier attendue confirmant le fonctionnement du serveur et de PostgreSQL.

Logs locaux : target/startup-fix-test.log, target/startup-fix-verify.log et
 target/startup-fix-run.log. La sauvegarde et les logs restent dans target, ignoré
par Git. Les modifications personnelles de .env et docker_cloudflared.yaml
n'ont pas été modifiées par cette correction.
