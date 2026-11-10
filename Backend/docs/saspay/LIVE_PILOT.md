# SasPay — pilote live à faible montant (préparation, non validé)

Ajouté après la validation locale du 9 novembre 2026 de la PR #86. **Les nouveaux changements de pilote n'ont pas encore été exécutés contre Maven, Angular, Playwright ni une CI verte. Ne déployer ni activer de paiement live avant validation sur la nouvelle tête de branche.**

## Ce qui change

- Les propriétés SasPay sont maintenant chargées dans tous les profils Spring, y compris `prod`. La lecture de `.env` reste réservée au profil non-`prod` ; injecter les vrais secrets via l'environnement du serveur/conteneur.
- Une clé `sk_live_` est acceptée uniquement si le profil `prod` est actif ET que `SASPAY_LIVE_PILOT_ENABLED=true`.
- Le pilote ne peut créer de nouveaux devis que pour **une seule organisation** déclarée dans `SASPAY_LIVE_PILOT_ORGANIZATION_ID`. L'initiation vérifie à nouveau cette organisation avant toute requête de paiement.
- Le **montant total débité**, frais pay-in inclus, est plafonné côté serveur à `SASPAY_LIVE_PILOT_MAX_CHARGED_XOF` (2 000 XOF par défaut, plafond de configuration absolu 5 000 XOF).
- Aucun payout SasPay réel ni remboursement API réel n'est implémenté. Les opérations marketplace restent purement simulées. L'activation d'un petit montant ne vaut pas autorisation d'encaisser pour des vendeuses tierces.

## Identifier le marchand, sans payer

Depuis la racine du dépôt, avec une clé SasPay live uniquement sur votre PC (ne jamais la communiquer à ChatGPT) :

```powershell
python tools/saspay_diagnostics.py --env-file Backend/.env
```

Le diagnostic appelle seulement `GET /merchant-balances/` et `GET /pricing/my-rates/` de `https://api.saspay.me/api/v1` ; aucun POST, payout ou débit. Il extrait `merchant` (UUID du marchand) des soldes et liste les réseaux SN/XOF actifs sans afficher ni clés, ni soldes, ni données clientes. Si la réponse ne contient pas de marchand ou en contient plusieurs, faire confirmer le bon UUID par SasPay. Ne pas copier un identifiant d'exemple de la documentation.

Sources officielles :
- https://docs.saspay.me/api-reference/wallet/balances-list
- https://docs.saspay.me/api-reference/reference/my-rates
- https://docs.saspay.me/api-reference/introduction

## Variables cibles du backend

Le bloc suivant est un **exemple de configuration cible**, pas une autorisation de mise en service :

```dotenv
SPRING_PROFILES_ACTIVE=prod
SASPAY_ENABLED=true
SASPAY_API_KEY=sk_live_REMPLACER_EN_SECRET
SASPAY_WEBHOOK_SECRET=SECRET_WEBHOOK_REEL
SASPAY_MERCHANT_ID=UUID_REEL_DU_MARCHAND
SASPAY_CHECKOUT_HOSTS=pay.saspay.me
SASPAY_LIVE_PILOT_ENABLED=true
SASPAY_LIVE_PILOT_ORGANIZATION_ID=UUID_DE_LA_BOUTIQUE_TEST
SASPAY_LIVE_PILOT_MAX_CHARGED_XOF=2000

BICTORYS_ENABLED=false
GROUPIA_BICTORYS_PAYOUTS_ENABLED=false
GROUPIA_MERCHANT_PAYOUTS_ENABLED=false
GROUPIA_MARKETPLACE_SIMULATION_ENABLED=false
GROUPIA_WEBHOOK_RETRY_ENABLED=true
GROUPIA_EVENT_RECOVERY_ENABLED=true

GROUPIA_DIRECT_CHECKOUT_ENABLED=true
GROUPIA_DIRECT_PAYMENT_AUTO_PREPARE_ENABLED=true
GROUPIA_DIRECT_PAYMENT_INITIATION_ENABLED=true
GROUPIA_SUBSCRIPTIONS_ENABLED=true
GROUPIA_SUBSCRIPTION_ENFORCEMENT_ENABLED=false
```

