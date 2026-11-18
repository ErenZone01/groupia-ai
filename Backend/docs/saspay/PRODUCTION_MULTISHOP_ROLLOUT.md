# SasPay — passage du pilote à une exploitation multi-boutiques (Groupia)

## But

Retirer les restrictions **de test** (une boutique, un checkout plafonné à 2 000 XOF,
un retrait limité à DIR-21/DIR-24, 1 000 XOF) **sans retirer les garanties financières** :
idempotence, validation serveur, séparation des boutiques, calcul du solde retirable,
réservation de l'argent, devis accepté, preuve fournisseur, GET indépendant, journaux
et gel des opérations contestées.

## Modes de fonctionnement

- **SANDBOX** : `sk_test_` utilise exclusivement `sandbox-api.saspay.me`.
- **LIVE pilote historique** : `SASPAY_LIVE_PILOT_ENABLED=true`, boutique unique,
  montant encaissé maximum 5 000 XOF. Conserver uniquement pour les vieux tests.
- **LIVE standard** : `SASPAY_PRODUCTION_ENABLED=true` et
  `SASPAY_LIVE_PILOT_ENABLED=false`, checkout hébergé disponible pour toute
  organisation dont le parcours Groupia autorise le paiement, avec plafond
  montant total client **par transaction**, paramétrable.
- **Retraits marchands standards** : `SASPAY_PAYOUT_GENERAL_ENABLED=true`,
  sans ciblage d'une commande mais avec droits `EXECUTE_PAYOUTS`, fonds Groupia
  réconciliés, devis, préflight tarifs et wallet, plafond **par retrait** et
  cumul **UTC par boutique et par jour**, appliqués sous verrou d'organisation.

**Les flags de passage en production sont désactivés par défaut**, et ne signifient
ni certification PCI, ni autorisation de PSP, ni contrat SasPay multivendeurs.
Activer uniquement après validation du partenaire sur la détention et le reversement
de fonds vers les bénéficiaires, puis une vérification de conformité adaptée au
modèle d'affaires (voir instruction BCEAO 001-01-2024).

## Exemple de configuration LIVE standard (à adapter après approbations)

```dotenv
SASPAY_ENABLED=true
SASPAY_API_KEY=<sk_live_secret>
SASPAY_WEBHOOK_SECRET=<webhook_secret>
SASPAY_MERCHANT_ID=<uuid_saspay>
SASPAY_CHECKOUT_HOSTS=checkout.saspay.me,pay.saspay.me
SASPAY_PRODUCTION_ENABLED=true
SASPAY_PRODUCTION_MAX_CHARGED_XOF=1000000
SASPAY_LIVE_PILOT_ENABLED=false

GROUPIA_MERCHANT_PAYOUT_PROVIDER=SASPAY
GROUPIA_MERCHANT_PAYOUTS_ENABLED=true
GROUPIA_DIRECT_MERCHANT_PAYOUTS_ENABLED=true
SASPAY_PAYOUTS_ENABLED=true
SASPAY_PAYOUTS_MARKETPLACE_AUTHORIZED=<true uniquement si confirmé>
SASPAY_PAYOUTS_BENEFICIARY_PROOF_APPROVED=<true uniquement si confirmé>
SASPAY_PAYOUTS_WAVE_NETWORK=<reseau confirme par SasPay>
SASPAY_PAYOUTS_ORANGE_NETWORK=<reseau confirme par SasPay>
SASPAY_PAYOUT_GENERAL_ENABLED=true
SASPAY_PAYOUT_MAX_PER_REQUEST_XOF=100000
SASPAY_PAYOUT_DAILY_MAX_XOF=500000
SASPAY_PAYOUT_PILOT_ENABLED=false
SASPAY_PAYOUT_LIVE_PILOT_ENABLED=false

SASPAY_PAYOUT_RECOVERY_ENABLED=false
GROUPIA_PAYOUT_ADMINISTRATION_HISTORICAL_UNLINKED_ENABLED=false
```

