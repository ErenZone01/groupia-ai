# Finalisation fonctionnelle — PR #86

Base récupérée : b91d1e4 (inclut 7390cce, 4467525, 528f95f, b91d1e4).
Consignes : une seule branche/PR, aucune fusion, aucun paiement ni transfert réel.
OpenAPI officiel relu le 7 novembre 2026 : SHA-256 inchangé, d4f48be4b723196500495f12b323cd5e2da18c3dad9c16e53785ff86ad60f5e6.

Étapes en cours, sans considérer les fonctionnalités ci-dessous comme terminées :

1. Abonnements : auditer les 4 commits, devis SasPay, reprise persistante, 10 000 XOF configurable, renouvellement manuel, échéance/rappels, historique et droits.
2. Encaissements de vente directe et solde transport des groupages : mêmes contrôles financiers, capability et isolation, UI et tests.
3. Remise, livraison, clôture, annulation/remboursement : compléter les transitions directes actuellement arrêtées à READY ; auditer les campagnes non abouties.
4. Registre marketplace séparé par fournisseur/compte, frais explicites et adaptateurs de reversement/remboursement simulés ; activation réelle impossible tant que les validations externes manquent.
5. Audit des autres blocages de bout en bout, puis toutes les suites locales après les derniers changements.

Les validations de la première version figurant dans README.md ne valent pas validation de ces évolutions. Résultats finaux, migrations, variables, procédure préproduction et risques à consigner après exécution.

## Lot abonnements et encaissements directs — 7 novembre 2026

Implémenté : tarif par défaut 10 000 XOF configurable, fournisseurs d'abonnement configurés, devis SasPay acceptés explicitement, contact payeur courant sans modifier les factures historiques, reprise autorisée par le backend et clés conservées dans la session du navigateur, rappels d'échéance dans l'application et renouvellement manuel. Vente directe : même devis ADD_ON, contrôle du jeton privé sous verrou, vérification du règlement, reprise par checkout ou push téléphonique. Aucune activation réelle.

Validation ciblée : 23 tests d'intégration SasPay réussis (dont abonnements/direct), suites abonnements et Modulith/OpenAPI réussies. Le lancement élargi de 75 tests avait 3 échecs de comptage des consultations des fournisseurs ; code corrigé, les 12 tests DirectCustomerPaymentStatusTests repassent tous. Non-régression DirectBictorysSandboxIntegrationTests réussie. Frontend : 26 tests abonnement et 16 tests suivi direct réussis ; 12 E2E abonnement et 2 E2E direct réussis, avec build production ; lint et diff-check réussis. Les suites complètes restent à exécuter après les prochains lots.


## Lot transport SasPay — validé le 7 novembre, enregistré le 8 novembre 2026

Solde transport : devis ADD_ON, conservation du net, session cliente et CSRF, contrôle de la révision financière, reprise autorisée par le serveur et clés persistantes côté navigateur. Une correction du transport invalide le devis précédent. Les erreurs explicites de devis avant création de tentative permettent un nouveau devis ; les erreurs réseau ambiguës conservent la même intention.

Validation : 25 tests ShippingFinanceIntegrationTests et Modulith réussis ; 57 tests frontend ciblés, puis 27 tests abonnements après ajout du cas de devis refusé ; 12 E2E transport incluant SasPay, lint et diff-check réussis. Fournisseurs HTTP simulés uniquement. Validation globale toujours à exécuter après tous les lots.


## Lot remise et clôture directes — 8 novembre 2026

Retrait, expédition, livraison et clôture après règlement canonique vérifié. Confirmation explicite, permission MANAGE_FULFILLMENT, version optimiste et intention persistante. Historique immuable isolé par organisation ; doublons et concurrence protégés. Migration additive 046, contrat OpenAPI et client régénérés. Les anciens paiements Bictorys restent utilisables.