`SASPAY_LIVE_PILOT_ORGANIZATION_ID` est l'UUID **Groupia** de la boutique test, différent du `SASPAY_MERCHANT_ID`. Ne pas le remplacer par l'UUID SasPay. Récupérer l'ID exact de l'organisation via une vue/API Groupia autorisée ; ne pas inventer une valeur.

`GROUPIA_DIRECT_*` ne doit être activé qu'après recette du checkout et du contrôle serveur des montants, des adresses et de l'éligibilité. Pour un devis SasPay, les coordonnées du payeur doivent comporter prénom, nom, téléphone `+221...` et email. Les commandes/groupages utilisent leurs propres parcours et capacités.

Pour les paramètres en cours, exécuter hors-ligne depuis un shell ayant chargé les variables :
```powershell
python tools/check_saspay_config.py
```
Ce validateur ne contacte jamais le fournisseur et ne prouve donc pas qu'un paiement peut aboutir.

**Attention :** le prix de l'abonnement vendeur par défaut est 10 000 FCFA ; avec un plafond pilote de 2 000 FCFA frais inclus, il ne peut pas être payé via SasPay live. Cela est volontaire : ne pas réduire les factures d'abonnement en production simplement pour contourner le plafond. Tester d'abord une commande propre de faible montant dans la boutique pilote.

## Tunnel Cloudflared temporaire

Créer le webhook dans le dashboard SasPay avec l'URL **publique du backend**, pas celle de l'interface Angular :
`https://ADRESSE_CLOUDFLARED_BACKEND/api/payment/webhooks/saspay`

Souscrire aux événements `transaction.created`, `transaction.success`, `transaction.failed`, en respectant le secret de signature `SASPAY_WEBHOOK_SECRET`. Vérifier avec un événement test et les journaux de livraison SasPay. Les adresses `trycloudflare.com` peuvent changer à chaque redémarrage du tunnel ; mettre immédiatement à jour l'URL du webhook ou, idéalement, utiliser un domaine stable avant toute transaction live. Ne jamais journaliser le corps brut avec des données sensibles.

- https://docs.saspay.me/api-reference/webhooks

## Points de blocage avant tout débit réel

1. Obtenir de SasPay les règles de tarification/arrondi XOF et une garantie de non-dépassement du devis accepté, ou une solution contractuelle permettant de garantir le total effectivement débité. L'API actuelle ne garantit pas le verrouillage du montant du devis : en cas d'écart constaté après débit, Groupia passe en revue au lieu de confirmer, mais l'argent peut déjà avoir été retiré.
2. Vérifier que le compte est autorisé pour le pay-in et pour **la nature exacte du paiement pilote**, idéalement un achat de test sur votre propre boutique (pas une collecte pour une tierce vendeuse). KYC validé ne suffit pas à autoriser une plateforme marketplace.
3. Exécuter les tests complets Java/Angular/E2E et la CI sur ces nouveaux commits, vérifier migrations et sauvegardes, puis tester la disponibilité du backend et le webhook HTTPS.
4. Contrôler le parcours : devis → frais et total visibles → consentement → même clé d'idempotence → push/redirection réelle → webhook signé → GET verify → montant/devise/marchand exacts → statut payé. En cas d'inconnu, NE PAS retenter manuellement avec une nouvelle clé ni délivrer la commande.
5. Documenter la procédure d'incident et de remboursement, le détenteur des fonds, l'historique financier et l'interruption immédiate du pilote (désactiver `SASPAY_ENABLED` coupe les nouvelles initiations, mais vérifier comment continuer à réconcilier les transactions en cours avant de révoquer la clé).
6. Les payouts SasPay réels nécessitent un développement distinct, une autorisation fournisseur et une IP publique sortante whitelistée. Ne pas activer les reversements juste parce qu'un paiement test réussi.

