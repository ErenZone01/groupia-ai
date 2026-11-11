# SasPay Checkout hébergé — migration Groupia (11 novembre 2026)

**Statut : développement sur PR #86 (brouillon). Non fusionnée et non validée par tests locaux.**
Aucun achat, débit, remboursement ou payout réel n'est nécessaire pour vérifier cette PR.

## Décision

La création de nouvelles tentatives SasPay utilise maintenant le **checkout hébergé**
(`POST /api/v1/checkout-sessions/`) au lieu de `POST /payments/softpay/`.
Le pays du premier pilote reste **SN**, la devise **XOF**.

Les parcours concernés sont :
- vente directe via lien privé de commande ;
- achat initial d'un groupage ;
- règlement d'un solde transport ;
- paiement manuel des factures d'abonnement d'une boutique.

Le client choisit Wave / Orange Money / les moyens effectivement disponibles
**chez SasPay**, où il voit les frais et valide le montant total avant de payer.
Groupia n'impose plus un devis de frais par réseau. Le compte SasPay possède
`ALLOW_CLIENT_OVERRIDE` / `ADD_ON` selon la confirmation du propriétaire ;
le serveur exige encore la preuve `ADD_ON` dans la transaction vérifiée.

## Contrat des objets

| Identifiant | Source | Usage |
|---|---|---|
| `payments.id` | UUID Groupia | Tentative unique et idempotence côté Groupia |
| `provider_charge_id` | `checkout-sessions.id` | **Session hébergée**, persistée dès réception de POST |
| `provider_transaction_id` | `checkout-sessions/{id}/status.transaction_id` | **Transaction financière** : jamais l'ID de session |
| `payment_requests.id` | UUID Groupia | Obligation et montant serveur non modifiable |
| `provider_collection_fee`, `provider_customer_charged` | GET `payments/{transaction_id}/verify` | Frais et débit réels, persistés **uniquement lors d'un règlement vérifié** |

Les champs `provider_collection_fee` et `provider_customer_charged`
sont ajoutés par la migration additive **054**. Les anciens encaissements
Softpay restent historiques : l'ancienne vérification est conservée,
mais toute **nouvelle** initiation SasPay utilise la session hébergée.

## Flux de création

1. Capacité client / autorisation gérante vérifiée côté serveur ;
   montant, devise et marchand issus d'une obligation verrouillée en base.
2. Création et commit d'une tentative Groupia en `CREATED` sous une
   clé d'intention idempotente. Une seconde tentative concurrente est bloquée.
