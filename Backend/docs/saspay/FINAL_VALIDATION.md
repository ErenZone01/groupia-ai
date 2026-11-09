# Bilan de finalisation — PR #86

Bilan finalisé le 9 novembre 2026, après lecture des suites terminées le 8 novembre : **validation locale complète réussie** sur le code `1d1c929`. Elle ne vaut ni validation réelle de SasPay, ni CI verte. Branche `saspay-payin-v1`, PR conservée en brouillon, aucune fusion ni mise en production.

## Fonctionnalités implémentées

- Encaissements SasPay pour commandes de groupage, solde transport, vente directe et factures d’abonnement. Fournisseur configurable, Bictorys conservé. Devis serveur persistants, ADD_ON, prix/frais/total/net visibles avant consentement, aucune commission Groupia sur les nouvelles commandes.
- Vérification canonique serveur avant succès : fournisseur, compte marchand, transaction, cible/finalité, montants, devise et net. Webhooks HMAC sur corps brut, âge contrôlé, reçus dédupliqués, transitions monotones et événements Modulith durables. Reprises réseau avec la même intention ; les incertitudes sans référence exploitable restent en revue.
- Abonnements : 10 000 XOF mensuels configurables, essai, échéances et rappels dans l’application, renouvellement manuel, factures/historique, activation et réactivation sur preuve de règlement. L’expiration limite les nouvelles opérations concernées, sans retirer l’accès aux anciennes commandes ou aux obligations financières. Aucun prélèvement récurrent SasPay supposé.
- Vente directe : préparation, retrait ou expédition/livraison, clôture, confirmation explicite et historique immuable. Groupage : création, admission selon dates/état, paiement, transport, distribution et clôture. Les achats utilisent des accès clients privés sans création obligatoire de compte.
- Annulations avant exécution et campagnes non abouties : dossiers de remboursement au montant serveur, paiements d’origine conservés, déclaration externe autorisée et idempotente, restitution suivie et rattrapage comptable. Une déclaration externe n’est pas une preuve de remboursement fournisseur. Les anciennes commissions nécessitent une compensation explicite avant libération financière.
- Registre des encaissements séparé par organisation/fournisseur/compte, rattrapage et historique. Simulations durables de reversement et remboursement : net intégral, budget de frais distinct, réservation, concurrence, reprise et preuve simulée. Aucun payout SasPay réel enregistrable.
- Corrections ciblées de parcours : messages obsolètes de vente directe, reprise après perte de réponse, changements de boutique/session, liens privés des commandes remboursées. Aucune refonte graphique.

Les décisions détaillées et limites sont dans [README](README.md), [MARKETPLACE](MARKETPLACE.md) et [CANCELLATIONS](CANCELLATIONS.md). Aucun objectif minimum de campagne absent du modèle n’a été inventé.

## Contrat fournisseur et blocages externes

La copie officielle OpenAPI v1.0.0 est conservée avec SHA-256 `d4f48be4b723196500495f12b323cd5e2da18c3dad9c16e53785ff86ad60f5e6`. La comparaison du 7 novembre était inchangée ; les tentatives de lecture du 8 novembre n’ont pas abouti. Aucun nouveau contrat n’est supposé.

1. Obtenir une garantie contractuelle de devis ou de débit maximal, et une règle d’arrondi XOF. Une dernière lecture des tarifs ne verrouille pas un changement ultérieur ; détecter un écart à la vérification ne permet pas d’annuler un débit. Les clés live restent refusées et les frais fractionnaires ambigus sont rejetés.
2. Faire autoriser le modèle marketplace, les comptes destinataires, le financement des frais et le net réellement reçu. Le net marchand pay-in n’est pas une preuve de versement vendeuse. Aucun split, compte connecté ou remboursement API non documenté n’est ajouté.
3. Valider KYC, scopes, IP de payout, domaines exacts de checkout, secrets et réception réelle du webhook en environnement fournisseur autorisé. Les tests automatiques restent entièrement fictifs.
4. Pour une ancienne commande à commission, la compensation due par Groupia exige un processus financier approuvé ; elle n’est ni effacée ni prélevée silencieusement sur la vendeuse.
5. Régler la facturation/plafond GitHub Actions puis obtenir toutes les vérifications CI vertes avant fusion. L’annotation fournie par l’utilisateur explique le non-démarrage du job security ; aucun contournement ni relancement continu.

## Validation locale

PostgreSQL 18.6 sous Testcontainers, Java 21, fournisseurs HTTP loopback et adaptateurs simulés, clés fictives ; aucun paiement, payout ou remboursement externe exécuté.

