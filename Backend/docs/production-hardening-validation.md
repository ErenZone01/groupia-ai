# Validation locale — Production hardening V1

Date : 10 octobre 2026. Branche : `production-hardening-v1`.
Base attendue : `873605a27d1b34e5b0dd4a373db5dcfe3270da53`.

## Résultats

| Contrôle exécuté | Résultat |
| --- | --- |
| Backend `./mvnw.cmd test` | BUILD SUCCESS, 770 tests, 0 échec, 0 erreur, 0 ignoré |
| Backend `./mvnw.cmd verify` | BUILD SUCCESS, 770 tests, 0 échec, 0 erreur, 0 ignoré ; packaging et Spotless verts |
| `ModularityTests` inclus dans les deux commandes | 3 tests verts ; `ApplicationModules.of(GroupiaApplication.class).verify()` PASS |
| `ProductionHardeningTests` inclus dans les deux commandes | 9 tests verts |
| Frontend `npm ci` | Réinstallation réussie |
| Frontend `npm run lint` | PASS |
| Frontend `npm test` | 280 tests, 31 fichiers, tous verts |
| Frontend `npm run build` | Build production réussi |
| Frontend `npm run test:e2e` | 147 tests Playwright verts |
| Frontend `npm audit --audit-level=high` | 0 vulnérabilité signalée |
| `python tools/check_migrations.py` | 22 migrations conformes à la base immuable |
| `python -m unittest discover -s tools -p test_migrations.py` | 4 tests verts |
| actionlint 1.7.12, image épinglée par digest | PASS |
| Snapshot OpenAPI | Identique sémantiquement à l'export backend final |
| Client généré | Hashes identiques aux fichiers validés par les tests frontend |

Les logs locaux détaillés sont exclus de Git : `Backend/target/hardening-test-final.log`, `Backend/target/hardening-verify.log` et `Frontend/artifacts/hardening-*-final.log`. Les contrôles secrets et Git sont consignés dans le rapport de livraison après leur dernière exécution.

Les migrations 001–022 n'ont pas été écrites ou corrigées. Le garde-fou vérifie les blobs Git byte-for-byte et tient compte uniquement de la conversion de fins de ligne préexistante dans le checkout Windows. La seule nouvelle migration est 023, dédiée aux quotas d'authentification. Aucun reset ou clearCheckSums.

Les avertissements Angular NG8107 préexistants dans l'éditeur de transport ne bloquent pas le build ; ils n'ont pas été masqués. Les tests sont locaux et utilisent des fournisseurs désactivés/doublés : ils ne prouvent pas l'exécution de la CI sur GitHub ou le bon paramétrage de l'origine de production.

## Avant production

Appliquer [la checklist de rotation](../../SECURITY-ROTATION.md) et [le guide de production](production-hardening.md), notamment : secrets indépendants, révocation des anciennes clés, `POSTGRES_PASSWORD` réel pour Compose local, profil prod, HTTPS/proxy et quotas edge, management privé, collecte/alertes, protection GitHub et restauration de sauvegarde dans un environnement isolé.

Aucune rotation fournisseur, charge sur une base réelle, restauration de sauvegarde, mise en production, fusion ou publication Git n'a été effectuée. Le verrou global du limiteur, le quota réseau partagé derrière un proxy et le backlog du scheduler restent à mesurer. SpotBugs et un scanner CVE JVM dédié sont différés, comme expliqué dans le guide.

## Inventaire des fichiers

Fichiers ajoutés, modifiés ou désindexés par rapport à la base (le javadoc généré racine est désindexé ; `user-account-view.ts` est supprimé par régénération OpenAPI) :