Les scénarios frontend existants n'affichent SasPay que si le serveur annonce le fournisseur disponible. Sur une commande directe, le paiement apparaît sur le **lien privé de suivi** une fois la demande de paiement préparée ; sans quote admissible ou sans email du payeur, il n'y a pas d'option exploitable. L'interface Angular n'accepte pour l'instant que les redirections hébergées sous `pay.saspay.me`.

**Statut : code pilote préparé dans PR #86, non validé en exécution et non déployé.**


**Diagnostic des formats tarifaires (9 novembre) :** si le script indiquait
`tarif incomplet ou format non accepté par Groupia`, la nouvelle version détaille maintenant
le champ exact absent, le champ numérique reçu alors qu'une chaîne était attendue, ou
un autre problème de structure — sans exposer le tarif, les informations client ou la clé.
Refaire la même commande `--amount-xof 1000` après `git pull`. Les réseaux simplement
« actifs » ne constituent pas une garantie d'éligibilité du devis à un montant donné.
Ne pas remplacer des valeurs manquantes par zéro ni arrondir des frais XOF sans
contrat explicite du fournisseur.

## Diagnostic précis lorsque `payment-quotes` répond `[]`

Depuis la racine du projet, avec la clé seulement dans `Backend/.env` :

```powershell
python tools/saspay_diagnostics.py --env-file Backend/.env --organization-id b95a0515-1bcf-4740-8417-c594d02f211e --amount-xof 1100
```

- `Boutique Groupia autorisée pour le pilote : NON` signifie que `SASPAY_LIVE_PILOT_ORGANIZATION_ID` ne correspond pas à la boutique Groupia. L'UUID marchand SasPay n'est pas cet identifiant.
- L'outil liste les causes d'exclusion pour les réseaux SN/XOF (OTP requis, tarif incomplet, mode ADD_ON indisponible, frais XOF fractionnaires, total dépassant le plafond, etc.).
- Cet outil ne contacte que les endpoints GET documentés, n'initie aucun transfert, ne montre ni clé ni téléphone/email. Son analyse est indicative : l'application Java fait autorité.
- Le backend renvoie désormais `409 SASPAY_PAYER_REQUIRED` si le contact client est incomplet, `409 SASPAY_PILOT_ORGANIZATION_NOT_ALLOWED` si la boutique est incorrecte et `409 SASPAY_NO_COMPATIBLE_QUOTE` s'il n'existe aucun devis. Le frontend présente un message adapté.

Après avoir corrigé l'UUID de la boutique dans l'environnement, **redémarrer complètement le processus Spring**. Ne pas changer le `SASPAY_MERCHANT_ID`, ne pas supprimer des restrictions d'arrondi, ne pas tester un véritable débit pour rechercher le problème.

**Les nouveaux correctifs de diagnostic n'ont pas subi la validation Maven/Angular/E2E complète ni une CI verte.** Les changements sont dans la PR brouillon uniquement.

## Formats JSON numériques dans les tarifs SasPay (9 novembre 2026)

Le compte pilote retournait `payin.tiers[*].min_amount` sous forme de **nombre JSON** alors
que les exemples publics du fournisseur l'écrivent comme une chaîne décimale. L'ancien
parseur refusait toutes les offres concernées, ce qui aboutissait à une liste de devis vide.

Le parseur `SaspayPricing.decimal` accepte maintenant soit une chaîne décimale bornée,
soit un nombre JSON décimal borné. Il ne fait **aucun arrondi** des frais en XOF et conserve
les blocages sur les montants invalides, l'absence de paliers uniques, le mode `ADD_ON`,
les réseaux à OTP et le plafond du pilote. Le diagnostic Python lit les flottants JSON
en `decimal.Decimal` pour éviter une conversion binaire approximative.

