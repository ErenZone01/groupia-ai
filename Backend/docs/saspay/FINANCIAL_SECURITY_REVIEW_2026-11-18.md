# Groupia — revue sécurité financière et contrat SasPay (18 novembre 2026)

## Portée et principe de preuve

Revue de code du dépôt Groupia après les pilotes de décaissement DIR-21 et DIR-24. Référence fournisseur : https://docs.saspay.me/llms.txt ; contrat source de vérité : https://docs.saspay.me/api-reference/openapi.json et copie versionnée `Backend/docs/saspay/openapi.json` (OpenAPI 3.1.0, API 1.0.0). Les pages d'introduction et de webhooks complètent les descriptions :
- https://docs.saspay.me/api-reference/introduction
- https://docs.saspay.me/api-reference/webhooks
- https://docs.saspay.me/api-reference/payouts/initialize
- https://docs.saspay.me/api-reference/payments/checkout-create

Ces conclusions reposent sur le code, la documentation et les tests automatisés. **Pas de pentest externe, pas de revue complète du déploiement Cloudflare/OS/DB, pas de certification PCI DSS/ISO 27001, pas de vérification de l'ensemble des opérateurs UEMOA, ni d'audit légal effectué**. Deux retraits réels réussis ne prouvent pas l'absence de failles dans les scénarios dégradés.

## Matrice de couverture des opérations réellement utiles aux vendeuses

| Capacité SasPay | Emploi actuel Groupia | État vérifié / décision |
| --- | --- | --- |
| Checkout hébergé POST + GET detail/status | Encaissement vente directe, groupage et facture d'abonnement via PaymentOperations | Fonctionnel au pilote ; POST sans Idempotency-Key dans OpenAPI : **ne pas rejouer automatiquement** une session de résultat incertain, récupération GET bornée |
| Softpay POST + verify GET | Anciennes intentions et tests simulés | Maintenir la vérification historique ; ne pas réintroduire par défaut des demandes OTP/softpay côté client |
| Confirmation OTP / retry d'un paiement échoué | Non utilisé avec checkout hébergé | **Hors parcours principal** ; ne pas appeler sans intention persistée, droits et clé d'idempotence neuve propre à la nouvelle tentative |
| Retrait POST initialize + GET verify | Versements vendeuses sur Wave, Orange Money selon configuration autorisée | Pilote DIR-21/DIR-24 réalisé ; devis serveur, réserve, clé durable, preuve GET et transaction-detail second GET |
| Pays, réseaux, mes tarifs | Tarification XOF/SN et réseau Wave/OM validés par le backend | Paramètres recontrôlés juste avant POST ; aucun pays/réseau UEMOA présumé compatible sans KYC, comptes, tarifs, essais |
| Soldes marchands et transactions GET | Préflight wallet, diagnostics historiques GET | Ne pas confondre wallet agrégé fournisseur et ledger isolé par vendeur/organisation |
| Tentatives et journal de statut GET | Investigation opérationnelle facultative | **Manque : outil de rapprochement régulier lecture seule**, cohérence de statuts et alerte des exceptions |
| Webhooks lecture/abonnements/livraisons GET | Écoute HMAC + reçus persistés ; révision par GET | Les `settlement.*` ont un payload non stabilisé ; jamais preuves de paiement seules ; après épuisement des retries SasPay, revoir manuellement le tableau de bord |
| Export CSV / facture PDF fournisseur | Aucun besoin d'initier des paiements | Outils d'audit externes ; éviter de dupliquer/archiver les données personnelles sans politique de rétention |
| Liens de paiement et CRUD | Groupia a déjà ses pages publiques et commandes | Option produit, pas un prérequis de sécurité ; ne pas ajouter un second chemin de paiement non lié à une commande |
| Wallet interwallet / change | Non exploité dans le parcours vendeur | **Ne pas activer** : opérations de transfert et responsabilités potentielles de conservation ou change à valider séparément avec le partenaire et la réglementation |
| Remboursement natif SasPay | **Aucun endpoint refund dans OpenAPI 1.0.0** | Ne pas inventer un POST refund ni promettre un remboursement automatique ; le dossier Groupia `CommercialRefundService` permet une déclaration et son audit, pas la preuve d'un débit inverse chez SasPay |

## État de sécurité vérifié dans le code

### Encaissement
- `SaspayPaymentProvider` conserve distincts session checkout et transaction payée : `PAID` et `transaction_status=SUCCESS` sont recontrôlés par `GET /payments/{id}/verify/`.
- Le GET paiement compare ID, marchand, `PAIEMENT/INBOUND`, montant, XOF, `ADD_ON`, frais, débit et net ; le navigateur/retour checkout n'est pas preuve financière.
- L'initiation sans clé d'idempotence de checkout est couverte par une intention persistée et une récupération GET à portée limitée : une réponse incertaine **doit rester bloquée ou revue**, pas un POST repris silencieusement.
- **Correctif de cette revue** : choisir automatiquement l'URL sandbox pour `sk_test_` et URL LIVE pour `sk_live_` ; refuser toute combinaison croisée, conformément aux deux environnements officiels.

