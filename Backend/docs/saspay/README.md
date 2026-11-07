# SasPay — audit et lot 1 encaissement de test

Audit du 7 novembre 2026, base Groupia `8f99fb8`.

## Sources et contrats

- [Index officiel](https://docs.saspay.me/llms.txt)
- [OpenAPI officiel](https://docs.saspay.me/api-reference/openapi.json), copie `openapi.json`, version 1.0.0, SHA-256 `d4f48be4b723196500495f12b323cd5e2da18c3dad9c16e53785ff86ad60f5e6`.
- [Skill officiel](https://docs.saspay.me/ai/skill) : le fichier local était vide ; son contenu est récupéré depuis le téléchargement officiel lié sur cette page.
- [Webhooks](https://docs.saspay.me/api-reference/webhooks), [tarifs](https://docs.saspay.me/api-reference/reference/my-rates), [enveloppes](https://docs.saspay.me/api-reference/introduction).

L'OpenAPI fait foi sur les routes : `POST /payments/softpay/`, `GET /payments/{payment_id}/verify/`, `GET /pricing/my-rates/`. Les exemples omettent parfois l'enveloppe `success/data` documentée : le client accepte les deux formes, refuse les erreurs et ne réessaie aucune mutation automatiquement. Aucune route de retry, checkout session, payout ou transfert wallet n'est appelée.

## Résultat de l'audit existant

Le module Payment contient déjà des obligations avec cibles et finalités explicites, un verrou de réservation des tentatives, des clés fournisseur persistantes, des reçus webhook dédupliqués en base, une réconciliation et des événements Modulith persistants. Ces mécanismes sont réutilisés. Bictorys conserve son authentification et ses flux existants.

Les ventes directes sont expressément verrouillées sur Bictorys et protégées par une capability ; ce lot ne change pas ce verrou. Les abonnements ont une cible `SUBSCRIPTION_INVOICE` et une finalité `SUBSCRIPTION_MONTHLY`, distinctes des commandes. Aucun succès de commande ne doit activer un abonnement. Le raccordement SasPay de ces deux parcours fera l'objet de lots séparés.

Le défaut historique de frais plateforme était **300 XOF**, incompatible avec la nouvelle politique sans commission. Ce lot fixe la configuration livrée à **0** (ancienne variable `GROUPIA_PLATFORM_FEE_XOF` supprimée) et refuse SasPay pour toute obligation contenant des frais Groupia non nuls. Les montants historiques sont préservés. Les anciens tests tarifés conservent leur configuration explicite pour vérifier la compatibilité des données historiques. Une surcharge directe de la propriété Spring doit rester à zéro ; le contrôle SasPay refuse toute obligation non conforme.

## Périmètre fonctionnel du lot 1

Commandes de groupage, Sénégal, XOF, réseaux renvoyés disponibles par le tarif marchand, sans OTP préalable. Après autorisation du jeton privé de suivi, `POST /api/public/orders/{orderNumber}/payment-quotes` calcule les offres à partir du montant de l'obligation sauvegardée. Les devis durent au plus 120 secondes et sont persistés avec organisation, obligation, compte et réseau. Le frontend montre prix, commission zéro, frais cliente, total et net marchand, puis transmet seulement l'ID du devis accepté avec la clé d'idempotence.

La réservation vérifie le devis sous le verrou de l'obligation. Une clé réutilisée avec un autre devis est refusée. Le fournisseur reçoit `ADD_ON` explicite, des chaînes décimales et les coordonnées serveur ; la clé d'API n'est jamais envoyée au navigateur. Les tarifs sont revérifiés avant l'appel softpay. Les appels réseau ont lieu hors transaction. Un timeout ou une réponse invalide garde la tentative à réconcilier et interdit un second débit automatique.

Une initiation est toujours `PENDING`. Seul `verify` avec `SUCCESS`, même ID, bon marchand, sens `INBOUND`, type `PAIEMENT`, devise et montant attendu, frais acceptés, débit total et net exacts permet la confirmation. Les réponses inconnues ou incohérentes ne livrent rien. Les corps fournisseur, coordonnées et secrets ne sont pas journalisés par l'adaptateur.

Les webhooks sont authentifiés en temps constant sur les octets bruts `timestamp.body`, tolérance ±300 secondes. Le reçu est persisté avant 202 ; le worker existant appelle ensuite `verify`. Aucun ID d'événement unique ni retour de metadata n'est garanti dans la documentation : déduplication par hash du corps et corrélation par ID de transaction déjà enregistré, limitée au même fournisseur et compte. Un webhook en avance attend la liaison de l'ID ; après cinq essais il va en revue. Un échec tardif ne régresse pas un succès.

## Limites du contrat : blocage volontaire

Les tarifs contiennent pourcentage, fixe, plancher, plafond et tranches. Le calcul backend applique ces éléments ; les cas ambigus ou dont les frais XOF ne sont pas entiers sont refusés, car les exemples officiels comportent des centimes XOF sans règle d'arrondi explicite. Les frontières de tranches qui correspondent à plusieurs tarifs sont refusées. Le comportement devra être confirmé avec le fournisseur avant tout passage réel.

La tarification n'offre pas de réservation de devis ni de maximum débité dans l'OpenAPI : une modification des tarifs entre la dernière lecture et softpay ne peut pas être empêchée côté Groupia. La vérification finale détecte tout écart et bloque la livraison, mais ne peut pas annuler un débit. **C'est un obstacle à l'activation réelle**, pas une garantie de frais verrouillés. Ce lot refuse donc toute clé `sk_live_...`.

Un timeout d'initiation avant la sauvegarde de l'ID ne peut pas être réconcilié automatiquement par notre référence : le contrat ne garantit pas son retour. La tentative reste bloquée et doit être investiguée ; aucun nouveau softpay sans résolution. Les réseaux OTP et les coordonnées manquantes ne sont pas inventés.

## Marketplace et payouts

`merchant` est ignoré avec une clé API, toujours liée à son propre marchand. Aucun split, sous-compte vendeuse, escrow ou autorisation marketplace n'est documenté dans ce contrat. Le net du pay-in est celui du **compte marchand SasPay** ; il ne prouve pas que la vendeuse a reçu ses fonds.

`POST /payouts/initialize/` existe, requiert le scope PAYOUT/BOTH, l'activation marchand et une IP whitelistée ; pas de conversion automatique. Les tarifs payout existent, mais le porteur final des frais et le net bénéficiaire doivent être validés avec le fournisseur. Ne pas soustraire ces frais au prix dû à la vendeuse. Aucun adaptateur payout SasPay n'est enregistré, aucune variable ne peut l'activer. Les encaissements SasPay restent intégralement inscrits au solde collecté, mais sont exclus du solde éligible au payout, y compris via Bictorys et après désactivation de SasPay. Cette retenue s'appuie sur le fournisseur du paiement sauvegardé. Les encaissements et reversements Bictorys existants restent indépendants.

## Configuration et exploitation

`SASPAY_ENABLED=false` par défaut. Pour un essai manuel autorisé en test : `SASPAY_API_KEY` (`sk_test_...` uniquement), `SASPAY_WEBHOOK_SECRET`, `SASPAY_MERCHANT_ID`, `SASPAY_CHECKOUT_HOSTS` (liste exacte validée des domaines de checkout ; aucun wildcard). L'API est fixée à `https://api.saspay.me/api/v1`. Le domaine de l'exemple officiel est `pay.saspay.me` ; les autres domaines des opérateurs doivent être vérifiés avant ajout. Aucun secret n'est commité. Créer le webhook via le tableau de bord SasPay, URL `/api/payment/webhooks/saspay`, et synchroniser l'horloge serveur.

401/403 : corriger credentials, scope ou autorisation ; 404 : investiguer la référence ; 409 : réconcilier la même intention ; 422 : corriger l'éligibilité ; 429 : pause d'au moins 60 secondes, `Retry-After` numérique respecté jusqu'à 24h. Aucun de ces codes ne déclenche de POST automatique. En déploiement multi-instance, un limiteur partagé serait nécessaire avant activation à grande échelle.

Vérification locale sans appel réseau ni affichage de secret : `python tools/check_saspay_config.py`.

Le job existant de reprise des webhooks doit être actif. L'inspection des paiements et reçus en revue s'effectue avec les outils opérationnels existants. Ne jamais réinitialiser une tentative incertaine pour la rendre payable sans investigation.

## Lots suivants et validation

1. Ce lot : audit, devis et encaissement de test pour commandes de groupage, vérification serveur, webhooks, UI et tests.
2. Abonnements SasPay : devis de facture, permissions vendeuse, activation sur preuve vérifiée, aucune hypothèse de prélèvement récurrent non documenté.
3. Ventes directes : étendre explicitement les guards, le choix serveur du fournisseur et les tests de courses avec annulation ; conserver le token privé.
4. Marketplace/payout : uniquement après autorisation fournisseur écrite et validation contractuelle du net vendeuse et des frais ; PR distincte.

Tests automatisés : fournisseur HTTP loopback synthétique, clés fictives, SasPay désactivé globalement dans Surefire, scénarios navigateur interceptés. Aucun paiement réel ni paiement sandbox externe n'est déclenché. Les essais opérateur, KYC, domaines de redirection et validation du contrat réel restent non vérifiés. La CI doit être verte avant toute fusion ; un quota GitHub Actions épuisé n'est pas présumé et ne permet pas de contourner ce verrou.


## Résultats de validation locale — 7 novembre 2026

| Contrôle | Résultat |
|---|---|
| Backend `mvnw.cmd -q verify` | 1 324 tests, aucun échec, erreur ou test ignoré ; architecture, PostgreSQL, migrations et formatage inclus |
| Régression préalable `mvnw.cmd -q -Dtest=MerchantBictorysIntegrationTests,MerchantSettlementIntegrationTests,SaspayIntegrationTests,SaspayContractTests,ModularityTests test` | 127 tests réussis |
| Frontend `npm test` | 49 fichiers, 512 tests réussis |
| Frontend `npm run lint`, build de production | Réussis ; avertissements de compilation existants conservés |
| E2E `npm run test:e2e` | Suite complète : 379 scénarios réussis ; après la dernière correction du parcours, les 4 scénarios SasPay ont été relancés et réussissent, dont le nouveau cas de réponse perdue |
| `python -m unittest discover -s tools -p 'test_*.py'` | 15 tests réussis |
| `python tools/check_migrations.py` | 22 migrations historiques gelées conformes |
| `python tools/scan_secrets.py` | 0 détection ; aucune valeur de secret affichée |
| actionlint et `git diff --check` | Réussis |
| `npm audit --audit-level=high` | Réussi ; 15 vulnérabilités modérées préexistantes, dépendances inchangées |
| Snapshot OpenAPI Angular / export backend | Égalité JSON vérifiée, 112 routes |

Les fixtures de migration vérifient la conservation des anciennes valeurs et la nouvelle liaison de devis nulle sur les paiements historiques. Les fixtures de payout Bictorys portent désormais une preuve de paiement source au lieu d'une référence aléatoire : le contrôle de provenance SasPay reste actif sans affaiblir les tests de payout.

Les devis refusent aussi tout montant ou total dépassant la plage de 14 chiffres du validateur monétaire, afin de rester vérifiables et représentables exactement côté navigateur. Il s'agit d'une limite applicative prudente, pas d'un plafond attribué au fournisseur.

CI distante : le [job security de la PR #86](https://github.com/ErenZone01/groupia-ai/actions/runs/42378628760/job/128421336226) n'a pas démarré. L'annotation transmise par l'utilisateur indique des paiements du compte GitHub en échec ou un plafond de dépenses à augmenter. Les jobs backend/frontend ont été ignorés par dépendance. Les contrôles locaux ne remplacent pas l'exigence de CI verte avant fusion ; la PR reste en brouillon, sans fusion ni déploiement.
