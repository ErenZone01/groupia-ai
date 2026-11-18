# SasPay — audit des contrats et incident DIR-21 (18 novembre 2026)

## Périmètre et statut

Audit statique du dépôt `main` après fusion de #92 et confrontation aux
pages publiques SasPay et au snapshot `Backend/docs/saspay/openapi.json`.
**Aucun accès aux clés LIVE, à la base réelle ou aux réponses fournisseurs
historiques dans ce rapport. Aucun mouvement de fonds, POST LIVE ou UPDATE SQL.**
Une compilation/CI verte n'est pas une attestation de conformité financière.

Sources :
- https://docs.saspay.me/llms.txt (la spécification OpenAPI est normative)
- https://docs.saspay.me/api-reference/introduction
- https://docs.saspay.me/api-reference/payments/checkout-create
- https://docs.saspay.me/api-reference/payments/verify
- https://docs.saspay.me/api-reference/payouts/initialize
- https://docs.saspay.me/api-reference/payouts/verify
- https://docs.saspay.me/api-reference/transactions/list
- https://docs.saspay.me/api-reference/transactions/get
- https://docs.saspay.me/api-reference/reference/my-rates
- https://docs.saspay.me/api-reference/wallet/balances-list
- https://docs.saspay.me/api-reference/webhooks

## Incident DIR-21

Commande `DIR-000000000021`, payout
`e1ecc9ae-89ab-449f-bc91-1a7e59f03a0e`, boutique
`b95a0515-1bcf-4740-8417-c594d02f211e`, 1000 XOF.
Historique : `REVIEW_REQUIRED/PAYOUT_MANUAL_RECOVERY_UNCERTAIN`,
`provider_transaction_id=NULL`. Des tentatives ont eu lieu les 14–15 novembre.
L'activation des décaissements du marchand (confirmée autour du 17–18 novembre)
rend les refus d'autorisation historiques plausibles, **mais ne prouve pas
qu'aucun POST de récupération n'a été transmis**.

Le cas DIR-24 a été clôturé via
`/administration/recorded-rejection` après preuve d'un refus HTTP 403
`payout_not_enabled`. Il a ensuite été réinitié en nouvelle intention et payé.
Le succès LIVE du nouveau payout DIR-24 a exigé #92 pour accepter
`TRANSFERT/OUTBOUND` et `DEDUCTED`, avec preuve double GET.
**Ne pas copier cette clôture sur DIR-21** : l'endpoint refuse
`PAYOUT_MANUAL_RECOVERY_UNCERTAIN` volontairement.

### Diagnostic non destructif

Lire l'état Groupia dans une connexion SQL **en lecture seule** :

```sql
SELECT id, organization_id, status, reconciliation_required, reconciliation_reason,
       provider, provider_account_reference, provider_transaction_id, amount,
       currency, requested_at, version
FROM merchant_payouts
WHERE id = 'e1ecc9ae-89ab-449f-bc91-1a7e59f03a0e';
```

Sur la machine de confiance disposant de `Backend/.env`, depuis la racine :

```powershell
python tools/saspay_inspect_unlinked_payout.py --env-file Backend/.env --from-utc 2026-11-14T00:00:00Z --to-utc 2026-11-17T23:59:59Z --expected-amount-xof 1000
python -m unittest discover -s tools -p test_saspay_inspect_unlinked_payout.py -v
```

L'outil ne fait **que** `GET /transactions/` en HTTPS, avec filtres
`merchant`, `flow_direction=OUTBOUND`, `currency=XOF` et dates.
Il balaie les deux types `RETRAIT` et `TRANSFERT`, refuse les redirections,
contrôle l'origine des URLs de pagination, reconstruit les URLs, borne la réponse
et ne divulgue ni clé, numéro, bénéficiaire, email, ni corps JSON.
Le résultat est une liste de **candidats**, jamais une preuve de règlement.
Zéro candidat, même après pagination complète, **n'est pas une preuve de non-exécution**.
Le fournisseur documente `search` sur référence/identité client, pas sur
les métadonnées Groupia : ne pas l'utiliser comme filtre de preuve.

Si candidat(s), utiliser les deux GET du diagnostic
`tools/saspay_inspect_payout.py` avec les valeurs économiques attendues.
Confronter le fournisseur aux logs de **toutes** les tentatives et à la clé
d'idempotence originale lue uniquement sur canal sûr. Une liaison de référence
perdue doit être autorisée, auditée, verrouillée et testée, jamais appliquée via
une modification SQL directe.