Tests de non-régression ajoutés : formats numériques acceptés pour `min_amount`,
`max_amount`, `percent`, `fixed`, refus des formes invalides, refus de 27,5 XOF
de frais (arrondi encore inconnu) et devis positif sur la route vente directe sans
créer de paiement.

Recette locale après `git pull` :

```powershell
python -m unittest discover -s tools -p test_saspay_diagnostics.py
python tools/saspay_diagnostics.py --env-file Backend/.env --organization-id b95a0515-1bcf-4740-8417-c594d02f211e --amount-xof 1000
cd Backend
.\mvnw.cmd -Dtest=SaspayContractTests,SaspayIntegrationTests test
```

Si les tests et le devis sont positifs, vérifier depuis l'interface seulement
`POST /api/public/direct-orders/payment-quotes` sur une commande éligible.
Ne pas initier de paiement live avant revue du contrat financier et validation du
workflow de confirmation. Les changements ci-dessus n'ont pas été exécutés sur
Maven/CI dans cet environnement.

## Résolution du contexte de tests et diagnostic du devis live (9 novembre, soir)

L'échec de `SaspayIntegrationTests` provient de `ProductionCommercePolicy` : une
valeur historique `groupia.ordering.platform-fee-xof=300` injectée par
`TestcontainersConfiguration` a activé la protection de production quand le profil
`prod` du local `.env` a été récupéré par le contexte de test. Les tests d'intégration
sont maintenant annotés `@ActiveProfiles("test")` afin de ne **jamais charger les
politiques de production** pendant les tests qui créent/tronquent leur base
Testcontainers. Le serveur réel doit rester en `prod` pour sa clé `sk_live_`.

Un véritable appel `POST /api/public/direct-orders/payment-quotes` a renvoyé
`409 SASPAY_NO_COMPATIBLE_QUOTE`, même après correction du parsing numérique.
Ce n'est pas une raison pour lever le refus ni abaisser les protections.

Le diagnostic de tarifs Python compare désormais aussi `network_name` et le code
réseau aux exigences Java. En mode logging DEBUG de
`sn.groupia.payment.infrastructure.provider.saspay.SaspayPaymentProvider`, chaque
réseau rejeté est indiqué par son identifiant validé et son **code d'erreur interne
uniquement** ; aucune clé, détail de client, taux ou réponse brute du fournisseur
n'est journalisé.

Pour reproduire sans payer :

```powershell
# Racine projet : remplacer 1000 par accepted_total_xof EXACT de la commande
python tools/saspay_diagnostics.py --env-file Backend/.env --organization-id b95a0515-1bcf-4740-8417-c594d02f211e --amount-xof 1000
# Backend dans un terminal séparé des tests : ajouter un logging limité lors du démarrage
$env:LOGGING_LEVEL_SN_GROUPIA_PAYMENT_INFRASTRUCTURE_PROVIDER_SASPAY_SASPAYPAYMENTPROVIDER = "DEBUG"
# Ou option JVM Spring : --logging.level.sn.groupia.payment.infrastructure.provider.saspay.SaspayPaymentProvider=DEBUG
```

Sur PowerShell, pour les noms de propriétés Spring complexes, privilégier plutôt une
option de lancement Spring `--logging.level.sn.groupia.payment.infrastructure.provider.saspay=DEBUG`
que le nom d'une variable d'environnement créé à la main. Ne jamais augmenter
le niveau de logs HTTP ou afficher les en-têtes Authorization.

Avant de reproduire, relever le montant réel de la commande :

```sql
SELECT order_number, accepted_total_xof
FROM direct_orders
WHERE order_number IN ('DIR-000000000008','DIR-000000000009');
```

Tests manuels et Maven complets toujours à exécuter sur le nouveau HEAD. Les tests
SasPay restent isolés par un fournisseur HTTP simulé et une base temporaire,
mais la recette de la vraie clé et des devis SasPay est une procédure distincte.

## Diagnostic Java numérique en notation scientifique — 9 novembre, 20h22 UTC

