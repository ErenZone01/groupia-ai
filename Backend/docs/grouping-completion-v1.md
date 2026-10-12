# Grouping Completion V1 — contrat et recette

## API privée

Base : `/api/organizations/{organizationId}/grouping-campaigns/{campaignId}`.

GET `/completion` : eligible, canComplete (permission serveur), campaignStatus, campaignVersion, completedAt, totalOrders/completedOrders/terminalUnexecutedOrders, counts par code, blockers contenant code/orderId/orderNumber. Réponse no-store sans payload, secrets ou données provider. OWNER/MANAGER/VIEWER seulement.

POST `/complete` : `{"version":12,"confirmed":true}` ; OWNER/MANAGER. Réévaluation intégrale sous verrous. 400 si confirmation absente/fausse ; 409 si version périmée, état incompatible ou blocage. Les blockers sont fournis dans le détail de l'erreur métier. Aucune somme, date ou attestation financière du navigateur n'est acceptée comme autorité.

## Recette frontend

1. Ouvrir un groupage en distribution : vérifier commandes/retraits/transport/paiements/remboursements/anomalies. Les références de commandes sont des liens privés.
2. Avec une commande prête non retirée, un crédit non remboursé, une review ouverte ou un receipt en attente, le bouton reste désactivé.
3. Résoudre réellement les opérations puis actualiser. Un duplicate remboursé ne doit plus produire un paymentInProgress artificiel ; une autre review ou reversal doit le maintenir.
4. Cliquer « Terminer définitivement le groupage ». La boîte de dialogue explique l'irréversibilité et les reversements indépendants. Annuler ne fait aucune écriture.
5. Confirmer : badge Terminé, date serveur, transport/historique accessibles en lecture seule. Les workflows financiers continuent selon leurs droits.
6. Simuler une version périmée : fermeture de la confirmation, rechargement campagne/diagnostic avant une nouvelle confirmation.
7. Répéter à 360 px et au clavier ; le dialogue natif gère focus et Échap, interdit pendant la soumission.
8. Un VIEWER lit le diagnostic sans bouton de terminaison ; un OPERATOR ne reçoit pas le diagnostic financier détaillé.

## Cas à traiter hors Completion

PENDING_PAYMENT historique : expiration/reprise par son workflow. Refund SUCCEEDED avec commande encore CONFIRMED : reprise du consommateur durable, aucune réparation depuis Completion. Refund FAILED/CANCELLED laissant un crédit : régularisation explicite à définir, jamais effacement. Annulation déjà payée non classifiable : blocage. Campagne déjà historiquement COMPLETED sans date : conserver l'inconnue.

Les reversements en attente et les abonnements expirés n'empêchent pas la fin opérationnelle. Un événement financier tardif crée ou actualise son incident financier sans changer COMPLETED.

## Concurrence et vérification

Ordre : campagne → toutes commandes → toutes obligations → refunds → relecture → transition/journal. Les receipts sont seulement lus sous le parent verrouillé, jamais verrouillés par Completion. L'admission d'un nouveau receipt corrélable prend le même parent ; celui-ci constitue la frontière avec un événement tardif. Voir ADR-0024 pour les garanties et limites.

Tests PostgreSQL : états compatibles/bloquants, absence d'effet financier, droits, double clôture, rollback journal, retraits/reviews/refunds/admissions concurrents, événements tardifs, migrations fresh/upgrade et conservation des checksums. Tests Angular/Playwright : diagnostic, confirmation, conflits, lecture seule et responsive.

Vérification : wrapper Maven test/verify (Modulith et Spotless inclus), migration guards et scan de secrets ; npm run lint, npm test, npm run build, npm run test:e2e.

## Validation finale — 12 octobre 2026