Si aucun candidat, demander une attestation **explicite** de SasPay :
- chaque tentative reçue ou non, HTTP/code métier, date et clé idempotente ;
- ID de transaction éventuel, statut gateway, debit, compte bénéficiaire ;
- existence d'un traitement `PENDING` ou d'une exécution différée ;
- preuve que l'ensemble des tentatives est définitivement non exécuté.

Avec cette preuve, la procédure administrative existante `CLOSE_NON_EXECUTED`
peut être évaluée si un dossier `PROVIDER_ATTESTATION` est préparé selon
`ReviewedPayoutEvidence` et les garde-fous de
`PayoutAdministrationService.decide`. Cette procédure ne doit pas être
simplifiée en une attestation utilisateur non vérifiée.
Dans tous les autres cas conserver la réserve ; aucun nouveau POST de reprise
ne sert de diagnostic.

## Matrice de traçabilité API

Statuts : **OK** = comportement constaté dans le code, **PARTIEL** = couverture
incomplète, **HORS PÉRIMÈTRE** = non invoqué par Groupia. Les réussites citées
sont des propriétés statiques ou des tests simulés, pas des résultats de
requêtes LIVE.

| Opération / contrat | Implémentation | Verdict |
| --- | --- | --- |
| `GET /pricing/my-rates/` 200 ; pays/réseau, tiers, pourcent, fixe, plancher/plafond, frais | `SaspayPricing` et `SaspayPayoutPreflight` valident disponibilité, ambiguïté, précision, devise et option | OK pour XOF/SN ; autres pays non ouverts |
| `GET /merchant-balances/` 200 paginé ; compte, pays, devise, liquidité | `SaspayMerchantPayoutProvider.merchantWalletPages` + `checkWallet` ; pagination exhaustive ou refus | OK, limite volontaire et fail-closed |
| `POST /checkout-sessions/` 201 ; montant, devise, pays, nom/email, fee mode, retour | `SaspayPaymentProvider.initiate` ; URL du checkout contrôlée, session distincte du payin, POST non rejoué automatiquement | OK pour flux courant ; non-idempotent, audit crash/recovery nécessaire |
| `GET /checkout-sessions/` 200 paginé | `SaspayClient.hostedCheckoutSessions` et `recoverHostedCheckout`, limite 3 pages ; aucun POST si session perdue | PARTIEL : limite de récupération, intervention opérateur si épuisement non prouvable |
| `GET /checkout-sessions/{id}/` 200 / 404 ; métadonnées et montant | `SaspayPaymentProvider.verify` contrôle ID, marchand, référence Groupia, devise et montant | OK pour sessions connues |
| `GET /checkout-sessions/{id}/status/` 200 / 404 ; PENDING, PAID, CANCELLED, EXPIRED | Statut de session distinct du statut de transaction ; PAID nécessite GET paiement | OK pour statuts énumérés ; inconnu = refus |
| `GET /payments/{id}/verify/` 200 / 404 ; INBOUND/PAIEMENT, ADD_ON et identité | `SaspayPaymentProvider.verify` + `verifyLegacySoftpay` | OK pour succès ; détail métier des erreurs amélioré |
| `POST /payments/softpay/` 201 ; clé d'idempotence | Utilisé uniquement pour anciennes opérations ; nouvelle commande utilise le checkout hébergé | HORS parcours neuf, vérification historique conservée |
| `POST /payouts/initialize/` 201 ; Idempotency-Key stable, customer.phone, recipient.msisdn, SN/XOF, `DEDUCTED` | `SaspayMerchantPayoutProvider.initialize` précontrôle et enregistrement de tentative | OK pour preuve statique ; éligibilité fournisseur nécessaire |
| `GET /payouts/{id}/verify/` 200 / 404 | `verifyExisting` : statut, ID, marchand, brut/frais/débit/net | OK, insuffisant seul pour un succès |
| `GET /transactions/{id}/` 200 / 404 | Deuxième preuve pour réussite, destinataire, réseau, type RETRAIT ou TRANSFERT, OUTBOUND | OK pour #92 ; surveiller tout nouveau type |
| `GET /transactions/` 200, curseur, filtres | Nouveau `tools/saspay_inspect_unlinked_payout.py` (lecture seule pour les incidents) | OK hors LIVE non exécuté ; recherche seule ne prouve rien |
| Webhook `transaction.*` signé | HMAC-SHA256 sur octets bruts, horodatage ±300s, reçu durable et dédoublonné | OK pour sécurité de base ; conformité opérationnelle à tester sur livraison |
| Webhook `transaction.cancelled` entrant | Reconnaissance ajoutée dans `SaspayPaymentProvider` | Corrigé dans cette branche ; tests CI nécessaires |
| Webhook de retrait RETRAIT/TRANSFERT | `SaspayPayoutWebhooks` conserve seulement une indication, vérification GET obligatoire | OK ; une notification seule ne libère ou paie rien |
| Webhook `settlement.*` | Documentation signale un payload non stabilisé ; non utilisé comme preuve financière | Correctement exclus des décisions |
| `POST /payments/{id}/confirm-otp/`, `retry/` | Groupia ne les appelle pas dans le flux hosted courant | HORS PÉRIMÈTRE : aucun appel simulé en production |
| `POST /checkout-sessions/{id}/cancel/` | Groupia ne l'appelle pas via SasPay | HORS PÉRIMÈTRE |
| `GET /transaction-attempts/`, `transaction-status-logs/`, `webhook-logs/` | Non utilisés automatiquement | Piste d'investigation READ-ONLY, sans garantie de couverture historique |

