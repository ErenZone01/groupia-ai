# Retraits SasPay Groupia — politique DEDUCTED, PR #86

**État : implémentation en cours de recette locale ; retrait LIVE désactivé par défaut.**
L'ancien verrou global compilé a été remplacé par deux autorisations séparées :
le pilote métier limité à une boutique et un seul montant, et la libération LIVE
limitée à une seule vente directe Wave. Les deux sont désactivées par défaut.
Un encaissement LIVE réussi ne prouve pas qu'un retrait est autorisé.

## Décision commerciale

- Les clients supportent les frais d'encaissement affichés au checkout.
- Les vendeuses supportent les frais de payout, déduits du montant brut qu'elles
  demandent à retirer. **Groupia ne finance aucun frais de retrait SasPay**.
- La rémunération Groupia provient des abonnements, sans commission sur les ventes.
- Les abonnements sont des paiements distincts qui supportent leurs propres frais.
- Tous les devis de retrait sont présentés avant confirmation ; aucune différence de
  tarif ne peut être acceptée automatiquement après l'accord de la vendeuse.

SasPay documente les deux modes `ADD_ON` et `DEDUCTED`, et précise pour
`DEDUCTED` : `debited_amount=requested_amount` et
`net_amount=requested_amount-client_fee`.
Le champ `fee_charge_mode` de l'initialisation est un **override par requête** ;
s'il est absent, SasPay reprend la configuration par défaut du marchand.
Le `fee_charge_mode` d'un palier `GET /pricing/my-rates/` indique donc
le **mode tarifaire par défaut**, pas une interdiction de demander `DEDUCTED`.
Groupia accepte un palier connu `ADD_ON` ou `DEDUCTED` pour établir une
**estimation** calculée à partir des frais tarifaires, mais demande
**toujours `DEDUCTED` explicitement dans le POST**. Un mode inconnu,
un tarif ambigu, une précision de frais invérifiable ou des frais modifiés
provoquent un refus avant le POST. La vérification du payout doit ensuite
prouver le mode effectif, le montant débité et le net, sans considérer
le devis GET comme une garantie de réussite.

- https://docs.saspay.me/api-reference/payouts/initialize
- https://docs.saspay.me/api-reference/payouts/verify
- https://docs.saspay.me/api-reference/transactions/get
- https://docs.saspay.me/api-reference/reference/my-rates
- https://docs.saspay.me/api-reference/wallet/balances-list

Exemple illustratif : 10 000 XOF retirés, 150 XOF de frais SasPay,
9 850 XOF nets annoncés à la vendeuse. Le solde vendeur baisse de 10 000 XOF.
Les frais ou ajustements éventuels d'un autre opérateur ne sont **pas** garantis
par le devis SasPay : ne pas promettre un net invariable sans preuve fournisseur.

## Parcours Groupia

1. La propriétaire configure son numéro de retrait Wave / Orange Money.
2. `GET .../merchant-payout-quote?destinationId=...` vérifie les droits,
   le solde éligible et la destination, consulte les tarifs SasPay par **GET**,
   puis enregistre un devis immuable valable 3 minutes.
3. L'écran montre le brut, les frais SasPay et le net estimé. Aucun POST de
   transfert n'est lancé lors de ce devis.
4. La vendeuse confirme le `feeQuoteId` exact avec une
   `Idempotency-Key` persistante. Le serveur contrôle l'organisation, la cible,
   le compte, le numéro, le réseau, le brut et l'expiration ; il lie une seule
   fois le devis à une seule demande. Un devis absent, expiré ou détourné
   est refusé.
5. La demande durable créée, le moteur revérifie les fonds et la liaison
   au devis. Puis l'adaptateur SasPay effectue les **GET** de tarifs et soldes
   wallet, refuse tout tarif changé, réseau indisponible ou fonds insuffisants.
6. `POST /payouts/initialize/` porte
   `fee_charge_mode=DEDUCTED`, une clé d'idempotence durable et la destination
   vérifiée. Un ACK n'est jamais une confirmation de versement.
7. `GET /payouts/{id}/verify/` et, en cas de succès, le détail indépendant
   `GET /transactions/{id}/` confirment le bénéficiaire, le réseau,
   l'identité du marchand, le brut, les frais, le débit et le net.
   Un désaccord de frais ou de destination maintient le retrait en revue.
   Aucun nouveau POST ne doit être envoyé pour résoudre une incertitude.

## Historique des migrations

- `056-saspay-payout-fee-evidence.sql` : historique immuable des frais fournisseur.
- `057-saspay-company-fee-budget.sql` : ancienne structure de financement
  Groupia, **désormais sans appel dans le parcours actif**. La migration reste
  dans le journal pour préserver l'historique et l'immutabilité ; aucune
  création de `FUNDING` n'est possible par une action publique.
