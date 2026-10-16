# Payments Dashboard V1

## Périmètre

Dashboard privé `/app/paiements`, détails `/app/paiements/requests/:requestId` et `/app/paiements/reviews/:reviewId`. Consultation des obligations et de toutes leurs tentatives ; enregistrement d’un remboursement externe déjà effectué via le workflow PaymentReview existant. Aucun nouveau règlement, remboursement provider, payout, ledger ou pouvoir de modification financière. Les commandes restent accessibles par leur UUID, sans dépendance `payment -> ordering`.

## Read-model et classification

`FinancialQueries` capture une seule fois `clock.instant()`, normalisé à la microseconde PostgreSQL, pour toute la réponse. Ses lectures sont `readOnly / REPEATABLE_READ` : IDs paginés, compteurs et faits de la page utilisent le même snapshot. Aucun GET n’expire, n’annule ou ne règle une obligation.

`PaymentRisk` reste l’autorité des anomalies. `PaymentDashboardPolicy` interprète ses raisons sans reproduire la corrélation financière. Priorité :

1. `REVIEW_REQUIRED` : tout risque autre que `PAYMENT_IN_PROGRESS`, montant nul OPEN, tentative en cours sur obligation non payable. Les reviews OPEN, reversals, incertitudes, incohérences de transaction/allocation et succès supplémentaires non régularisés ne sont jamais masqués.
2. `SETTLED` : CLOSED, principal présent et cohérent d’après PaymentRisk, aucun risque restant. Un doublon précisément couvert par une review RESOLVED_REFUNDED ne bloque plus ; une autre review ou une reconciliation reste bloquante.
3. `PENDING` : OPEN, montant positif, sans règlement, délai non dépassé ou absent, sans anomalie. Une tentative CREATED/PENDING normale peut conserver `PAYMENT_IN_PROGRESS` dans les raisons tout en appartenant à cette catégorie. Une tentative FAILED n’empêche pas un nouveau paiement si l’obligation reste payable.
4. `TERMINAL_UNPAID` : sans règlement ni risque, CANCELLED / SUPERSEDED, ou OPEN dont `expiresAt <= evaluationAt`. `terminalReason` vaut respectivement CANCELLED, SUPERSEDED ou DEADLINE_EXCEEDED.
5. Combinaison non reconnue : REVIEW_REQUIRED / PAYMENT_CLASSIFICATION_UNCERTAIN.

`PaymentDashboardPersistence` utilise une CTE et des agrégats latéraux/EXISTS pour refléter cette classification avant LIMIT/OFFSET. Les reviews ne sont jamais jointes aux tentatives de manière à multiplier les lignes. Les IDs de la page sont hydratés en trois lectures groupées (obligations, tentatives, reviews) ; on ne charge pas toutes les obligations d’une boutique en Java. Ordre stable `created_at DESC, id ASC`.

La policy Java est la référence. Les 40 scénarios PostgreSQL de `PaymentDashboardParityTests` comparent chaque catégorie SQL et la policy, ainsi que les résultats de chaque filtre. Les combinaisons corrompues sont exclusivement produites dans des tables temporaires transactionnelles, copies vides des tables de test : aucune contrainte de production n’est désactivée. Le service vérifie aussi la parité pour chaque ligne retournée et échoue au lieu d’afficher une catégorie contradictoire.

## Contrats API

- GET `/api/organizations/{organizationId}/payment-requests`, operationId `listPaymentRequests`.
- GET `/api/organizations/{organizationId}/payment-requests/{id}`, operationId `getPaymentRequest`.
- Filtres : `operationalStatus`, `purpose`, `orderId`, `page` (0 à 1 000 000), `size` (1 à 100, défaut 20). Aucun filtre = Tous. Filtrage serveur avant pagination.
- `PaymentRequestsPageView` : items, page, size, totalElements, totalPages, summary, evaluationAt.
- `PaymentRequestSummaryView` : anciens champs de lecture préservés, plus état/raisons opérationnels, terminalReason, openReviewCount, attemptInProgress, reconciliationRequired.
- `PaymentRequestDetailView` : request, attempts, evaluationAt ; `PaymentAttemptReadView` dédié à la lecture privée, sans checkoutUrl ni clés d’idempotence.
- `PaymentDashboardSummaryView` : pending, settled, reviewRequired, terminalUnpaid, openReviews.