Les deux modes encaissement LIVE sont **mutuellement exclusifs** et les plafonds
de production doivent être des entiers XOF positifs : encaissement total client
`<= 10 000 000`, retrait `<= 5 000 000`, quota quotidien `<= 25 000 000`.
Les défauts (1 000 000, 100 000, 500 000) servent de limites de
prudence et **ne constituent pas des limites commerciales promises par SasPay**.

## Parcours garantis

1. **Vente directe / groupage / abonnement** : obligation et tentative Groupia
   persistées, création de session SasPay, redirect HTTPS allowlist, vérification
   de session ET transaction (montant, marchand, nature, devise, frais et net).
   Webhook signé et horodaté : notification durable, **pas un acquittement monétaire**.
2. **Retrait direct / groupage** : solde éligible vérifié, objet verrouillé,
   quota journalier et plafond, destination confirmée, devis réel SasPay
   accepté et toujours valide, clé d'idempotence unique, POST envoyé **une
   seule fois**, réconciliation GET, puis preuve supplémentaire de transaction.
3. **Incident** : toute erreur de transport, timeout, HTTP 5xx ou réponse ambiguë
   sur un POST entraîne enquête/gel de la réservation, **pas de replay
   automatique** ni une libération forcée ; les clôtures admin sont traçables.

**IMPORTANT** : le checkout hébergé SasPay exige `customer_email` et
`customer_name` d'après l'OpenAPI actuelle. La vente invitée sans compte peut
rester possible, mais le checkout doit recueillir ces coordonnées, sans en inventer.
Le mode `fee_charge_mode=ADD_ON` peut être ignoré par SasPay si
`ALLOW_CLIENT_OVERRIDE` n'est pas activé pour le marchand : la vérification
Groupia refusera alors un résultat incohérent. Configurer l'option fournisseur
avant commercialisation.

## Risques et contrôles à finaliser avant généralisation commerciale

- Le compte SasPay est un seul compte marchand ; isoler les ledgers
  des boutiques ne prouve pas que les fonds de chacune sont juridiquement
  distincts : mandat de marketplace et processus de sauvegarde des fonds
  à valider avec SasPay et un conseil compétent.
- Confronter quotidiennement soldes / transactions / tentatives SasPay avec
  le livre des obligations Groupia. Alerter sur différence, webhook non livré,
  anomalie de frais ou transfert orphelin ; geler les retraits incertains.
- L'API SasPay ne documente pas de remboursement natif : ne pas considérer
  une déclaration Groupia de remboursement externe comme un débit SasPay.
- Tester en recette et en LIVE autorisé les retraits Wave et Orange Money,
  les groupages, abonnements, événements dupliqués, pannes, concurrence,
  changements de destination, paiements tardifs et relecture après crash.
- Rotation des secrets, habilitations des opérateurs et MFA, supervision,
  sauvegardes et restaurations PostgreSQL, contrôle des journaux (données
  personnelles), rétention et suppression.
- Aucun autre pays UEMOA n'est ouvert automatiquement par ce changement ;
  pays/devise/KYC/réseaux nécessitent une validation distincte.

## Validation

`./mvnw verify` (dont Spotless, architecture, migrations et PostgreSQL),
`python -m unittest discover -s tools -p 'test_*.py'`,
frontend lint/tests/build et Playwright. L'exécution LIVE réelle n'est pas
simulable par CI ; elle doit être réalisée avec clés protégées et montants
contrôlés, puis réconciliée avant généralisation.

Références : https://docs.saspay.me/api-reference/introduction,
https://docs.saspay.me/api-reference/payments/checkout-create,
https://docs.saspay.me/api-reference/payouts/initialize,
https://www.bceao.int/fr/reglementations/instruction-ndeg001-01-2024-du-23-janvier-2024-relative-aux-services-de-paiement