3. Appel de **POST /checkout-sessions/** hors transaction DB, avec :
   `amount`, `currency=XOF`, `country=SN`, `customer_email`,
   `customer_name`, `description`, `fee_charge_mode=ADD_ON`,
   `return_url=<GROUPIA_PUBLIC_BASE_URL>/paiement/retour` et
   métadonnées de rapprochement `groupia_payment_attempt_id`,
   `groupia_reference`, `groupia_purpose`.
4. Groupia conserve l'UUID session et seulement l'URL HTTPS dont le
   domaine figure dans `SASPAY_CHECKOUT_HOSTS`. Si la réponse est
   incertaine, **ne jamais refaire ce POST** : pas de contrat
   `Idempotency-Key` documenté sur cette route.
5. Le navigateur est dirigé vers SasPay (même onglet). Le lien privé
   Groupia reste local, jamais envoyé dans `return_url` ni les métadonnées.
6. SasPay peut rediriger vers `/paiement/retour` après succès ;
   cette page revient au suivi Groupia quand la session locale existe,
   sans attribuer de succès à la seule redirection.

## Vérification et reprise

- Le job `SaspayHostedCheckoutPoller` effectue des **GET uniquement**
  pour les sessions `PENDING` et les créations incertaines.
- Il lit `GET /checkout-sessions/{id}/` et
  `GET /checkout-sessions/{id}/status/` et vérifie l'identité session,
  le marchand, le montant, la devise, les métadonnées et l'UUID Groupia.
- Si `status=PAID` et `transaction_status=SUCCESS`, il exige
  **encore** `GET /payments/{transaction_id}/verify/`.
  Ce résultat doit confirmer `SUCCESS/PAIEMENT/INBOUND`,
  même marchand, devise, principal et net, `ADD_ON`,
  `client_fee >= 0` et
  `debited_amount = requested_amount + client_fee`.
- Les états `PENDING`, `CANCELLED`, `EXPIRED` d'une **session**
  n'ont pas d'identifiant de transaction et ne peuvent pas confirmer
  un règlement. Une session annulée ou expirée peut seulement fermer
  la tentative, libérant une reprise autorisée par la politique Groupia.
- Si le POST de création a peut-être réussi mais sa réponse est perdue,
  une récupération GET lit au maximum 3 pages de sessions,
  cherche l'unique UUID de tentative dans les métadonnées et refuse
  toute référence dupliquée/incompatible. Si le résultat reste incertain,
  la tentative reste bloquée pour **revue**, pas de double POST.
- Les webhooks signés restent enregistrés et traités selon les
  protections existantes ; ils ne sont pas utilisés seuls comme preuve
  de succès pour une session hébergée.
- Si le client ne revient jamais, le job continue sa vérification.
- Aucune opération ici n'autorise un payout ou la livraison d'une
  commande non réglée.

## Informations client

SasPay exige `customer_name` et `customer_email` pour créer une
session. Groupia conserve **l'e-mail facultatif pour créer une
commande sans compte**. Si la commande ne possède pas d'e-mail, le
client peut simplement fournir **un e-mail de paiement** depuis
son lien privé (vente directe ou groupage) ou sa session
authentifiée (transport). L'adresse n'altère pas le snapshot
historique de la commande.

## Variables et préparation

- `SASPAY_ENABLED`, `SASPAY_API_KEY`, `SASPAY_WEBHOOK_SECRET`,
  `SASPAY_MERCHANT_ID` : valeurs provider existantes.
- `SASPAY_CHECKOUT_HOSTS` : hôtes de paiement HTTPS réellement utilisés
  par SasPay (pas des wildcard, ni des URL complètes).
- `GROUPIA_PUBLIC_BASE_URL` : domaine HTTPS stable de l'application
  client en production, **pas** un tunnel éphémère `trycloudflare.com`.
- `SASPAY_LIVE_PILOT_ENABLED`,
  `SASPAY_LIVE_PILOT_ORGANIZATION_ID`,
  `SASPAY_LIVE_PILOT_MAX_CHARGED_XOF` : restrictions
  du pilote conservées.
- `groupia.payment.saspay.hosted-poll-delay-ms` : 60000 par défaut.
- `groupia.payment.saspay.legacy-softpay-tests` : **NE JAMAIS
  activer en production**. Uniquement dans les anciennes fixtures de tests.

**Limite du plafond pilote** : SasPay choisit le réseau et les frais
sur la page hébergée. Groupia peut contrôler son montant de base
**avant** la création et son total vérifié **après** l'encaissement,
mais ne peut pas garantir d'avance que le total débité reste inférieur
à un plafond avec frais inconnus. Ne pas augmenter les plafonds
de test sans décider explicitement de ce risque.

## Vérification locale avant fusion

Depuis la racine :
```powershell
git pull --ff-only origin saspay-payin-v1
python -m unittest discover -s tools -p "test_saspay*.py"
python tools/check_migrations.py

cd Backend
$env:SPRING_PROFILES_ACTIVE = "test"
.\mvnw.cmd spotless:apply
.\mvnw.cmd "-Dtest=SaspayContractTests,SaspayHostedContractTests,PaymentHostedSessionTests" test
.\mvnw.cmd "-Dtest=SaspayIntegrationTests" test
.\mvnw.cmd verify

cd ..\Frontend
npm ci
npm run lint
npm test
npm run build
npx playwright test e2e/direct-saspay.spec.ts e2e/saspay-collection.spec.ts
```

Le fichier `tools/check_migrations.py` existe dans le dépôt. `npm test`
exécute déjà `ng test --watch=false`. Les E2E démarrent leur propre serveur
Angular/production selon `Frontend/playwright.config.ts`.
Les contrats OpenAPI générés ont été ajustés avec les nouveaux champs
`payerEmail` ; une régénération complète des clients API doit être
vérifiée avant fusion pour éviter qu'un prochain `api:generate` n'efface
les champs ajoutés manuellement.
Aucun test `test` ne doit utiliser `Backend/.env` avec les clés LIVE.
Les intégrations PostgreSQL exigent Docker/Testcontainers local.

En cas d'erreur : conserver le code de sortie exact et les premières lignes
du compilateur ou du test en échec, **sans coller les secrets LIVE**.
Ne pas fusionner si l'un des tests échoue.

## Cas d'acceptation du MVP

- Aucune création d'un deuxième checkout après double clic, timeout,
  retour au navigateur ou rechargement.
- GET `status` qui renvoie PENDING ne règle pas la commande.
- PAID ne règle pas la commande si la vérification financière donne 404,
  si le marchand diffère, si le net est inférieur à l'attendu, si
  le total/frais sont incohérents ou si `ADD_ON` est absent.
- Une commande réglée est immuable/idempotente même si le webhook
  arrive avant/après le retour client.
- Le client revient vers Groupia sans divulguer son lien privé ;
  il peut aussi retrouver sa commande sans retour automatique.
- La vendeuse ne peut ni expédier ni retirer sur un paiement non vérifié.

## Hors de portée de cette migration

Le checkout hébergé ne démontre **pas** que SasPay reverse directement
l'argent à chacune des vendeuses multi-tenant. C'est un contrat
marchand/payout séparé, à faire valider avant une ouverture commerciale.
Les paiements historiques du 10/11 en `UNKNOWN_REFERENCE` restent à
réconcilier **en lecture seule** ; aucun UPDATE SQL par montant/heure.
Les abonnements restent renouvelés manuellement, sans mandat de
prélèvement récurrent prouvé. Pays autres que SN/XOF : prochaine phase
après vérification du contrat d'éligibilité et de devise.
