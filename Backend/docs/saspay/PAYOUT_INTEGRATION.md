# Retraits SasPay Groupia — politique DEDUCTED, PR #86

**État : implémentation en cours de recette locale ; aucun retrait LIVE autorisé.**
Le verrou compilé `PRODUCTION_PAYOUT_RELEASED=false` reste en place.
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

Ne pas activer `SASPAY_PAYOUTS_ENABLED` ni modifier le verrou production
en se basant seulement sur un statut vert des tests.

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