Les quatre compteurs d’obligations sont filtrés par boutique + purpose + orderId, indépendants de la catégorie et de la page sélectionnées. `totalElements` tient compte de tous les filtres, y compris la catégorie. `openReviews` compte les incidents OPEN de toute la boutique, indépendamment des filtres d’obligations : deux incidents sur une obligation donnent 2 incidents et 1 obligation à vérifier. Le panneau Anomalies du détail est, lui, filtré par paymentRequestId.

Les opérations Commandes conservent explicitement leurs anciens operationIds `list_4` et `get_5` pour éviter le décalage des noms automatiques induit par les nouveaux noms Paiements. Aucun comportement Commandes n’est modifié. Le snapshot OpenAPI et le client sont régénérés avec `tools/generate-api.mjs --snapshot`, jamais modifiés manuellement.

## Autorisation et données privées

Les deux GET sont `Cache-Control: no-store` et exigent VIEW_PAYMENTS via OrganizationAccess : OWNER/MANAGER/VIEWER autorisés, OPERATOR refusé. L’authentification, l’appartenance active, l’état de la boutique et la permission sont vérifiés côté serveur. Un identifiant étranger ne donne pas accès au détail ; un filtre orderId/paymentRequestId étranger renvoie une liste vide dans la boutique autorisée.

PaymentReview conserve les trois routes existantes. Son GET de liste accepte désormais `paymentRequestId`. `PaymentReviewService` calcule MANAGE_REFUNDS une fois par liste, puis `canResolve = permission && status == OPEN`. Les entités n’évaluent aucune permission ; leur mapping brut est sans capacité et seul le service enrichit la réponse. Une capacité absente est refusée côté Angular. Le POST conserve son autorisation, ses verrous, sa normalisation, son idempotence et son rejet des preuves contradictoires.

Aucune clé provider, signature, token, credential, checkoutUrl ou payload webhook n’est exposé par les nouveaux DTO de lecture.

## Interface et résolution

Listes paginées indépendantes, tableau desktop et cartes à 360 px, variables sémantiques light/dark, boutons accessibles et focus visible. Les libellés français traduisent les codes sans recalculer les catégories financières. Les montants réutilisent le formatteur sûr du projet : un nombre non représentable exactement en JavaScript n’est pas présenté comme exact.

Le dialogue natif conserve le focus clavier entre ses contrôles avec Tab/Shift+Tab, se ferme avec Échap et rétablit le focus après résolution. Il présente le prestataire, la transaction anormale de la review (jamais substituée par celle du principal), le montant/devise et la date de détection. Il indique : « Cette action n'effectue aucun remboursement. Elle enregistre dans Groupia qu'un remboursement externe a déjà été effectué. » Le body contient uniquement externalRefundReference (1–160 caractères) et resolutionNote facultative (maximum 1 000).

Après une réponse perdue/timeout, la preuve soumise reste en mémoire, liée à boutique + review + session. Relecture serveur :

- RESOLVED_REFUNDED et même preuve normalisée : enregistrement terminé ;
- OPEN : bouton « Réessayer l’enregistrement », renvoyant exactement le même body ;
- résolution avec autre preuve : conflit explicite, affichage des données serveur ;
- relecture impossible : état incertain, vérifier avant tout nouvel enregistrement.

Aucun réessai automatique du POST et aucune invitation à refaire le remboursement externe. Après résolution, les compteurs, l’obligation et les reviews sont relus. Les preuves ne vont ni dans localStorage ni dans sessionStorage.

Au changement de boutique/session/navigation, le store annule les requêtes, incrémente sa génération et efface données/capacités/erreurs/preuve. Le modèle visible est immédiatement masqué si le contexte courant diffère, avant même l’effet de nettoyage. Chaque callback GET/POST vérifie aussi la génération, la session et la boutique courantes. Changer la pagination d’une section ne recharge pas l’autre.

## Vérification et limites

