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

La route standard de vérification SasPay documente `customer` et
`network` comme des **UUID**, pas le téléphone final en clair.
Par conséquent notre validateur refuse de marquer un payout
`SUCCEEDED` sans champs de bénéficiaire et code réseau
**authentifiés par le fournisseur**. La validation écrite de ce
contrat de preuve est un blocage de mise en service, pas une
simple préférence d'interface.

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

**Limite fonctionnelle :** le moteur de solde et de versement
actuel est lié aux campagnes de **groupage**.
Le registre `provider_collections` couvre également les ventes
directes, mais aucun solde transférable de vente directe n'est
encore alloué à une demande `merchant_payout` canonique.
Ce lot **ne prétend donc pas couvrir les retraits des ventes directes**.
Un modèle de soldes commun direct/groupage, avec remboursements,
réservations et suivi par organisation, reste nécessaire.

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

## Tests ciblés, sans argent réel

```powershell
git pull --ff-only origin saspay-payin-v1
cd Backend
$env:SPRING_PROFILES_ACTIVE = "test"
.\mvnw.cmd spotless:apply
.\mvnw.cmd spotless:check
.\mvnw.cmd "-Dtest=SaspayMerchantPayoutProviderTests,MerchantSettlementIntegrationTests,SaspayHostedContractTests" test
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