- `058-saspay-deducted-payout-quotes.sql` : ajout du mode et du net dans les
  preuves, conservation des anciennes preuves `ADD_ON`, devis immuables
  organisés par boutique, cible et bénéficiaire, liaison unique devis ↔ payout.

Une ligne `DEDUCTED` historique retient :
`principal_xof` (brut pris sur le solde vendeur),
`provider_fee_xof` (coût pour la vendeuse),
`wallet_debited_xof=principal_xof`,
`net_amount_xof=principal_xof-provider_fee_xof`.
Aucune modification d'une preuve déjà enregistrée n'est autorisée.

## Diagnostic du compte LIVE sans transfert

Depuis la racine du projet :

```powershell
python tools/saspay_diagnostics.py --env-file .\\Backend\\.env --payout-amount-xof 1000
```

Cette commande utilise exclusivement des GET. Les réseaux annoncés comme
disponibles pour **pay-in** ne sont pas automatiquement éligibles au **payout**.
Si `SASPAY_PAYOUTS_WAVE_NETWORK` ou
`SASPAY_PAYOUTS_ORANGE_NETWORK` sont absentes ou invalides,
le diagnostic affiche des **codes candidats fournis par l'API** avec leurs
tarifs et une estimation du coût en demandant explicitement `DEDUCTED`.
Un tarif GET affichant `ADD_ON` peut désormais être estimé : ce mode
est le défaut annoncé, pas le mode imposé au POST.
Il n'ajoute pas ces codes au fichier `.env`, ne débloque pas les retraits et
ne prouve ni le droit d'émettre un payout, ni l'IP autorisée.

Seuls des codes et droits **confirmés par SasPay** pourront être ajoutés
ensuite à la configuration de retrait. Ne pas activer les variables de
payout ou le verrou de production sur la seule base d'un candidat tarifaire.

## Préparation du pilote local : retrait partiel et blocage par défaut

La confirmation de l'IP publique sortante du PC dans la whitelist SasPay
est nécessaire pour les appels depuis ce PC, mais elle ne suffit pas à autoriser
un retrait. Elle devra être revérifiée si la connexion Internet ou le serveur
change. Le serveur de production aura sa propre IP sortante.

Pour un pilote contrôlé, les paramètres backend suivants ont été ajoutés
(**ne pas les activer avant la recette finale**) :

```dotenv
SASPAY_PAYOUT_PILOT_ENABLED=false
SASPAY_PAYOUT_PILOT_ORGANIZATION_ID=
SASPAY_PAYOUT_PILOT_MAX_XOF=1000
```

- Le premier paramètre est **false par défaut** : même un fournisseur SasPay
  actif ne peut pas obtenir un devis de retrait ni réserver un nouveau retrait
  hors pilote. Le verrou indépendant `SASPAY_PAYOUT_LIVE_PILOT_ENABLED=false`
  reste désactivé par défaut : **aucune sortie LIVE n'est activée** par ce changement.
- Seule la boutique dont l'UUID correspond à la configuration peut obtenir un
  devis de retrait SasPay. Le plafond est vérifié côté serveur : valeur entière
  strictement positive et jamais plus de 1 000 XOF, même en cas de configuration
  erronée du plafond.
- Une seule demande SasPay peut être enregistrée au total pour la boutique
  durant ce pilote, **y compris après échec ou incertitude**, afin d'éviter
  les tentatives multiples. Le déblocage exige une revue explicite, pas un
  retry automatique.
- La vendeuse choisit un montant partiel, puis le serveur vérifie
  `0 < amount <= availableForPayout` et le plafond pilote. Le
  `GET .../merchant-payout-quote?destinationId=<UUID>&amount=1000`
  est en **lecture seule côté fournisseur** ; il crée un devis de frais
  enregistré, mais jamais un transfert.
- Le POST de confirmation ne contient **pas** de montant transmis par le
  navigateur : seul le devis serveur `feeQuoteId` détermine le brut réservé.
  Le montant et les fonds sont revérifiés après verrouillage de la boutique.
  Le mode de frais demeure `DEDUCTED`, avec vérification complète du débit,
  du net, de l'identité et du bénéficiaire.

Une whitelist d'IP correcte, des tests verts ou un devis GET ne prouvent
toujours pas le droit PAYOUT ni la réussite d'un retrait réel.

### Recette locale sans déclencher de retrait

```powershell
cd Backend
.\\mvnw.cmd spotless:check
.\\mvnw.cmd "-Dtest=SaspayPayoutPilotPolicyTests,SaspayMerchantPayoutProviderTests,MerchantPayoutFeeBudgetTests,UnifiedMerchantPayoutIntegrationTests,MerchantSettlementIntegrationTests" test
cd ..
python -m unittest discover -s tools -p "test_saspay_diagnostics.py"
cd Frontend
npm test
npm run build
```

## Exception LIVE limitée à une seule vente directe Wave