Après la première correction de lecture des nombres JSON, le vrai devis
`/api/public/direct-orders/payment-quotes` retournait encore
`409 SASPAY_NO_COMPATIBLE_QUOTE` avec le scan Java suivant :
`rates=64, countryCurrencyMatches=7, acceptedNetworks=0`.
Les réseaux Wave/Orange/Expresso/FreeMoney/Wizall étaient exclus par
`SASPAY_INVALID_MONEY`, alors que les tests Python en lecture seule
trouvaient Expresso et Wizall théoriquement compatibles pour **1000 XOF**.

Cause technique probable : `SaspayPricing.decimal` appliquait une expression
régulière au résultat de `JsonNode.asText()`, y compris aux nombres JSON.
Le texte produit par le parseur peut employer une notation scientifique
légitime (`0E-8` ou `1E+3`), qui ne correspond pas à la forme décimale
attendue par la regex. Les chaînes JSON restent strictement validées ;
les **nombres** JSON utilisent désormais `JsonNode.decimalValue()` puis
des bornes par valeur (`0 <= amount < 1e14`, au plus six décimales non nulles).
Aucune conversion en float, approximation du taux, ou règle d'arrondi XOF
n'a été ajoutée.

Les tests contractuels couvrent les notations numériques exponentielles,
les montants invalides/excessifs, les chaînes exponentielles interdites et
le refus d'arrondir 27,5 FCFA de frais. Le diagnostic Python suit les mêmes
bornes. Pour les refus restants, une ligne DEBUG indique le **nom du champ**
`min_amount`/`max_amount`/`percent`/`fixed`/`floor_amount`/`cap_amount`
et le type JSON, jamais sa valeur.

Le rapport utilisateur précédent confirme **48 tests réussis, BUILD SUCCESS**
pour les commits antérieurs, mais pas encore pour le présent correctif.

Validation avant paiement live :
```powershell
git pull --ff-only origin saspay-payin-v1
cd Backend
$env:SPRING_PROFILES_ACTIVE = "test"
.\mvnw.cmd "-Dtest=SaspayContractTests,SaspayIntegrationTests" test
```
Puis dans un terminal **distinct**, redémarrer le vrai backend `prod`
avec les variables SasPay habituelles (et logging DEBUG restreint
au paquet `sn.groupia.payment.infrastructure.provider.saspay`) et
cliquer uniquement sur « Voir les frais SasPay » pour une commande
de 1000 XOF. Examiner `payment-quotes` sans effectuer d'encaissement.

## Chercher des montants d'essai compatibles sans encaisser (9 novembre)

Les devis réels de la boutique Groupia sont maintenant disponibles pour une
commande de 1000 XOF, notamment Expresso (frais 139, total 1139) et Wizall
(frais 35, total 1035), chacun avec commission Groupia 0 et net théorique
marchand 1000. Cela prouve le fonctionnement de l'appel de tarification, pas
l'encaissement ni le versement au vendeur.

Pour les autres réseaux, un refus peut venir d'un calcul de frais fractionnaires
en XOF ou de paliers tarifaires qui excluent le prix testé. Le calculateur bloque
l'arrondi non documenté. La documentation publique SasPay consultée pour
`GET /pricing/my-rates/` décrit `ADD_ON`, `DEDUCTED`, `percent` et `fixed` mais ne
détermine pas la règle exacte d'arrondi de tous les taux.

Depuis la racine du dépôt :

```powershell
python tools/saspay_diagnostics.py --env-file Backend/.env --organization-id b95a0515-1bcf-4740-8417-c594d02f211e --amount-xof 1000 --suggest-amounts
python -m unittest discover -s tools -p test_saspay_diagnostics.py
```