Validation ciblée : 10 nouveaux tests unitaires ; les 9 scénarios DirectBictorysSandboxIntegrationTests validés avec reprise ciblée après correction des fixtures (contrainte reconciliation_reason et politique existante 403 interorganisation). Modulith, OpenAPI, préparation et readiness validés. Frontend : 10 tests gestion directe et 16 suivi client ; 2 nouveaux E2E retrait/livraison ; lint et diff-check réussis. Les suites globales restent à exécuter après les prochains lots.

## Lot registre fournisseurs et simulations marketplace — 8 novembre 2026

Registre immuable et rattrapage paginé des règlements, séparés par organisation/fournisseur/compte/cible ; abonnements exclus. Frais SasPay repris sur le paiement réglé, frais Bictorys inconnus affichés comme tels. Payouts existants limités au compte source exact, avec exclusion des règlements litigieux. Reversements et remboursements strictement simulés : net intégral, frais fictifs financés séparément, devis serveur, consentement, réservation, preuve persistante, reprise et historique. Aucun transfert réel SasPay activable. Voir MARKETPLACE.md pour les limites et variables.

Migrations additives 047/048. Validation ciblée finale : 45 tests MerchantSettlementIntegrationTests, 21 MarketplaceSimulationIntegrationTests et 23 SaspayIntegrationTests réussis (89 au dernier lancement) ; 49 MerchantBictorysIntegrationTests, 2 tests de configuration, 13 OpenAPI et 3 Modulith réussis au lancement précédent. Une erreur de découpage Liquibase de la fonction PL/pgSQL et une liste de nettoyage de fixtures incomplète ont été corrigées, sans modifier les assertions métier.

Frontend : 26 tests des écrans financiers, lint, build production, génération OpenAPI et 2 nouveaux E2E réussis. 22 migrations gelées conformes ; scan de secrets : 0 détection ; diff-check réussi. Les avertissements CSS de budget préexistants restent non bloquants. Les suites globales finales restent à exécuter après le lot annulations/remboursements et l'audit restant.

## Lot annulations et remboursements commerciaux — 8 novembre 2026

Implémenté : annulation payée avant remise/expédition, annulation des commandes d’une campagne non aboutie, conservation des preuves et montants, dossiers de remboursement serveur, déclaration externe explicite et idempotente, permission dédiée, reprise persistante côté navigateur et suivi client. Les frais pay-in non documentés ne sont pas assimilés au principal remboursable. Les anciennes commissions deviennent une compensation distincte bloquante, sans perte silencieuse vendeuse. Un verrou relit la version courante de la commande après acquisition pour supporter les confirmations tardives concurrentes. Le profil prod refuse une commission de commande non nulle. Migrations additives 049/050 ; voir CANCELLATIONS.md.

Audit complémentaire : dates d’admission de campagne contrôlées sous verrou, aucun seuil d’objectif implicite inventé, anciennes commandes et remboursements accessibles sans exiger un abonnement actif, messages obsolètes de vente directe corrigés. Aucun appel de transfert ou remboursement SasPay ajouté.

État au commit de ce lot : 13 tests directs Bictorys, 46 tests de clôture, 45 tests de solde vendeuse, 13 OpenAPI et 3 Modulith réussis au dernier passage ciblé. Le passage SasPay avait 25 succès sur 26 ; la course de version sur confirmation tardive a été corrigée et sa revalidation est incluse dans la suite globale en cours. De nouveaux cas couvrent également le remboursement du net vendeuse et les frontières des champs d’une intention ; ils restent à confirmer par cette validation globale.

Frontend : 538 tests Angular et lint réussis ; 2 E2E annulation réussis, également réussis dans la suite complète en cours. Une assertion E2E attendait encore la mention de vente directe future ; elle a été alignée sur le parcours disponible et doit être rejouée. 15 tests outils et 22 migrations gelées réussis. Scan secrets : 0 détection. npm audit : 0 haute/critique, 15 modérées préexistantes. Ces résultats intermédiaires ne constituent pas encore la validation globale finale, ni une CI verte ; GitHub Actions reste bloqué par facturation.
