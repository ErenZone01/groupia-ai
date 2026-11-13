# Retraits SasPay — implémentation technique de préparation (PR #86)

**État au 12 novembre 2026 : code ajouté, DÉSACTIVÉ ; aucun retrait réel effectué ni autorisé.**

L'encaissement direct `DIR-000000000021` a été confirmé en base.
Ce succès de pay-in **ne transfère pas les fonds à la vendeuse**.
Un payout SasPay part du wallet marchand qui détient les fonds,
et non de l'acheteuse : dans la configuration actuelle, il s'agit du
compte SasPay associé à Groupia. La plateforme doit obtenir
l'autorisation explicite de gérer des fonds pour des vendeuses
distinctes.

## Contrat SasPay appliqué

Documentation :
- https://docs.saspay.me/api-reference/payouts/initialize
- https://docs.saspay.me/api-reference/payouts/verify
- https://docs.saspay.me/api-reference/reference/networks

`SaspayMerchantPayoutProvider` utilise :
- `POST /api/v1/payouts/initialize/` avec `Idempotency-Key` UUID
  **déjà durablement réservé** dans `merchant_payouts`.
- corps `amount`, `currency=XOF`, `country=SN`,
  `customer.phone`, `recipient.msisdn`, `method`,
  `fee_charge_mode=ADD_ON`, `metadata.groupia_payout_id`
  et `metadata.groupia_reference`.
- réponse `201` : l'UUID de payout est retenu pour **vérification**,
  mais n'est **jamais** une preuve de fonds reçus.
- `GET /api/v1/payouts/{id}/verify/` : vérification indépendante
  de l'UUID de transaction, du marchand, type `RETRAIT`,
  direction `OUTBOUND`, devise, montant, frais, débit total et
  net exact. Une preuve de bénéficiaire et de réseau est nécessaire
  pour autoriser `SUCCEEDED`.
- aucune relance automatique d'un payout avec une **nouvelle clé**.
  En cas d'ACK perdu, la réservation reste bloquée ; la résolution
  doit conserver la même clé et investiguer l'état fournisseur.

La route standard `GET /payouts/{id}/verify/` documente `customer` et
`network` comme des **UUID** et actualise le statut auprès du gateway.
Le complément officiel `GET /transactions/{id}/` documente
`operation_msisdn` (numéro effectivement crédité sur un retrait) et
`network_code`, ainsi que les montants détaillés :
https://docs.saspay.me/api-reference/transactions/get

**Correctif de vérification dans la PR #86** : un payout `PENDING`, `FAILED`
ou `CANCELLED` est rapproché avec le seul endpoint payout, sans inventer de
preuve de réception. Pour `SUCCESS`, le backend appelle **aussi** le détail
de transaction, contrôle l'identité et les montants indépendamment sur
les deux réponses, leur cohérence, `operation_msisdn` et `network_code`
contre la destination sauvegardée. Une preuve incomplète, contradictoire
ou non vérifiable reste en revue sans libérer le solde. Aucun POST de
retrait n'est fait pendant la vérification.

Ce correctif repose sur les champs documentés mais nécessite toujours
une validation de contrat réelle avec le compte concerné, sans
transactions nouvelles pendant la recette initiale.

Le réseau `wave_sn` a été observé sur l'encaissement LIVE Wave,
mais son **support en payout** doit être validé séparément.
Le code Orange Money payout reste à confirmer.
Le serveur ne devine plus les réseaux : il exige explicitement
`SASPAY_PAYOUTS_WAVE_NETWORK` et
`SASPAY_PAYOUTS_ORANGE_NETWORK`, validés dans la liste
fournisseur et autorisés pour les retraits au Sénégal.
N'envoyer aucun transfert tant que ces permissions et codes
ne sont pas confirmés.

## Branchements à Groupia

Le moteur existant `MerchantPayoutTransactions` maintient
les destinations, permissions EXECUTE_PAYOUTS, versions,
concurrence et réservation du solde. `MerchantSettlementService`
appelle maintenant le fournisseur choisi via
`groupia.settlement.payout-provider` : Bictorys par défaut,
SasPay uniquement sur choix explicite. Il n'y a pas deux
gestionnaires de transactions financières concurrents.

`MerchantBalances` ne rend les encaissements SasPay éligibles
que lorsque le fournisseur de payout est SasPay, son compte est
strictement identique au compte pay-in et l'adaptateur signale
une autorisation effective. Les encaissements SasPay ne passent
jamais par le payout Bictorys.