| Contrôle | Résultat effectif |
|---|---|
| Maven `verify` complet, code `1d1c929` | **1 385 tests, 94 suites, 0 échec, 0 erreur, 0 ignoré ; BUILD SUCCESS**. PostgreSQL, Liquibase, Modulith, sécurité, contrats, concurrence et formatage inclus. Terminé le 8 novembre à 20:22:25 UTC, durée 28 min 51 s |
| Régression métier finale | 74 réussis : 49 settlement, 22 accès client, 3 Modulith ; aucun échec/erreur/ignoré |
| Régression des migrations corrigées | 16 réussis dans 9 suites ; aucun échec/erreur/ignoré |
| Angular `npm test` | 538 réussis dans 51 fichiers |
| Angular lint et build production | Réussis ; avertissements CSS/budget existants conservés |
| Playwright complet | 391 réussis, aucun échec ; le lancement reconstruit aussi le bundle production |
| Tests outils Python | 15 réussis |
| Migrations gelées | 22 conformes au baseline immuable |
| Scan de secrets | 0 détection, valeurs non affichées |
| actionlint épinglé / diff-check | Réussis |
| OpenAPI exporté / snapshot frontend | Égalité JSON, 127 routes |
| npm audit complet | 0 haute/critique, 15 modérées dans la chaîne de lint de développement |
| npm audit `--omit=dev` | 0 vulnérabilité signalée |
| Maven dependency:tree / dependency:analyze-only | Réussis avec avertissements de déclaration/framework ; aucun changement de dépendance |

Les rapports locaux se trouvent sous `Backend/target` et les journaux frontend `Frontend/final-tests2.log`, `Frontend/final-e2e2.log` ; ils sont ignorés par Git. Le contrôle Maven des dépendances est une analyse d’inventaire et d’utilisation, pas un scan exhaustif des CVE Java. Les corrections npm proposées incluent des changements majeurs/rétrogradations de l’outillage : elles n’ont pas été appliquées automatiquement dans ce lot financier. Les 15 alertes restent un travail de maintenance identifié.

La validation de migrations précédente avait encore un échec dans le cas de schéma inconnu volontairement refusé : la fixture exigeait une colonne qui ne devait pas être créée après ce refus. Le commit `1d1c929` vérifie son absence et l’égalité de toutes les anciennes valeurs. Les 16 cas concernés ont été rejoués avec succès avant le nouveau verify complet.

Les premiers passages complets ont révélé des fixtures de migration comparant l’ancien schéma sans retirer la nouvelle colonne nullable, un horodatage de révocation de fixture et une assertion E2E annonçant encore la vente directe future. Les fixtures vérifient désormais explicitement la nouvelle colonne nulle et l’égalité de toutes les anciennes valeurs. Aucune migration gelée ni assertion financière n’a été affaiblie. Une course sur le rechargement d’une commande après verrou a également été corrigée.

Le processus de tests a dépassé le délai de fermeture Surefire de 30 secondes après `System.exit(0)` ; Surefire a arrêté son fork. Les 1 385 tests étaient terminés, les rapports XML sont tous sans échec/erreur/ignoré et Maven a ensuite produit le JAR et passé Spotless avec BUILD SUCCESS. Ce message de fermeture n’est pas masqué ; les temps d’arrêt de nombreux contextes de test restent à surveiller.

Les E2E frontend interceptent les réponses API : ils valident le navigateur et les contrats attendus, pas une communication avec le vrai fournisseur. Les tests d’intégration backend couvrent séparément les transactions, événements et appels HTTP simulés.

## Migrations ajoutées

| Migration | Objet |
|---|---|
| 045 | Devis de frais serveur, liaisons aux tentatives |
| 046 | Historique immuable de remise et clôture directe |
| 047 | Registre des encaissements par fournisseur et compte |
| 048 | Intentions, réservations, reçus et événements marketplace simulés |
| 049 | Dossiers, sources et événements de remboursement commercial |
| 050 | États de remboursement, liaison des commandes au dossier, acteur d’annulation de campagne |

Les migrations 001–022 gelées restent inchangées. Les montants historiques ne sont pas recalculés. Répéter la montée de version sur une copie anonymisée préproduction, vérifier les comptes par fournisseur et les dossiers historiques avant toute activation.

## Configuration nécessaire

Secrets via gestionnaire de secrets ou environnement, jamais dans Git, le navigateur ou les logs.