`--suggest-amounts` classe trois montants d'exemple **entiers**, les plus proches
du montant testé, selon les tarifs actifs du marchand. Chaque suggestion est
limitée au plafond de paiement piloté **frais compris**, exige un seul palier
de prix, un tarif `ADD_ON`, un réseau sans OTP prépaiement requis et des frais
XOF strictement entiers. **Aucun paiement ni modification d'une commande n'est
effectué.** Le script ne fait que les GET déjà autorisés.

Pour tester un nouveau montant, créer une **nouvelle commande valide** avec
produit/livraison authentiques ; ne jamais modifier le total historique d'une
commande SQL pour forcer un devis.

Pour permettre Wave/Orange Money avec des frais fractionnaires sur des prix
arbitraires, obtenir de SasPay une confirmation explicite sur : unité monétaire,
règle d'arrondi côté pay-in, mode `ADD_ON` appliqué au compte, correspondance
`requested_amount`, `client_fee`, `debited_amount` et `net_amount` ; puis créer
des tests contractuels sur cette réponse. **Ne pas inventer CEILING/FLOOR/HALF_UP**.
Le fournisseur peut ignorer `fee_charge_mode` sur les opérations softpay si
l'option `ALLOW_CLIENT_OVERRIDE` n'est pas active (voir documentation SasPay).

## Retour pilote live et correction diagnostic webhook — 9 novembre 2026

L'utilisateur a déclenché une initiation réelle Expresso pour
`DIR-000000000011`. Le tableau de bord SasPay montre une transaction
**PENDING** et un mode `ADD_ON` :
montant demandé 1000 XOF ; frais gateway 24 XOF ; frais SasPay plateforme
115 XOF ; frais client totaux 139 XOF ; montant débité **prévu**
1139 XOF ; net **prévu** 1000 XOF. Les deux tentatives de livraison du
webhook `transaction.created` ont renvoyé **HTTP 401**. « Tentative
réussie » signifie que la demande a été transmise au réseau, **pas
que l'encaissement est confirmé**. Aucun payout vendeur n'a été démontré.

### Documentation et fichiers réellement lus

Le dépôt contient déjà `skills/saspay-integration/SKILL.md` et
`skills/saspay-integration/AGENTS.MD`, références complètes de
l'intégration ; ils sont documentés par
https://docs.saspay.me/llms.txt. Le contrat OpenAPI conservé
`Backend/docs/saspay/openapi.json` contient les routes
`GET /merchant-webhooks/`, `GET /merchant-webhook-subscriptions/`,
`GET /payments/{payment_id}/verify/` et
`POST /checkout-sessions/`. Il ne définit pas d'endpoint de pré-devis
garanti pour chaque réseau et n'indique pas la règle d'arrondi des
frais XOF fractionnaires. Ne pas en inventer.

Guide webhook : https://docs.saspay.me/api-reference/webhooks
Signature : HMAC SHA-256 sur octets bruts `timestamp.body`,
`X-Webhook-Signature` en hex minuscule,
`X-Webhook-Timestamp` en secondes, tolérance ±300 s.
Le `signing_secret` est **distinct** de `sk_live_...`.
Il appartient au **point de réception** et ne peut pas être relu via GET
SasPay. Seules création/rotation depuis le dashboard le montrent.
Les reprises sont automatiques selon la politique SasPay mais peuvent
être relancées manuellement après correction.

### Diagnostic sans transfert ni débit

```powershell
# Racine du dépôt, après récupération de la dernière branche
python tools/saspay_diagnostics.py --env-file Backend/.env --organization-id b95a0515-1bcf-4740-8417-c594d02f211e --amount-xof 1000 --check-webhooks
python -m unittest discover -s tools -p test_saspay_diagnostics.py
```

Ce script ne fait que des GET sur la liste des points de réception
et de leurs abonnements (pas les payloads des livraisons). Il n'affiche
que les hôtes/chemins des URL, les indicateurs LIVE/actif et les types
d'événement, jamais les paramètres d'URL, la clé API ou le signing secret.
La présence d'un secret local n'implique **pas** qu'il correspond à celui
du point de réception LIVE.

