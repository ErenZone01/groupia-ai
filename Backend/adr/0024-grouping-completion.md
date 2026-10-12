# ADR-0024 — Grouping Completion V1

Date : 12 octobre 2026. Statut : accepté.

## Sens de la transition

DISTRIBUTING → COMPLETED constate la fin des traitements, retraits et régularisations financières clientes connus au point de décision transactionnel. COMPLETED est terminal. Ni réouverture, ni expiration, annulation, réparation de commande, résolution d'incident ou mouvement financier n'est effectué par Completion.

Les obligations de reversement marchand sont indépendantes : aucun payout absent, CREATED, PENDING ou REVIEW_REQUIRED ne bloque. Ledger et payouts restent utilisables après completion. L'abonnement ne conditionne pas cette action ; les permissions normales et l'activité de la boutique restent exigées.

## Coordination modulaire

Ordering coordonne toutes les commandes, sans utiliser la projection forDistribution. Grouping expose CampaignCompletion, possède la transition, sa version, completedAt et son journal. Payment expose PaymentCompletionFacts avec uniquement des records ; ses repositories et entités restent internes. Shipping conserve ses évaluations. Aucun cycle grouping → ordering, aucune association JPA intermodule, aucun nouvel événement sans consommateur.

Une commande exécutée doit être COMPLETED/PICKED_UP avec dates cohérentes, transport final SETTLED et initial correctement alloué. EXPIRED/CANCELLED sans exécution est compatible seulement sans allocation, dette, tentative dangereuse, review ou refund restant. PENDING_PAYMENT et CONFIRMED bloquent, y compris pendant le retard d'un consommateur durable après remboursement. Une campagne vide ou dépourvue de commande exécutée/exécutable cohérente est refusée.

Toutes les obligations INITIAL_ORDER et SHIPPING_BALANCE, historiques incluses, sont examinées. OPEN, allocation incohérente, tentative CREATED/PENDING, réconciliation, succès non alloué non régularisé, review OPEN et receipt authentique/corrélable encore RECEIVED/RETRYABLE_FAILURE bloquent. Les refunds autres que SUCCEEDED ou encore incertains bloquent. Aucun crédit n'est effacé par la clôture.

## Classification financière commune

PaymentRisk est utilisé pour les états d'obligations transport et les faits financiers de Completion. ShippingFinance l'exploite également pour les obligations initiales : les contrôles de distribution/correction ne contournent pas une anomalie initiale connue.

Un succès non principal cesse de bloquer seulement si une review RESOLVED_REFUNDED correspond exactement à sa tentative, obligation, principal, provider, compte, transaction, montant et devise. Toutes les autres reviews OPEN et tous les flags reconciliationRequired restent examinés. Une résolution ne neutralise jamais PROVIDER_REVERSAL, PROVIDER_STATE_UNCERTAIN ou une transaction incohérente. La résolution ne réécrit pas le succès ; les receipts REVIEW_REQUIRED restent historiques.

## Transactions et verrous

Lecture informative en REPEATABLE_READ. POST en READ_COMMITTED : autorisation, campagne, commandes triées par UUID, toutes leurs obligations triées par createdAt/id à l'intérieur de chaque commande, refunds en ordre déterministe, relecture des faits, décision, transition et journal, commit. Les commandes sont rechargées après acquisition des verrous. Les obligations sont rafraîchies sous verrou pessimiste.

Les writers de tentatives/reviews prennent le parent PaymentRequest. Les résultats de refund prennent leur propre ligne ; Completion verrouille donc aussi les refunds. Les nouveaux refunds/obligations doivent être créés sous verrou de commande, comme les coordinateurs actuels. Aucun verrou ledger, appel provider, ni attente d'un consommateur asynchrone.

L'admission d'un nouveau receipt authentique/corrélable prend le verrou de son obligation avant insertion. L'ordre admission est coordination de déduplication → recherche de receipt existant → obligation → insertion du nouveau receipt. Le chemin d'un receipt déjà existant retourne avant le verrou parent. Le traitement conserve receipt → obligation → tentative. Completion lit les receipts sans les verrouiller : il ne crée jamais l'inversion obligation → receipt.

Un receipt commité avant acquisition du parent est visible par Completion. Une admission qui attend ce parent peut commiter après Completion et devient un fait tardif. Les événements financiers tardifs continuent leur workflow (dont nouvelles reviews) sans rouvrir la campagne. Un receipt inauthentique, non corrélable ou final historique ne bloque pas globalement les campagnes.

## Contrats et persistance

GET /api/organizations/{organizationId}/grouping-campaigns/{campaignId}/completion exige VIEW_GROUPINGS + VIEW_ORDERS + VIEW_PAYMENTS (OWNER/MANAGER/VIEWER).

POST .../complete exige MANAGE_GROUPINGS (OWNER/MANAGER), version et confirmed=true. Confirmation absente/fausse : 400 GROUPING_COMPLETION_CONFIRMATION_REQUIRED. Version périmée : 409. Le GET n'autorise jamais une mutation ; POST recalcule tout. Une double soumission produit au plus une transition et un journal.

Migration 025 uniquement : completed_at nullable pour l'histoire inconnue, nouvelle transition datée, cohérence chronologique, terminalité et immutabilité PostgreSQL ; kind COMPLETION_TRANSITION avec shape dédié, unicité et immutabilité. Aucune date historique inventée. Aucune modification des migrations 001–024.

## Limites

Le diagnostic n'est pas une garantie d'absence d'événement provider futur. Les annulations déjà payées sans workflow de régularisation explicite et les crédits dont le refund a échoué restent bloquants ; aucune réparation implicite. Les commandes CONFIRMED/PICKED_UP attendent leur consommateur de remboursement. Le verrouillage exhaustif est volontaire en V1 ; mesurer durée de transaction et cardinalité avant scale, sans paginer le prédicat ni accepter un résumé client.