| Variables | Usage / défaut |
|---|---|
| `SASPAY_ENABLED` | false ; configuration de test hors profil prod |
| `SASPAY_API_KEY`, `SASPAY_WEBHOOK_SECRET` | Secrets serveur ; clé test uniquement admise actuellement |
| `SASPAY_MERCHANT_ID`, `SASPAY_CHECKOUT_HOSTS` | Marchand et domaines exacts validés ; aucun wildcard |
| `GROUPIA_PUBLIC_BASE_URL` | URL publique pour les liens clients |
| `GROUPIA_DIRECT_CHECKOUT_ENABLED` | false ; création directe |
| `GROUPIA_DIRECT_PAYMENT_AUTO_PREPARE_ENABLED`, `GROUPIA_DIRECT_PAYMENT_INITIATION_ENABLED` | false ; préparation de l’obligation et initiation directe |
| `GROUPIA_SUBSCRIPTIONS_ENABLED` | true |
| `GROUPIA_SUBSCRIPTION_ENFORCEMENT_ENABLED` | false ; activer seulement après validation du cycle et des droits |
| `GROUPIA_SUBSCRIPTION_TRIAL_MONTHS` | 1 |
| `GROUPIA_SUBSCRIPTION_MONTHLY_XOF` | 10000, tarif serveur des nouvelles échéances |
| `GROUPIA_MARKETPLACE_SIMULATION_ENABLED` | false ; aucune opération réelle même si true |
| `GROUPIA_MARKETPLACE_SIMULATION_FEE_XOF` | 200 fictifs, sans lien avec un tarif fournisseur |
| `GROUPIA_MARKETPLACE_SIMULATION_COMPANY_BUDGET_XOF` | 100000 fictifs par boutique |

Les configurations Bictorys existantes sont conservées. `groupia.ordering.platform-fee-xof` reste à zéro ; le profil prod refuse une valeur non nulle. Il n’existe aucun flag permettant d’activer un payout réel SasPay.

## Validation manuelle préproduction et activation

1. Déployer une copie isolée avec base anonymisée et sauvegarde vérifiée. Pour la base locale, `docker compose -f Backend/compose.yaml up -d postgres` après configuration locale du mot de passe ; les tests utilisent leur propre PostgreSQL Testcontainers. Ne pas raccorder de clés live.
2. Appliquer Liquibase et comparer les anciens montants/états. Contrôler les événements en échec et les rapprochements financiers par organisation, fournisseur et compte.
3. Tester avec deux boutiques et plusieurs rôles : devis, acceptation des frais, réponse perdue, reprise identique, échec, règlement confirmé et refus d’accès croisé. Vérifier séparément groupage, solde transport, direct et abonnement. Une redirection seule ne doit rien confirmer.
4. Vérifier préparation, retrait/livraison, clôture, annulation, déclaration externe et historique. Contrôler les blocages après remise, l’absence de nouveau paiement après annulation et la conservation du suivi privé.
5. Vérifier essai, échéance, paiement manuel, expiration et réactivation ; les anciennes commandes et remboursements restent accessibles. Les simulations de reversement conservent le net et financent leurs frais séparément.
6. Avec autorisation opérateur et compte fournisseur test, configurer les secrets, lancer `python tools/check_saspay_config.py` (contrôle hors ligne), créer le webhook `/api/payment/webhooks/saspay` dans le tableau de bord, vérifier l’horloge et les domaines de redirection. Les essais fournisseur restent à réaliser manuellement, hors tests automatiques.
7. Avant toute activation réelle : confirmations écrites des garanties de prix/net, permissions marketplace, budget et porteur des frais, preuves bénéficiaire, supervision/reprise et procédure d’incident. Adapter puis tester le code sous nouvelle revue si le contrat nécessite d’autres champs. Ne pas supprimer simplement les verrous live.
8. Une fois la facturation GitHub réglée, exécuter la CI et attendre tous les contrôles verts avant une décision de fusion. Aucun déploiement ni mouvement d’argent n’est effectué par ce travail.

## Commits et PR

Les quatre commits utilisateurs `7390cce`, `4467525`, `528f95f`, `b91d1e4` ont été repris et audités. Lots ajoutés sur la même branche :

- `6b23c00` : abonnements et encaissement direct SasPay.
- `973a0ed` : solde transport SasPay.
- `5f36b40` : remise et clôture directes vérifiées.
- `3cd45f4` : séparation des fonds et simulations marketplace.
- `d02f25e` : annulations payées et remboursements externes.
- `4471fe3` : rattrapage comptable, suivi privé et conservation historique.
- `1d1c929` : vérification du schéma inchangé après migration inconnue refusée.

Résultat global backend : réussi ; les corrections sont enregistrées et poussées. La finalisation documentaire ne modifie pas le code testé. [PR #86](https://github.com/ErenZone01/groupia-ai/pull/86), non fusionnée ; aucune affirmation de CI verte.
