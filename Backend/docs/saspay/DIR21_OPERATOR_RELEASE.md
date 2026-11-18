# DIR-21 — clôture exceptionnelle supervisée, sans appel SasPay POST

## Portée

Destiné à la demande d'un propriétaire/opérateur qui **atteste** que son historique
DIR-21 n'a pas été exécuté chez SasPay et accepte expressément le risque d'un
versement tardif. La preuve disponible est un scan SasPay en lecture seule,
exécuté le 18 novembre 2026, couvrant le 14–17 novembre, avec une transaction
candidate **distincte (DIR-24)**. Ce constat n'est pas une preuve absolue
de non-exécution et ne doit jamais être enregistré comme une attestation SasPay.

L'endpoint ne fait **aucune requête fournisseur**, ni GET ni POST, et n'envoie
aucun retrait. Son unique effet financier est une transition Groupia
`REVIEW_REQUIRED → NON_EXECUTE` qui rend les 1 000 XOF précédemment réservés
à nouveau disponibles **si tous les gardes passent**.

Cette exception volontaire est plus permissive que le chemin normal
`CLOSE_NON_EXECUTED`, fondé sur des preuves externes : elle est
**strictement désactivée par défaut**, réservée à un **seul ID de payout
configuré**, avec trace `OWNER_RISK_ACCEPTANCE`, version optimiste,
verrouillage de l'organisation et transaction DB atomique. L'opérateur
assume la décision et le risque de paiement tardif. Jamais exposée à
la vendeuse ni à un acteur non autorisé.

## Prérequis de déploiement (temporaire)

Après tests CI verts et fusion du correctif, déployer le backend contenant
cette route, avec les variables :

```dotenv
GROUPIA_PAYOUT_ADMINISTRATION_ENABLED=true
GROUPIA_PAYOUT_ADMINISTRATION_OPERATOR_IDS=<uuid-de-l-operateur>
GROUPIA_PAYOUT_ADMINISTRATION_HISTORICAL_UNLINKED_ENABLED=true
GROUPIA_PAYOUT_ADMINISTRATION_HISTORICAL_UNLINKED_PAYOUT_ID=e1ecc9ae-89ab-449f-bc91-1a7e59f03a0e
```

Les permissions d'organisation `EXECUTE_PAYOUTS` et `MANAGE_SETTLEMENTS`
restent requises. Le contrôleur refuse toute autre intention, tout autre
marchand, autre devise, autre montant, tout retrait après activation
SasPay au **17 novembre 2026 à 05:53:24 UTC**, toute transaction
identifiée et tout état déjà finalisé. Il exige **WAVE**, `SASPAY`,
`REVIEW_REQUIRED`, `PAYOUT_MANUAL_RECOVERY_UNCERTAIN`, 1000 XOF,
une version exacte et l'absence d'incident de rapprochement.

## Étape 1 : consulter état/version (GET, zéro mutation)

```http
GET /api/organizations/b95a0515-1bcf-4740-8417-c594d02f211e/merchant-payouts/e1ecc9ae-89ab-449f-bc91-1a7e59f03a0e/administration/historical-unlinked
Authorization: Bearer <jeton-operateur-autorise>
```

Exemple de réponse (la version doit venir de ta propre instance) :

```json
{
  "payoutId": "e1ecc9ae-89ab-449f-bc91-1a7e59f03a0e",
  "status": "REVIEW_REQUIRED",
  "reconciliationReason": "PAYOUT_MANUAL_RECOVERY_UNCERTAIN",
  "version": 2,
  "eligible": true
}
```

N'invente pas la version `2` : copie la valeur réellement renvoyée.
Si `eligible=false`, **arrêter** : ne pas modifier directement SQL ni
changer les contrôles pour passer.

## Étape 2 : clôturer la réservation (POST administratif, aucun payout SasPay)

```http
POST /api/organizations/b95a0515-1bcf-4740-8417-c594d02f211e/merchant-payouts/e1ecc9ae-89ab-449f-bc91-1a7e59f03a0e/administration/historical-unlinked
Authorization: Bearer <jeton-operateur-autorise>
Content-Type: application/json

{
  "expectedVersion": 2,
  "evidenceReference": "SASPAY-READONLY-SCAN-20261118",
  "reason": "Proprietaire confirme absence de virement DIR-21 ; scan GET du 18 novembre distingue DIR-24 et accepte le risque residuel.",
  "ownerConfirmsNeverExecuted": true,
  "acceptsDuplicatePayoutRisk": true,
  "confirmed": true
}
```

Ne mettre `ownerConfirmsNeverExecuted=true` que si c'est ta
propre confirmation réelle. L'action crée une **preuve d'acceptation de
risque par l'opérateur**, non une attestation fournisseur.

Succès : `status=NON_EXECUTE` et `authorizationId` audit unique.
Un POST identique rejoué redonne le même audit. Aucune opération
`/payouts/initialize/` n'est invoquée. L'ancienne clé d'idempotence,
son historique et la destination restent intacts. Les réserves sont
libérées par le mécanisme comptable habituel de Groupia.

## Étape 3 : nouvelle tentative séparée

Désactiver **immédiatement** les deux paramètres temporaires :

```dotenv
GROUPIA_PAYOUT_ADMINISTRATION_HISTORICAL_UNLINKED_ENABLED=false
GROUPIA_PAYOUT_ADMINISTRATION_HISTORICAL_UNLINKED_PAYOUT_ID=
```

Conserver l'administration générale seulement si elle est nécessaire.
Ne pas activer le chemin `recover-pilot` historique :
`SASPAY_PAYOUT_RECOVERY_ENABLED=false`.

Pour un **nouveau** retrait de DIR-21 avec le pilote LIVE
restreint à une commande, le serveur doit cibler le
`directOrderId` réel de DIR-21 et non celui de DIR-24 :

```dotenv
SASPAY_PAYOUT_LIVE_PILOT_ENABLED=true
SASPAY_PAYOUT_LIVE_PILOT_DIRECT_ORDER_ID=<uuid-reel-dir21>
```

Respecter les autres paramètres du pilote (boutique autorisée, plafond
1000 XOF) : ne pas ouvrir les retraits généraux simplement pour ce test.
Sur le parcours vendeur, demander un **nouveau devis** de 1000 XOF,
vérifier les frais `DEDUCTED`, puis accepter une **nouvelle intention**
avec une **nouvelle clé d'idempotence**. Ne jamais relancer l'ancien
payout, ni rejouer sa clé. Contrôler ensuite le versement avec
`GET /payouts/{id}/verify/` et `GET /transactions/{id}/`.
La documentation fournisseur n'exige aucune route de déblocage :
celle-ci est propre au registre de réservations Groupia.

## Après opération

Le ledger conserve un audit immuable de l'acceptation du risque.
En cas de preuve tardive d'un ancien versement, les contrôles de résolution
de Groupia gèlent de nouveaux décaissements de cette boutique plutôt que
payer silencieusement deux fois. Ne pas masquer ce risque.