| Vérification | Résultat |
| --- | --- |
| Backend `mvnw.cmd test` | PASS — 870 tests, 0 échec, 0 erreur, 0 ignoré |
| Backend `mvnw.cmd verify` | PASS — 870 tests rejoués, packaging et Spotless PASS |
| Spring Modulith `ApplicationModules.verify()` | PASS sans exclusion |
| Frontend `npm run lint` | PASS |
| Frontend `npm test` | PASS — 286 tests dans 32 fichiers |
| Frontend `npm run build` | PASS — avertissements Angular NG8107 non bloquants |
| Frontend `npm run test:e2e` | PASS — 150 tests |
| `python tools/check_migrations.py` | PASS — baseline gelée du dépôt |
| Tests Python des outils | PASS — 4 tests |
| `python tools/scan_secrets.py` | 0 résultat |
| `git diff --check` et diff indexé | PASS |

Total : **1 310 tests**, sans recompter les tests backend rejoués par verify. Le snapshot OpenAPI frontend est identique au contrat généré par les tests backend. Les 24 blobs Git des migrations 001–024 sont identiques à ceux de la référence `1629ee441b5bd81fc778a8fde884a968e5af880c` ; les seules évolutions SQL sont 025 et son inclusion dans le master.

Nouveaux tests : 46 cas d'intégration Completion, 1 upgrade Completion, 7 cas de classification PaymentRisk, 5 tests du panneau Angular, 1 test de verrouillage en lecture seule du transport et 3 scénarios Playwright. Les fixtures historiques créant une campagne terminée respectent désormais la transition datée. Les comparaisons de checksums historiques excluent uniquement la nouvelle migration de leur périmètre ancien ; aucune assertion de conservation n'a été supprimée. La régression de formulaire logistique sur conflit reste couverte par le scénario Playwright existant.

Aucune dépendance ajoutée. La vérification PostgreSQL s'appuie sur les bases isolées des tests ; aucune réparation de données existantes n'est effectuée.

## Inventaire des fichiers livrés

Inventaire de cette tranche, avant commit. Les fichiers du client API sont générés depuis le snapshot OpenAPI vérifié.