- `.github/dependabot.yml`
- `.github/workflows/ci.yml`
- `.gitignore`
- `.gitleaks.toml`
- `Backend/.env.example`
- `Backend/adr/0022-production-hardening.md`
- `Backend/adr/README.md`
- `Backend/compose.yaml`
- `Backend/docker_cloudflared_quick.yml`
- `Backend/docs/identity-organization-api.md`
- `Backend/docs/production-hardening-gap.md`
- `Backend/docs/production-hardening-validation.md`
- `Backend/docs/production-hardening.md`
- `Backend/mvnw`
- `Backend/pom.xml`
- `Backend/postman/Groupia-local.postman_collection.json`
- `Backend/postman/README.md`
- `Backend/src/main/java/sn/groupia/customeraccess/infrastructure/AccessConfiguration.java`
- `Backend/src/main/java/sn/groupia/identity/domain/repository/AuthLimitBuckets.java`
- `Backend/src/main/java/sn/groupia/identity/infrastructure/hardening/AuthLimitSettings.java`
- `Backend/src/main/java/sn/groupia/identity/infrastructure/hardening/AuthNetworkFilter.java`
- `Backend/src/main/java/sn/groupia/identity/infrastructure/hardening/AuthRateLimits.java`
- `Backend/src/main/java/sn/groupia/identity/infrastructure/hardening/PostgresAuthLimitBuckets.java`
- `Backend/src/main/java/sn/groupia/identity/infrastructure/security/SecurityConfiguration.java`
- `Backend/src/main/java/sn/groupia/identity/infrastructure/web/AuthController.java`
- `Backend/src/main/java/sn/groupia/notification/infrastructure/whatsapp/MetaWhatsAppDeliveryGateway.java`
- `Backend/src/main/java/sn/groupia/ordering/infrastructure/web/TrackingAttempts.java`
- `Backend/src/main/java/sn/groupia/payment/infrastructure/metrics/PaymentMetrics.java`
- `Backend/src/main/java/sn/groupia/payment/infrastructure/provider/bictorys/BictorysException.java`
- `Backend/src/main/java/sn/groupia/settlement/infrastructure/metrics/PayoutMetrics.java`
- `Backend/src/main/java/sn/groupia/shared/infrastructure/hardening/HttpSecurityHeaders.java`
- `Backend/src/main/java/sn/groupia/shared/infrastructure/hardening/ManagementSecurity.java`
- `Backend/src/main/java/sn/groupia/shared/infrastructure/metrics/PublicationMetrics.java`
- `Backend/src/main/java/sn/groupia/shared/infrastructure/openapi/OpenApiConfiguration.java`
- `Backend/src/main/java/sn/groupia/shared/infrastructure/web/ApiExceptionHandler.java`
- `Backend/src/main/resources/application-prod.properties`
- `Backend/src/main/resources/application.properties`
- `Backend/src/main/resources/db/changelog/023-auth-rate-limits.sql`
- `Backend/src/main/resources/db/changelog/db.changelog-master.yaml`
- `Backend/src/test/java/sn/groupia/CreditResolutionIntegrationTests.java`
- `Backend/src/test/java/sn/groupia/CreditResolutionMigrationTests.java`
- `Backend/src/test/java/sn/groupia/CustomerAccessIntegrationTests.java`
- `Backend/src/test/java/sn/groupia/DistributionChecksumUpgradeTests.java`
- `Backend/src/test/java/sn/groupia/DistributionIntegrationTests.java`
- `Backend/src/test/java/sn/groupia/DistributionMigrationTests.java`
- `Backend/src/test/java/sn/groupia/HardeningTestConfiguration.java`
- `Backend/src/test/java/sn/groupia/IdentityOrganizationIntegrationTests.java`
- `Backend/src/test/java/sn/groupia/LogisticsMigrationUpgradeTests.java`
- `Backend/src/test/java/sn/groupia/MerchantSettlementMigrationTests.java`
- `Backend/src/test/java/sn/groupia/OpenApiIntegrationTests.java`
- `Backend/src/test/java/sn/groupia/OrderingMigrationUpgradeTests.java`
- `Backend/src/test/java/sn/groupia/ProductionHardeningTests.java`
- `Backend/src/test/java/sn/groupia/ShippingChecksumUpgradeTests.java`
- `Backend/src/test/java/sn/groupia/ShippingFinanceIntegrationTests.java`
- `Backend/src/test/java/sn/groupia/ShippingFinanceMigrationTests.java`
- `Backend/src/test/java/sn/groupia/ShippingMigrationUpgradeTests.java`
- `Backend/src/test/java/sn/groupia/SubscriptionMigrationTests.java`
- `Backend/src/test/java/sn/groupia/TestcontainersConfiguration.java`
- `Backend/src/test/java/sn/groupia/subscription/application/service/SubscriptionIntegrationTests.java`
- `Frontend/angular.json`
- `Frontend/e2e/api-mock.ts`
- `Frontend/e2e/hardening.spec.ts`
- `Frontend/eslint.config.mjs`
- `Frontend/openapi.json`
- `Frontend/package-lock.json`
- `Frontend/package.json`
- `Frontend/src/app/api/generated/fn/auth-controller/register.ts`
- `Frontend/src/app/api/generated/models.ts`
- `Frontend/src/app/api/generated/models/user-account-view.ts`
- `Frontend/src/app/api/generated/services/auth-controller.service.ts`
- `Frontend/src/app/core/auth/auth-api.spec.ts`
- `Frontend/src/app/core/auth/auth.service.ts`
- `Frontend/src/app/core/boutique/boutique-context.service.ts`
- `Frontend/src/app/core/guards/auth.guard.spec.ts`
- `Frontend/src/app/core/theme/theme.service.ts`
- `Frontend/src/app/features/auth/auth.page.ts`
- `Frontend/src/app/features/checkout/public-checkout.spec.ts`
- `Frontend/src/app/features/dashboard/ui-showcase.page.ts`
- `Frontend/src/app/features/groupages/credit-resolution.ts`
- `Frontend/src/app/features/groupages/groupage.store.ts`
- `Frontend/src/app/features/groupages/logistics-details-editor.html`
- `Frontend/src/app/features/groupages/logistics-details-editor.ts`
- `Frontend/src/app/features/groupages/logistics-panel.html`
- `Frontend/src/app/features/groupages/product-picker.ts`
- `Frontend/src/app/features/orders/orders.spec.ts`
- `Frontend/src/app/features/settlements/merchant-settlement.store.ts`
- `Frontend/src/app/shared/ui/brand.ts`
- `Frontend/src/app/shared/ui/tabs.ts`
- `Frontend/tools/security-headers.cjs`
- `Frontend/tools/serve-production.cjs`
- `Frontend/tsconfig.json`
- `SECURITY-ROTATION.md`
- `build/generated-spring-modulith/javadoc.json`
- `performance/README.md`
- `performance/public-groupage.js`
- `tools/check_migrations.py`
- `tools/migrations-sha256.json`
- `tools/scan_secrets.py`
- `tools/test_migrations.py`