**Évolution postérieure (12 novembre 2026) :** la migration additive 055
et le module `DirectMerchantBalances` ont étendu le moteur canonique
`merchant_payouts` aux ventes directes, en conservant les mêmes garanties de
compte fournisseur, preuve de règlement, réservations et historique que les
groupages. Cette capacité est **désactivée par défaut** via
`GROUPIA_DIRECT_MERCHANT_PAYOUTS_ENABLED=false`, indépendamment du verrou
des transferts SasPay réels. La recette complète de ce nouveau lot reste à
exécuter ; voir `UNIFIED_FUNDS.md`.

## Variables (toutes DÉSACTIVÉES)

```dotenv
GROUPIA_MERCHANT_PAYOUT_PROVIDER=BICTORYS
GROUPIA_MERCHANT_PAYOUTS_ENABLED=false
SASPAY_PAYOUTS_ENABLED=false
SASPAY_PAYOUTS_MARKETPLACE_AUTHORIZED=false
SASPAY_PAYOUTS_BENEFICIARY_PROOF_APPROVED=false
SASPAY_PAYOUTS_COMPANY_FEE_BUDGET_APPROVED=false
SASPAY_PAYOUTS_WAVE_NETWORK=
SASPAY_PAYOUTS_ORANGE_NETWORK=
```

La présence d'une clé `SASPAY_API_KEY` LIVE qui encaisse ne
prouve **pas** un scope payout, un whitelist IP ni un mandat
marketplace. Une autorisation pour les fonds de Groupia ne vaut
pas autorisation pour les vendeuses.

**La libération production est aussi bloquée dans le code** :
`PRODUCTION_PAYOUT_RELEASED=false` dans l'adaptateur SasPay,
et cette constante n'est pas une propriété d'environnement.
Le test `productionWiringCannotReleaseRealMoneyJustByFlippingEnvironmentFlags`
vérifie que même tous les drapeaux mis à true ne peuvent
déclencher un retrait LIVE. Les tests réseau utilisent un
adaptateur à transport loopback, jamais la clé LIVE du vendeur.

**Ne pas passer ces drapeaux à true maintenant.**
L'approbation doit couvrir :
1. KYC et permissions de payout `PAYOUT/BOTH`, IP sortante stable
   et whitelistée, preuve du titulaire/destinataire ;
2. conditions marketplace (compte Groupia centralisé vs
   comptes vendeuses connectés) et réglementation applicable ;
3. bénéficiaire exact et code réseau vérifiables dans la réponse
   GET du fournisseur, ou preuve serveur autorisée distincte ;
4. **frais de payout séparément financés par Groupia**.
   `ADD_ON` prélève le principal + les frais du wallet émetteur :
   il faut un budget réel, des réservations et une comptabilité
   distincte. Les paramètres d'approbation seuls ne créent pas
   cette comptabilité ; l'activation ne doit pas précéder sa
   livraison et ses tests de concurrence ;
5. rapprochement du coût réel du retrait, reprise après timeout
   et procédure de remboursement / erreur de bénéficiaire.

Le retrait et le remboursement client sont des opérations
financières distinctes. Aucun remboursement automatique SasPay
n'est intégré par ce lot.

## Historisation des frais vérifiés (migration 056, à tester localement)

Le provider SasPay transmet désormais le `client_fee` vérifié dans
`MerchantPayoutProvider.Proof.providerFee`. Une preuve `SUCCEEDED` sans frais
valides (y compris 0 XOF si réellement facturé) n'autorise pas le statut
`SUCCEEDED` dans Groupia. Le moteur enregistre auparavant le principal,
le coût réel du fournisseur et leur somme dans
`merchant_payout_provider_fees` (migration additive 056). Le SQL et la
relecture rendent l'écriture idempotente, rejettent tout montant divergent
et protègent cette preuve contre UPDATE/DELETE. Le principal de la vendeuse
n'est jamais diminué par cette écriture. Les scénarios synthétiques
d'autres fournisseurs restent compatibles avec l'ancien constructeur de preuve.

### Budget séparé et réservations (migration 057, en attente de tests)

Le système conserve maintenant un journal append-only
`saspay_company_fee_events` par **compte marchand SasPay**, et non par
boutique. Il distingue :

- `FUNDING` : apport de Groupia confirmé indépendamment ; aucune API cliente
  ni configuration d'environnement ne crée automatiquement ces apports ;
- `RESERVE` : plafond de frais bloqué AVANT l'initiation d'un retrait ;
- `SETTLE` : coût réel validé sur la réponse SasPay, inférieur ou égal
  à la réserve ;
- `RELEASE` : prévu pour la libération après justification d'une
  non-exécution définitive ; aucune libération automatique n'est activée.