Tests backend : parité systématique, filtrage avant pagination, bornes exactes d’échéance, tri stable, métriques distinctes, permissions, isolement, no-store, absence de champs sensibles, capacités et régressions de résolution. Tests Angular : chargement/vide/erreur, filtres et pagination, GET/POST tardifs, session/boutique, capacités absentes, dialogue, preuve, timeout/relecture/conflit. Playwright : parcours desktop/mobile, thèmes, clavier, navigation Commandes, OWNER et VIEWER, réponses perdues.

Aucune migration et aucune dépendance supplémentaire. Migrations 001–025 protégées et comparées au commit de départ. Les agrégats lisent les faits de la boutique filtrée ; leur coût devra être mesuré avec la volumétrie réelle avant d’envisager une projection matérialisée ou de nouveaux index. Aucun index ajouté sans mesure et validation. Le détail liste toutes les tentatives comme demandé pour V1 ; les obligations sont paginées. Les tests navigateur utilisent une API simulée ; les tests Spring utilisent PostgreSQL et le vrai contrat HTTP applicatif.

Validation finale du 14 octobre 2026 :

- Backend : `mvnw.cmd test` et `mvnw.cmd verify` terminés avec succès le 13 octobre, 921 tests chacun, aucun échec ni test ignoré. Aucun code backend modifié depuis ces exécutions.
- `ApplicationModules.verify()` et les trois tests de modularité : PASS ; Spotless : PASS.
- Frontend : lint PASS, 318 tests unitaires PASS, build production PASS, 165 tests Playwright PASS (dont 15 pour ce dashboard).
- Les quatre variantes clavier du dialogue (360/1280 px, clair/sombre) passent ; captures desktop/mobile inspectées. Les seuls avertissements Angular restants sont les NG8107 préexistants de `shipping-order-editor`.
- Protection des migrations : PASS ; quatre tests Python PASS ; les 25 blobs Git des migrations 001–025 sont identiques au commit de départ, sans nouvelle migration.
- Scan de secrets : zéro résultat après démarrage de Docker ; `git diff --check` : PASS.
- La régénération OpenAPI finale ajoute uniquement la nullabilité attendue de `terminalReason` au contrat ; les autres différences du snapshot et de `HttpHeaders` sont des réordonnancements sans changement sémantique.

Le SHA de livraison est fourni dans le compte rendu final, après commit et push sur `payments-dashboard-v1` uniquement.

## Inventaire des fichiers

60 chemins ajoutés, modifiés ou remplacés (y compris les anciens DTO/fonctions générés supprimés). Les fichiers generated sont issus du workflow OpenAPI.