Le verrou global de production demeure fermé pour toutes les opérations non
pilotes. Un **nouveau verrou LIVE distinct**, désactivé par défaut, autorise
uniquement la commande directe choisie (pas les groupages, pas Orange Money) :

```dotenv
SASPAY_PAYOUT_PILOT_ENABLED=false
SASPAY_PAYOUT_PILOT_ORGANIZATION_ID=
SASPAY_PAYOUT_PILOT_MAX_XOF=1000
SASPAY_PAYOUT_LIVE_PILOT_ENABLED=false
SASPAY_PAYOUT_LIVE_PILOT_DIRECT_ORDER_ID=
```

La commande doit exister dans `direct_orders` pour cette boutique, porter un
encaissement `SASPAY` réconcilié et rester `COMPLETED`. Le montant brut
ne peut dépasser 1 000 XOF ou le disponible réel de cette vente. Le seul réseau
sortant autorisé pendant ce pilote est `WAVE`. Une autre boutique, un autre
identifiant de commande, une destination Orange Money, une commande de groupage
et un montant fractionnaire ou supérieur au plafond sont refusés dans les
transitions de devis/réservation et de nouveau **à la frontière provider**.

Le code ne libère aucune sortie d'argent à lui seul : les interrupteurs
marchands et SasPay sont indépendants, l'URL/secret de webhook, la destination
vérifiée, les droits de l'acteur, l'absence d'autre retrait pilote et le devis
DEDUCTED accepté restent nécessaires. Le mode LIVE dépend en plus
de l'activation explicite des options de sortie et des vérifications
`marketplace-authorized` et `beneficiary-proof-approved`, qui ne doivent
pas être affirmées sans validation du cas d'utilisation. Les frais exacts
et le compte SasPay sont revérifiés immédiatement avant le POST fournisseur.

**Ne jamais activer le verrou LIVE juste pour faire apparaître un solde
dans l'interface.** La valeur `SASPAY_PAYOUT_LIVE_PILOT_ENABLED=true` ne
doit être mise qu'après recette locale complète et consentement explicite à
un unique transfert réel, après contrôle de l'UUID de commande et du numéro
Wave masqué dans l'interface. Le fournisseur peut encore refuser la requête
et un statut incertain doit être reconcilié sans réinitialiser l'idempotence.

Pour obtenir l'UUID de la vente pilote, effectuer une requête locale
**SELECT uniquement**, jamais un UPDATE de statut ou de solde :

```sql
select d.id, d.order_number, d.status, c.provider, c.seller_principal
from direct_orders d
join provider_collections c
  on c.organization_id=d.organization_id
 and c.target_type='DIRECT_ORDER'
 and c.target_id=d.id
where d.organization_id='b95a0515-1bcf-4740-8417-c594d02f211e'
  and d.order_number='DIR-000000000021'
  and c.provider='SASPAY';
```

## Points à valider avant le premier retrait réel

- L'accord de SasPay sur les retraits via la clé LIVE du compte, les réseaux
  et le mode `DEDUCTED` pour le Sénégal, et les contraintes éventuelles d'IP.
- Les coûts réels et éventuels frais externes affichés au bénéficiaire.
- Le comportement exact des deux réponses GET de confirmation en LIVE.
- La politique de traitement des retraits interrompus, annulés, échoués ou
  nécessitant une intervention de l'opérateur.
- Une limite propre au pilote de retrait (par exemple 1 000 XOF) et les contrôles
  de disponibilité des fonds.
- Tous les tests backend, frontend, SQL et Playwright en local. GitHub Actions
  est temporairement indisponible, ce qui ne vaut pas validation automatique.

Ne pas activer `SASPAY_PAYOUTS_ENABLED` ou
`SASPAY_PAYOUT_LIVE_PILOT_ENABLED` sur la seule base de tests verts ou de
méthodes personnelles vérifiées dans le tableau de bord SasPay : les droits
API de versement à un tiers doivent être confirmés distinctement.

## Tests locaux sans argent réel (PowerShell, depuis la racine)

```powershell
git pull --ff-only origin saspay-payin-v1
$env:SPRING_PROFILES_ACTIVE = "test"
$env:SASPAY_ENABLED = "false"
$env:SASPAY_PAYOUTS_ENABLED = "false"
cd Backend
.\mvnw.cmd spotless:apply
.\mvnw.cmd "-Dtest=SaspayMerchantPayoutProviderTests,MerchantPayoutFeeBudgetTests,UnifiedMerchantPayoutIntegrationTests,MerchantSettlementIntegrationTests" test
.\mvnw.cmd verify
cd ..
python -m unittest discover -s tools -p "test_saspay_diagnostics.py"
python tools/check_migrations.py
cd Frontend
npm run lint
npm test
npm run build
npm run test:e2e
```

Exécuter uniquement avec une base de test isolée (Testcontainers), et des
fournisseurs simulés. Ne partager ni clé API, ni secret de webhook.