La réservation se fait sous verrou transactionnel global au compte marchand.
Les autres boutiques ne peuvent donc pas réutiliser simultanément le même
budget de Groupia. Après un timeout, la réserve reste bloquée jusqu'à
rapprochement, sans réessayer de transfert avec une nouvelle clé.
Tout le journal est non modifiable et supporte les répétitions idempotentes.

`SASPAY_PAYOUTS_APPROVED_FEE_CEILING_XOF=0` est volontairement la valeur
par défaut. Un plafond non nul ne constitue **ni un devis SasPay, ni un
justificatif d'apport de fonds**. Le moteur refuse tout retrait tant que
le plafond ou l'apport certifié manquent. Les fonds encaissés pour les
vendeuses ne peuvent jamais être déclarés `FUNDING`.

**Reste à livrer avant le réel :** méthode d'inscription des vrais apports
Groupia *après vérification de la transaction d'approvisionnement*, preuve
d'origine des fonds, confrontation au solde du wallet SasPay, validation
du tarif effectif du réseau pour le retrait (le plafond n'est pas un tarif),
et procédure d'approbation de sortie d'une réserve en cas d'échec définitif.
Le paramètre `PRODUCTION_PAYOUT_RELEASED=false` demeure inchangé. Cette
migration ne crédite aucun compte et ne déclenche aucun retrait.

Commandes ciblées pour tester le nouveau HEAD avec PostgreSQL/Testcontainers
isolés (jamais sur la base ou les clés LIVE) :

```powershell
git pull --ff-only origin saspay-payin-v1
cd Backend
$env:SPRING_PROFILES_ACTIVE = "test"
$env:SASPAY_ENABLED = "false"
$env:SASPAY_PAYOUTS_ENABLED = "false"
.\mvnw.cmd spotless:apply
.\mvnw.cmd "-Dtest=UnifiedMerchantPayoutIntegrationTests,MerchantSettlementIntegrationTests,SaspayMerchantPayoutProviderTests" test
.\mvnw.cmd verify
cd ..
python tools/check_migrations.py
```

## Diagnostic des réseaux payout et frais (lecture seule)

Depuis la racine du dépôt, après avoir mis à jour la branche et configuré
localement **sans publier la clé** :

```powershell
python tools/saspay_diagnostics.py --env-file Backend/.env --payout-amount-xof 1000
```

Ce diagnostic vérifie les codes `SASPAY_PAYOUTS_WAVE_NETWORK` et
`SASPAY_PAYOUTS_ORANGE_NETWORK` contre les tarifs SN/XOF renvoyés
par `GET /pricing/my-rates/`, distingue `payout.available`,
`ADD_ON` et `DEDUCTED`, et affiche uniquement une **estimation**
du débit wallet, des frais et du principal reçu pour un montant test.
Les erreurs d'arrondi ou de palier bloquent la compatibilité indicative.
Le script ne fait **aucun POST**, ne valide ni scope `PAYOUT`, ni IP,
ni fonds disponibles, ni autorisation de redistribuer les fonds de tiers.
Un tarif déclaré compatible ne donne aucun droit d'activation.

**Blocage conservé** : la comptabilité des frais `ADD_ON` financés
par Groupia n'a pas de réservation durable indépendante du principal
vendeur ni de rapprochement final dans ce lot. La preuve de bénéfice
ci-dessus ne règle pas cette comptabilité ; conserver
`PRODUCTION_PAYOUT_RELEASED=false` et les flags désactivés
jusqu'à validation de cette partie.

## Tests ciblés, sans argent réel

```powershell
git pull --ff-only origin saspay-payin-v1
cd Backend
$env:SPRING_PROFILES_ACTIVE = "test"
.\mvnw.cmd spotless:apply
.\mvnw.cmd spotless:check
.\mvnw.cmd "-Dtest=SaspayMerchantPayoutProviderTests,MerchantSettlementIntegrationTests,SaspayHostedContractTests" test
cd ..
python -m unittest discover -s tools -p test_saspay_diagnostics.py
```

Lancer ensuite tous les tests backend, lint, unit, build et
Playwright frontend. Ces contrôles n'ont pas été exécutés par
l'assistant via GitHub ; seuls des fichiers source ont été
publiés.

## Information client

Les écrans de vente directe, groupage, transport et abonnement
précisent désormais que le partenaire ou l'opérateur peut
afficher un montant final supérieur à la somme présentée
par SasPay. La cliente doit examiner le **total final affiché
dans Wave/Orange Money avant de confirmer**.

Le supplément Wave 46 XOF et l'écart présenté par Orange
Money restent non expliqués officiellement ; cette mention
n'équivaut pas à une autorisation d'ajouter arbitrairement des
frais ni à une garantie que le prix avant validation correspond
au débit final. Un rapprochement contractuel est encore requis.