## Réponses HTTP, codes métier et politique financière

| Réponse | Principe Groupia | Point contrôlé / restant |
| --- | --- | --- |
| 200/201 | Contrôler corps + identité + état ; initialisation ACK != paiement | OK ; `success=false` n'est jamais succès |
| 400 | Erreur de validation, POST potentiellement soumis selon endpoint | Code/famille gardé, pas de nouveau POST automatique |
| 401/403 | Authentification, autorisation, IP, désactivation payout | Payout : seuls refus 403 reconnus sont `REJECTED` ; anciens incertains nécessitent dossier |
| 404/410 | Ressource absente/expirée, jamais preuve générique de non-débit | Payin et payout conservateurs ; expliciter les sessions expirées |
| 409 | Collision d'idempotence ou d'état en cours | Ne jamais libérer ni réémettre automatiquement |
| 422 | Validation métier, ex. `insufficient_balance` | Codes payin allowlist ajoutés sans payload externe |
| 429 | Rate limit | Payin respecte `Retry-After` ; payout ne retry pas automatiquement ; améliorer scheduling borné |
| 5xx, timeout, JSON invalide | Issue inconnue pour tout POST susceptible d'être transmis | Fonds réservés et rapprochement, aucun retry aveugle |
| Statut inconnu | Refus de transition, pas de succès deviné | OK, ajout `CANCELLED` payin |
| Code fournisseur inconnu | `UNRECOGNIZED`, pas de message/code arbitraire en logs | Payout déjà contrôlé ; payin corrigé dans cette branche |

**Écarts et risques restant à surveiller**
1. **P0 — DIR-21** : non-exécution non démontrée et ID fournisseur perdu ;
   conserver la réserve, investiguer via scan GET + support SasPay.
2. **P1 — récupération de checkout** : la liste est volontairement bornée ;
   en cas de trop nombreuses sessions il faut un rapprochement fournisseur.
3. **P1 — couverture réelle des erreurs** : de nouvelles variantes de réponses ou
   de codes nécessitent des tests contractuels avec fixtures officielles ; les
   codes non reconnus restent fail-closed.
4. **P1 — exploitation Webhooks** : après la 5e livraison échouée, SasPay
   requiert un renvoi manuel depuis son dashboard ; planifier surveillance des
   reçus échoués côté Groupia.
5. **P2 — frais tiers** : Groupia garantit les frais indiqués par SasPay dans
   le devis et la preuve, pas des commissions externes Wave/OM non documentées.
6. **P2 — régionalisation** : XOF/SN est volontairement strict ; aucune
   extension UEMOA ni compatibilité réseau ne doit être présumée sans vérification
   pays/devise, KYC, capacité fournisseur et tests LIVE autorisés.

## Test et mise en production

Exiger tests backend (dont `SaspayContractTests` et
`SaspayMerchantPayoutProviderTests`), Spotless, frontend et sécurité,
six tests Python de scan en réseau simulé, et jobs CI verts avant fusion.
Ne pas modifier les variables de déblocage LIVE, ne pas ouvrir les payouts
généraux ni lancer de transfert lors de cet audit. Toute intervention sur
DIR-21 après obtention des preuves doit suivre les contrôles d'org, de version,
d'acteur, de double preuve et l'audit DB transactionnel existant.
