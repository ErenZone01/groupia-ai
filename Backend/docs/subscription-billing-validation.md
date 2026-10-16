# Validation — Groupia-managed Subscription Billing V1

Branche : subscription-billing-v1. Base main : e770fc6ea6c884b404e19e888f36555b2b3f85d7. Aucun merge ni activation de production.

## Architecture livrée

- Subscription détient le calendrier, les snapshots de facture et les périodes payées. Dépendance subscription → payment::api uniquement.
- PaymentTarget ORDER / SUBSCRIPTION_INVOICE est physique dans PaymentRequest. SUBSCRIPTION_MONTHLY utilise expectedAmount positif, sans ventilation Order et sans orderId.
- Migration unique 026-subscription-billing.sql : cibles historiques ORDER, fuseau immuable, invoices OPEN/PAID/EXPIRED, contraintes d'idempotence, liens et preuves des nouvelles périodes. Aucun backfill de facture ou période payée.
- Paiement standard Bictorys Charge et webhook existant ; pas d'API Billing, pas de deuxième provider. Garde-fou sandbox conservé.
- PaymentObligationSettled est générique. PaymentRequestSettled historique conserve son contrat et reste ORDER uniquement.
- Le consumer durable verrouille abonnement et facture, relit PaymentSettlementEvidence, vérifie le principal, les montants/devise, références, cible et échéance. Replay exact idempotent ; contradiction explicite.
- Double débit = review sans seconde période. Reversal = historique préservé et réconciliation, sans suspension ou refund automatique.
- OWNER gère et résout les reviews Subscription ; MANAGER lit, VIEWER ne voit aucune obligation/review Subscription (filtrage avant pagination et compteurs, détails 404), OPERATOR reste interdit aux paiements.
- Commandes, ledger marchand, transport, distribution et clôture restent isolés.

## Calendrier et UX

Un mois calendaire dans le fuseau figé, en précision microseconde. Trial actif ou période active : début à leur fin. Après interruption : début maintenant. Une seule période future prépayée. Le tarif courant ne modifie jamais une facture existante. La date limite de PaymentRequest est la fin de période ; succès après cette limite = LATE_SUCCESS sans droit automatique.

La page propose un paiement volontaire par facture, affiche attente/vérification/prochaine période réglée et permet une relecture explicite. Aucun checkout ou redirect n'accorde un droit. Les réponses périmées sont invalidées par boutique/session/génération/séquence. Un timeout garde facture et clé et relit le serveur. Anciennes routes start/cancel : 410 ; anciens flags et colonnes provider conservés mais dépréciés.

## Tests et contrôles

Résultats finaux à compléter après la fin des commandes en cours.

Les tests couvrent notamment calendrier/DST, concurrence des factures, replay des événements, succès/doublons/tardifs/reversal Bictorys, preuves incompatibles, permissions, snapshots, migration depuis 025, conservation des historiques, parité du dashboard, absence d'effets métier Order, navigation concurrente et timeout, parcours mobile et clavier.

Les fixtures de migration sont adaptées à leur schéma historique (sans target avant 026). Les comparaisons historiques excluent uniquement les nouvelles colonnes/lignes de changelog ; toutes les anciennes valeurs/checksums restent comparées. Les nouvelles colonnes sont vérifiées séparément dans SubscriptionBillingMigrationTests. Le nettoyage des fixtures attend les consumers durables avant TRUNCATE pour éviter un deadlock entre tests ; aucun consumer n'est désactivé.

## Migration et fichiers principaux

`src/main/resources/db/changelog/026-subscription-billing.sql` et son inclusion dans le master constituent toute l'évolution SQL. La facture possède ses identifiants boutique/abonnement/request, une clé de période et de création, une référence, le plan, le mois et le fuseau, le prix/devise, le contact figé, le principal réglé, les dates et la version. Les uniques protègent abonnement/période, abonnement/début, référence, request et boutique/clé. La FK composite invoice → request vérifie également la cible et la boutique. Les périodes nouvelles sont liées au principal et protégées contre les preuves contradictoires ; les anciennes conservent leurs liens null.

Fichiers de référence :

- `payment/api/PaymentTarget.java`, `PaymentTargetType.java`, `PaymentPurpose.java` et `dto/CreatePaymentRequest.java` : cible générique et invariants.
- `payment/api/event/PaymentObligationSettled.java`, `dto/PaymentSettlementEvidence.java`, `application/service/PaymentTransactions.java` : allocation et preuve serveur.
- `subscription/domain/model/SubscriptionInvoice.java`, `domain/service/SubscriptionBillingCalendar.java`, `application/service/SubscriptionInvoiceService.java` : facture, calendrier, création atomique et idempotence.
- `subscription/application/service/SubscriptionSettlementListener.java`, `SubscriptionPaymentRecorder.java`, `SubscriptionEntitlementService.java` : consommation durable, période unique et droits.
- `payment/application/service/FinancialQueries.java`, `PaymentReviewService.java` et leurs stores : visibilité et résolution selon permissions.
- `ordering/application/service/OrderSettlementListener.java`, `ShippingFinance.java`, `ordering/domain/service/GroupingCompletionPolicy.java`, `settlement/application/service/MerchantLedgerListeners.java` : isolation des obligations Order.
- `Frontend/src/app/features/subscription/` et `features/payments/` : paiement volontaire, reprise, navigation par cible et absence de réponses périmées.
- `Frontend/openapi.json` et `Frontend/src/app/api/generated/` : contrat généré depuis Spring.
- `SubscriptionBillingMigrationTests`, `SubscriptionIntegrationTests`, `BictorysIntegrationTests`, `PaymentTargetTests`, `SubscriptionCompletionIsolationTests`, tests de calendrier, store et Playwright : preuves de non-régression.
- `adr/0025-groupia-managed-subscription-billing.md` et les cinq documents métier/API mis à jour : décisions et contrat d'exploitation.

Les chemins Java ci-dessus sont relatifs à `Backend/src/main/java/sn/groupia/` ; les tests Java sont dans `Backend/src/test/java/sn/groupia/`.

## Dépendances et limites

Aucune nouvelle dépendance Maven/npm. Client Angular régénéré depuis l'OpenAPI Spring, sans DTO dupliqué à la main. Historique V1 non paginé ; à mesurer avant gros volume. Clés frontend en mémoire dans la session ; après reload, reprise depuis la facture serveur et les protections Payment. Aucun appel réel facturant le sandbox n'est exécuté par les tests automatisés : ils utilisent l'adapter réel avec un serveur HTTP de test. Credentials production et validation de déploiement restent une étape distincte.

Les contrôles Git portent sur les blobs 001–025 identiques à la base ; les fins de ligne du checkout Windows préexistantes ne sont pas réécrites. main doit rester à la base ci-dessus ; commit/push uniquement de subscription-billing-v1.