Le backend fournit désormais deux traces DEBUG (activer seulement le
paquet `sn.groupia.payment.infrastructure`) :
- `SasPay webhook reached Spring payment controller` : le POST atteint Spring.
- `SasPay webhook authentication rejected: <REASON>` : une signature est refusée
  pour code fixe `HMAC_MISMATCH`, `TIMESTAMP_OUTSIDE_300S_WINDOW`,
  `SIGNATURE_HEADER_MISSING_OR_DUPLICATE`, `INVALID_SIGNATURE_FORMAT`, etc.

Si SasPay voit HTTP 401 et **aucune première trace**, chercher une couche
Cloudflare/reverse-proxy/access-control/routage en amont (ou une instance
non redémarrée). Si les deux traces apparaissent et le code
`HMAC_MISMATCH`, vérifier le secret de signature du webhook **LIVE**
à l'aide du tableau de bord SasPay ; en cas de doute, **faire une
rotation** depuis le tableau de bord et recharger uniquement la variable
`SASPAY_WEBHOOK_SECRET` côté serveur. Ne pas désactiver la vérification.
Si l'horodatage est hors fenêtre, synchroniser l'horloge ; si un header
est absent, vérifier le proxy. Ne pas afficher les corps ou headers.

Une fois corrigé, envoyer uniquement le **webhook de test** depuis le
tableau de bord ou retransmettre un événement légitime qui était
refusé — aucune nouvelle initiation de paiement. Le test donne une
réponse 2xx si la vérification HMAC passe et que la réception peut
être persistée. `transaction.created` conserve l'état PENDING ;
seul `GET /payments/{id}/verify/` avec SUCCESS et contrôles financiers
permet d'avancer l'état de la commande. Un débit en attente ne doit
jamais être considéré comme réussi. Le solde du marchand SasPay n'est
pas un versement au compte de la vendeuse.

### Réseaux restants

Wave, Orange et Free Money retournent des frais fractionnaires dans
les conditions tarifaires du compte à 1000 XOF. Djamo et PayDunya
n'ont pas de palier compatible jusqu'au plafond actuel de 2000 XOF,
frais inclus. Le tarif public du marchand et le payload `fee_charge_mode`
ne garantissent pas l'arrondi final. Les seules options validées ici
sont l'affichage de devis exacts et refus des devis non sûrs.
Un parcours alternatif de checkout **hébergé** (`POST /checkout-sessions/`)
est documenté mais doit être conçu avec son propre consentement client,
idempotence, plafond, anti-doublon et vérification d'état ; il n'est
pas ajouté automatiquement dans cette PR pour ne pas contourner les
contrôles financiers.

## SasPay XOF décimal : correction optionnelle et revue de sécurité (10 novembre 2026)