### Retraits
- `SaspayMerchantPayoutProvider` recontrôle devis, destination, autorisation de l'opération, liquidité, montant et compte ; aucune réémission aveugle après timeout.
- Une réussite demande deux lectures authentifiées (verify et détail transaction) cohérentes : type `RETRAIT` ou `TRANSFERT`, `OUTBOUND`, compte, montant, frais, bénéficiaire et réseau, sans accorder de succès à un ACK initial.
- Comptabilité Groupia : réservations et fonds déjà versés isolés par organisation, historiques append-only, version, transaction et anti-rejeu.
- `CLOSE_NON_EXECUTED` et la dérogation DIR-21 sont des actions **internes Groupia** et non une attestation de SasPay ; les propriétés temporaires d'administration doivent rester désactivées après le pilote.

### Webhooks et API
- SasPay documente HMAC-SHA256 sur `timestamp + '.' + raw_body`, fenêtre ±300 s ; le code vérifie la signature en temps constant, refuse duplications de timestamp/signature et déduplique le traitement métier.
- **Correctif de cette revue** : lorsqu'un `X-Webhook-Event` est fourni, comparer strictement sa valeur à l'event dans le corps signé, rejeter les doublons insensibles à la casse. L'absence d'event header reste acceptée pour compatibilité avec les anciens tests/livraisons : contrôler en recette avant éventuel passage à l'obligation stricte.
- Les actions sensibles sont côté serveur ; le rôle et l'organisation doivent être vérifiés par endpoint et les réponses de tiers traitées comme non fiables.

## Référentiels et limites réglementaires

- **OWASP API Security Top 10 2023** : vérifier l'autorisation objet/fonction (API1/API5), la propriété d'objet (API3), l'accès aux flux sensibles (API6), les limites d'API (API4), mauvaise configuration (API8) et consommation non fiable d'API tierces (API10). Source : https://api-security.owasp.org/editions/2023/en/0x11-t10/
- **PCI DSS v4.0.1** : applicability à déterminer selon la collecte éventuelle de données cartes et architecture de paiement. Le checkout externalisé peut réduire la portée, pas supprimer automatiquement toute obligation. Examiner SAQ A / périmètre ASV avec acquéreur/QSA ; ne jamais déclarer Groupia « certifiée PCI ». Source : https://www.pcisecuritystandards.org/faqs/1604/
- Séparation des rôles, 2FA opérateur, rotations de clés, journal d'approbations, politiques de conservation PII, sauvegarde/restauration PostgreSQL et revue indépendante sont des **actions de déploiement**, pas des garanties conférées par le code seulement.
- L'activation des transferts d'un marchand vers plusieurs vendeuses, l'éventuelle détention de fonds et l'extension UEMOA nécessitent validation contractuelle SasPay et revue du cadre juridique applicable. **Ne pas confondre permission technique et habilitation juridique.**

## Risques résiduels : ordre de traitement

| Priorité | Écart / preuve manquante | Condition d'ouverture ou résultat attendu |
| --- | --- | --- |
| P0 | Pilotage LIVE de l'encaissement limité à une boutique et plafonné ; retraits à une commande ou activation générale spécifique | Définir règles d'onboarding/KYC des vendeuses, mandat contractuel, règles organisation/compte et essais avec petites sommes ; **ne pas lever globalement les flags** |
| P0 | Commingling potentiel : un compte fournisseur peut financer plusieurs organisations alors que les ledgers sont logiques | Contrats marketplace, propriétaire des fonds, rapprochement wallet vs toutes obligations, disponibilité et réserve consolidées, audit humain |
| P0 | Remboursements vendeur/client sans API SasPay refund dans le contrat | Processus de remboursement externe prouvé, double approbation et preuve de sortie, aucune libération de fonds avant justificatif |
| P1 | Rapprochement de fin de journée et webhook fournisseur épuisé | Comparer quotidiennement transactions/solde/ledger, détecter trous de webhook, doublons, retour tardif, gel des fonds litigieux et alerte |
| P1 | Pilotage de la facturation/renouvellement abonnement | Tester expiration, réouverture, paiement concurrent, renouvellement et réversibilité ; ne pas annoncer débit récurrent automatique si absent du fournisseur |
| P1 | Disponibilité checkout après échec réseau et pagination max 3 pages | Épreuve de charge, outil opérateur de recollage sûr, service dégradé ; jamais nouveau POST si résultat incertain |
| P1 | Infra/secret/accès | MFA admin + rotation secrets, restriction d'accès DB, sauvegardes chiffrées, contrôle environnements, logs sans PII, surveillance et alertes |
| P2 | Réseaux, commissions, téléphone, devis pays UEMOA | Région par région, règles fiscales/KYC et précision devise, tests contractuels réalistes sans bypass |
| P2 | Normes de sécurité et gouvernance | Tests OWASP ASVS / API Top 10 indépendants, SAST/SCA/SBOM, tests de récupération, périmètre PCI et validation si carte activée |

## Contrat OpenAPI sous test

`tools/test_saspay_openapi_contract.py` valide hors réseau les routes utilisées, statuts, présence de `Idempotency-Key` pour softpay/payout/retry et absence de ce header dans le checkout, ainsi que l'absence de route remboursement native. Ce test protège la **copie versionnée**, et **ne met pas à jour automatiquement** la documentation SasPay distante : comparer explicitement la version publiée lors des mises à niveau.

## Condition de fusion et production

CI backend, frontend, sécurité et tests OpenAPI **verts**, revue manuelle du diff et déploiement avec flags historiques DIR-21 désactivés ; pas de manipulation de fonds dans cet audit. Le fait d'avoir deux transactions réelles réussies (DIR-21 et DIR-24) valide un **parcours de pilote**, pas l'ensemble des pays, vendeuses, réseaux, abonnements et remboursements.