```text
Backend/docs/payments-dashboard-v1.md
Backend/src/main/java/sn/groupia/ordering/infrastructure/web/OrderingController.java
Backend/src/main/java/sn/groupia/payment/api/PaymentOperationalStatus.java
Backend/src/main/java/sn/groupia/payment/api/dto/PaymentAttemptReadView.java
Backend/src/main/java/sn/groupia/payment/api/dto/PaymentDashboardSummaryView.java
Backend/src/main/java/sn/groupia/payment/api/dto/PaymentRequestDetailView.java
Backend/src/main/java/sn/groupia/payment/api/dto/PaymentRequestSummaryView.java
Backend/src/main/java/sn/groupia/payment/api/dto/PaymentRequestsPageView.java
Backend/src/main/java/sn/groupia/payment/api/dto/PaymentReviewView.java
Backend/src/main/java/sn/groupia/payment/application/service/FinancialQueries.java
Backend/src/main/java/sn/groupia/payment/application/service/PaymentReviewService.java
Backend/src/main/java/sn/groupia/payment/domain/model/PaymentReview.java
Backend/src/main/java/sn/groupia/payment/domain/repository/PaymentDashboardStore.java
Backend/src/main/java/sn/groupia/payment/domain/repository/PaymentReviewStore.java
Backend/src/main/java/sn/groupia/payment/domain/service/PaymentDashboardPolicy.java
Backend/src/main/java/sn/groupia/payment/infrastructure/persistence/PaymentDashboardPersistence.java
Backend/src/main/java/sn/groupia/payment/infrastructure/persistence/PaymentDashboardSql.java
Backend/src/main/java/sn/groupia/payment/infrastructure/persistence/PaymentReviewPersistence.java
Backend/src/main/java/sn/groupia/payment/infrastructure/web/PaymentController.java
Backend/src/main/java/sn/groupia/payment/infrastructure/web/PaymentReviewController.java
Backend/src/test/java/sn/groupia/OpenApiIntegrationTests.java
Backend/src/test/java/sn/groupia/PaymentDashboardParityTests.java
Backend/src/test/java/sn/groupia/PaymentReviewIntegrationTests.java
Frontend/e2e/payments-dashboard.spec.ts
Frontend/openapi.json
Frontend/src/app/api/generated/fn/payment-controller/get-4.ts
Frontend/src/app/api/generated/fn/payment-controller/get-payment-request.ts
Frontend/src/app/api/generated/fn/payment-controller/list-3.ts
Frontend/src/app/api/generated/fn/payment-controller/list-payment-requests.ts
Frontend/src/app/api/generated/fn/payment-review-controller/list-payment-reviews.ts
Frontend/src/app/api/generated/functions.ts
Frontend/src/app/api/generated/models.ts
Frontend/src/app/api/generated/models/attempt.ts
Frontend/src/app/api/generated/models/detail.ts
Frontend/src/app/api/generated/models/http-headers.ts
Frontend/src/app/api/generated/models/page.ts
Frontend/src/app/api/generated/models/payment-attempt-read-view.ts
Frontend/src/app/api/generated/models/payment-dashboard-summary-view.ts
Frontend/src/app/api/generated/models/payment-request-detail-view.ts
Frontend/src/app/api/generated/models/payment-request-summary-view.ts
Frontend/src/app/api/generated/models/payment-request-view.ts
Frontend/src/app/api/generated/models/payment-requests-page-view.ts
Frontend/src/app/api/generated/models/payment-review-view.ts
Frontend/src/app/api/generated/services/payment-controller.service.ts
Frontend/src/app/app.routes.ts
Frontend/src/app/features/payments/payment-display.ts
Frontend/src/app/features/payments/payment-pagination.ts
Frontend/src/app/features/payments/payment-request-list.ts
Frontend/src/app/features/payments/payment-request.page.html
Frontend/src/app/features/payments/payment-request.page.ts
Frontend/src/app/features/payments/payment-review.page.html
Frontend/src/app/features/payments/payment-review.page.ts
Frontend/src/app/features/payments/payment-reviews-panel.ts
Frontend/src/app/features/payments/payments.page.html
Frontend/src/app/features/payments/payments.page.ts
Frontend/src/app/features/payments/payments.pages.spec.ts
Frontend/src/app/features/payments/payments.scss
Frontend/src/app/features/payments/payments.store.spec.ts
Frontend/src/app/features/payments/payments.store.ts
Frontend/src/app/testing/payment-fixtures.ts
```

## Obligations d'abonnement (026)

PaymentTarget expose ORDER ou SUBSCRIPTION_INVOICE et son UUID. SUBSCRIPTION_MONTHLY a orderId null, expectedAmount positif, les trois ventilations à zéro et expiresAt = invoice.periodEnd. Les DTO request/review exposent target ; l'UI affiche Abonnement et dirige vers /app/abonnement sans construire de lien commande nul.

VIEW_PAYMENTS ne suffit pas : sans VIEW_SUBSCRIPTION, les obligations et reviews d'abonnement sont exclues AVANT pagination, agrégations et compteurs. Les détails directs sont 404. OWNER/MANAGER lisent ; OWNER seul résout une review d'abonnement avec les deux permissions MANAGE_REFUNDS et MANAGE_SUBSCRIPTION. Les classifications PENDING/SETTLED/REVIEW_REQUIRED/TERMINAL_UNPAID restent identiques.

PaymentObligationSettled est publié au premier règlement de toute cible. PaymentRequestSettled historique reste inchangé et réservé à ORDER. PaymentSettlementEvidence n'expose ni PII, ni checkoutUrl, ni secret et fournit les preuves du principal. Aucun effet Order/Shipping/Distribution/Completion/MerchantLedger ne traite SUBSCRIPTION_MONTHLY.