La [documentation de vérification SasPay](https://docs.saspay.me/api-reference/payments/verify)
montre **explicitement** `client_fee: "37.50"`, `debited_amount: "2537.50"`
et `currency: "XOF"`. Le code original refusait toutes les décimales XOF.
Cela écartait Wave, Orange Money et Free Money pour certaines commandes.
En revanche, la documentation **ne garantit pas** que chaque portefeuille
mobile money débite les sous-unités XOF sans arrondi propre au gateway.

Un mode de pilotage opt-in a été ajouté :
`SASPAY_ALLOW_FRACTIONAL_XOF_FEES=true` active les devis XOF dont les
**frais calculés exacts** ne dépassent pas deux décimales. Ce mode :
- conserve le prix de la commande entier XOF et la commission Groupia nulle ;
- refuse la moindre troisième décimale non nulle, sans arrondi ;
- refuse les tarifs ambigus ou absents, les frais qui dépassent le plafond,
  les modes hors `ADD_ON` et les réseaux OTP non supportés ;
- persiste `payment_fee_quotes.fee` jusqu'à deux décimales via migration
  **052** (la migration 045 et toutes les migrations 001-022 restent
  inchangées) ;
- recalcule les mêmes frais immédiatement avant l'appel softpay et refuse
  tout changement de tarif ;
- exige au retour du fournisseur la preuve `requested_amount == amount`,
  `client_fee == devis`, `debited_amount == amount + devis`, et
  `net_amount == amount` avant tout règlement de commande.

**Le flag est false par défaut.** Il ne garantit pas que SasPay/les gateways
acceptent le montant exact, ni qu'une tentative de paiement n'ait jamais
déjà débité un client avant détection d'écart. Ce risque justifie de tester
d'abord les devis en lecture seule et de conserver le plafond de 2000 XOF.

Configuration ciblée de l'application réelle :
```properties
SASPAY_ALLOW_FRACTIONAL_XOF_FEES=true
SASPAY_LIVE_PILOT_MAX_CHARGED_XOF=2000
# Conserver SASPAY_ENABLED, SASPAY_LIVE_PILOT_ENABLED, secret, compte,
# identifiant organisation et SPRING_PROFILES_ACTIVE=prod déjà validés.
# SASPAY_CHECKOUT_HOSTS doit contenir les domaines HTTPS EXAC TS réellement
# renvoyés par le provider ; pay.saspay.me et pay.wave.com sont des exemples
# documentés ; ne pas utiliser de wildcard.
```
(En pratique, orthographier les commentaires comme de simples remarques :
`SASPAY_CHECKOUT_HOSTS=pay.saspay.me,pay.wave.com` uniquement après
vérification des redirections réellement utilisées.)

Ne pas modifier le prix d'une ancienne commande en base. Utiliser une
nouvelle commande de 1000 XOF et uniquement « Voir les frais SasPay »
pour vérifier que Wave et Orange apparaissent. **Ne pas lancer de second
paiement réel automatiquement**. Si le tarif produit plus de deux décimales
exactes, le réseau reste volontairement bloqué.

Tests locaux à rejouer depuis la racine du dépôt :
```powershell
python -m unittest discover -s tools -p "test_saspay_*.py"
python -m unittest discover -s tools -p "test_check_saspay_config.py"
python tools/saspay_diagnostics.py --env-file Backend/.env --organization-id b95a0515-1bcf-4740-8417-c594d02f211e --amount-xof 1000
cd Backend
$env:SPRING_PROFILES_ACTIVE = "test"
.\mvnw.cmd "-Dtest=SaspayContractTests,SaspayIntegrationTests" test
```

Pour connaître l'état d'une transaction déjà initiée, **sans nouveau débit**,
la commande dédiée vérifie strictement l'identité du marchand et les
montants attendus :
```powershell
python tools/saspay_verify_pilot.py --env-file Backend/.env --payment-id <UUID_TRANSACTION_EXISTANTE> --expected-amount-xof 1000
```
Une transaction `FAILED` reste non payée, même si sa vérification
financière est cohérente. Le statut Groupia dépend séparément de
l'enregistrement et de la réconciliation du webhook.

Autres limites réellement confirmées :
- Le checkout hébergé `POST /checkout-sessions/` ne verrouille pas un
  réseau ni des frais précis et n'accepte pas `Idempotency-Key` ;
  il ne remplace pas directement l'acceptation du devis avec le modèle
  financier actuel. Il demanderait un flux séparé et vérifiable.
- Le plafond LIVE de **2000 XOF frais compris** ne peut pas couvrir
  l'abonnement standard à **10000 XOF**. Ne pas accroître ce plafond
  sans décision explicite de risque et validation fournisseur.
- Les frais et le net du compte marchand SasPay ne sont pas la preuve
  du versement à une vendeuse tierce. Aucun payout réel n'est activé.
- Après `202`, un webhook doit encore être traité/reconcilié avant
  d'autoriser la préparation commerciale.

Les correctifs ci-dessus ont été publiés mais ne sont pas encore
validés par un cycle Maven/Testcontainers de l'auteur du présent lot.