- `Backend/adr/0024-grouping-completion.md`
- `Backend/adr/README.md`
- `Backend/docs/business-rules.md`
- `Backend/docs/grouping-completion-v1.md`
- `Backend/src/main/java/sn/groupia/grouping/api/CampaignCompletion.java`
- `Backend/src/main/java/sn/groupia/grouping/api/dto/CampaignView.java`
- `Backend/src/main/java/sn/groupia/grouping/application/service/CampaignCompletionService.java`
- `Backend/src/main/java/sn/groupia/grouping/application/service/GroupingService.java`
- `Backend/src/main/java/sn/groupia/grouping/domain/model/GroupingCampaign.java`
- `Backend/src/main/java/sn/groupia/grouping/domain/model/GroupingChange.java`
- `Backend/src/main/java/sn/groupia/ordering/api/dto/GroupingCompletionView.java`
- `Backend/src/main/java/sn/groupia/ordering/application/service/GroupingCompletion.java`
- `Backend/src/main/java/sn/groupia/ordering/application/service/OrderShipping.java`
- `Backend/src/main/java/sn/groupia/ordering/application/service/ShippingFinance.java`
- `Backend/src/main/java/sn/groupia/ordering/domain/repository/Orders.java`
- `Backend/src/main/java/sn/groupia/ordering/domain/service/GroupingCompletionPolicy.java`
- `Backend/src/main/java/sn/groupia/ordering/infrastructure/persistence/OrderPersistence.java`
- `Backend/src/main/java/sn/groupia/ordering/infrastructure/web/GroupingCompletionController.java`
- `Backend/src/main/java/sn/groupia/payment/api/PaymentCompletionFacts.java`
- `Backend/src/main/java/sn/groupia/payment/api/dto/OrderPaymentCompletionFacts.java`
- `Backend/src/main/java/sn/groupia/payment/application/service/PaymentCompletionFactsService.java`
- `Backend/src/main/java/sn/groupia/payment/application/service/PaymentTransactions.java`
- `Backend/src/main/java/sn/groupia/payment/domain/repository/FinancialStore.java`
- `Backend/src/main/java/sn/groupia/payment/domain/repository/PaymentReviewStore.java`
- `Backend/src/main/java/sn/groupia/payment/domain/service/PaymentRisk.java`
- `Backend/src/main/java/sn/groupia/payment/infrastructure/persistence/PaymentPersistence.java`
- `Backend/src/main/java/sn/groupia/payment/infrastructure/persistence/PaymentReviewPersistence.java`
- `Backend/src/main/resources/db/changelog/025-grouping-completion.sql`
- `Backend/src/main/resources/db/changelog/db.changelog-master.yaml`
- `Backend/src/test/java/sn/groupia/CampaignLogisticsIntegrationTests.java`
- `Backend/src/test/java/sn/groupia/CreditResolutionMigrationTests.java`
- `Backend/src/test/java/sn/groupia/DistributionChecksumUpgradeTests.java`
- `Backend/src/test/java/sn/groupia/DistributionMigrationTests.java`
- `Backend/src/test/java/sn/groupia/GroupingCompletionFixtures.java`
- `Backend/src/test/java/sn/groupia/GroupingCompletionIntegrationTests.java`
- `Backend/src/test/java/sn/groupia/GroupingCompletionMigrationTests.java`
- `Backend/src/test/java/sn/groupia/LogisticsMigrationUpgradeTests.java`
- `Backend/src/test/java/sn/groupia/MerchantSettlementMigrationTests.java`
- `Backend/src/test/java/sn/groupia/PaymentReviewMigrationTests.java`
- `Backend/src/test/java/sn/groupia/ShippingFinanceMigrationTests.java`
- `Backend/src/test/java/sn/groupia/ShippingMigrationUpgradeTests.java`
- `Backend/src/test/java/sn/groupia/SubscriptionMigrationTests.java`
- `Backend/src/test/java/sn/groupia/SupplierOrderIntegrationTests.java`
- `Backend/src/test/java/sn/groupia/payment/domain/service/PaymentRiskTests.java`
- `Frontend/e2e/api-mock.ts`
- `Frontend/e2e/grouping-completion.spec.ts`
- `Frontend/openapi.json`
- `Frontend/src/app/api/generated/fn/grouping-completion-controller/complete-grouping.ts`
- `Frontend/src/app/api/generated/fn/grouping-completion-controller/grouping-completion.ts`
- `Frontend/src/app/api/generated/fn/payment-review-controller/get-payment-review.ts`
- `Frontend/src/app/api/generated/fn/payment-review-controller/list-payment-reviews.ts`
- `Frontend/src/app/api/generated/fn/payment-review-controller/resolve-payment-review-refunded.ts`
- `Frontend/src/app/api/generated/functions.ts`
- `Frontend/src/app/api/generated/models.ts`
- `Frontend/src/app/api/generated/models/blocker.ts`
- `Frontend/src/app/api/generated/models/campaign-view.ts`
- `Frontend/src/app/api/generated/models/confirm-completion.ts`
- `Frontend/src/app/api/generated/models/grouping-completion-view.ts`
- `Frontend/src/app/api/generated/models/payment-review-page.ts`
- `Frontend/src/app/api/generated/models/payment-review-view.ts`
- `Frontend/src/app/api/generated/models/resolve-payment-review-refunded.ts`
- `Frontend/src/app/api/generated/services.ts`
- `Frontend/src/app/api/generated/services/grouping-completion-controller.service.ts`
- `Frontend/src/app/api/generated/services/payment-review-controller.service.ts`
- `Frontend/src/app/features/groupages/completion-panel.html`
- `Frontend/src/app/features/groupages/completion-panel.spec.ts`
- `Frontend/src/app/features/groupages/completion-panel.ts`
- `Frontend/src/app/features/groupages/groupage-detail.page.html`
- `Frontend/src/app/features/groupages/groupage-detail.page.ts`
- `Frontend/src/app/features/groupages/logistics-panel.html`
- `Frontend/src/app/features/groupages/logistics-panel.ts`
- `Frontend/src/app/features/groupages/shipping-batch.ts`
- `Frontend/src/app/features/groupages/shipping-order-editor.html`
- `Frontend/src/app/features/groupages/shipping-order-editor.ts`
- `Frontend/src/app/features/groupages/shipping.spec.ts`
