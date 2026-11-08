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
