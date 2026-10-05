# Régularisation des trop-perçus transport — V1

## Parcours et invariants

`CREDIT_DUE → PICKED_UP → restitution réussie → SETTLED → Order COMPLETED`.

Un crédit avant retrait reste visible et ne bloque pas la remise physique. Sa régularisation est refusée avant PICKED_UP avec `CREDIT_NOT_STABLE_FOR_REFUND`. Après retrait, les corrections ordinaires du transport sont déjà verrouillées par Distribution V1.

Le serveur détermine le crédit complet sous verrou Order. Aucun montant, devise, téléphone, acteur, paymentId ou identifiant métier fourni dans le corps HTTP n'est accepté. Les UUID du chemin sont contrôlés par l'autorisation et le scope de la commande. XOF entier et montant positif sont obligatoires. Aucun remboursement partiel, portefeuille ou réaffectation à une autre commande.

## Propriété des données et autorisations

`payment` possède Refund, ses preuves et ses transitions ; `ordering` calcule le crédit et autorise la réservation après retrait. Les références entre modules restent des UUID, sans relation JPA ni accès à un repository externe.

`MANAGE_REFUNDS` est réservée à OWNER et MANAGER ; OPERATOR et VIEWER ne peuvent pas initier ni réconcilier une sortie d'argent. Utilisateur, membership et organisation doivent être actifs. Les lectures privées exigent VIEW_PAYMENTS. Le tableau Distribution transmet seulement les capacités et les résumés nécessaires à l'opérateur.

Refund conserve montant/devise/purpose SHIPPING_CREDIT, calcul transport, boutique/commande, méthode, référence métier déterministe, référence marchande GPR-, clé d'idempotence provider, destinataire et nom snapshotés, acteur/date serveur, provenance, statut, références/preuves et version. Le Payment SUCCEEDED original et les PaymentRequest historiques restent inchangés.

## Deux provenances

### BICTORYS_PAYOUT

POST `/pay/v1/payouts?payment_type=wave_money|orange_money`, `transactionType=refund`, XOF, pays SN. Destinataire : téléphone et nom snapshotés sur la commande. Le numéro doit être sénégalais. L'en-tête X-API-Key utilise la clé privée, `merchant.secretCode` le code marchand et `idempotency-key` la clé persistée. Le secret webhook est distinct.

Références officielles : [création d'un payout](https://docs.bictorys.com/reference/createpayout), [guide d'intégration](https://docs.bictorys.com/docs/integration). Le contrat de référence autorise refund ; l'exemple générique du guide utilise payment. Groupia utilise explicitement refund pour cette restitution.

Une réponse HTTP 200/201/202, même avec un statut de succès, reste une initiation : elle ne solde rien. L'identifiant renvoyé est conservé pour corrélation. Les erreurs réseau, réponses ambiguës, 4xx ou 5xx restent prudentes : REVIEW_REQUIRED, crédit ouvert, aucun nouvel envoi automatique. Seul un statut final issu d'une preuve authentifiée et entièrement corrélée peut réussir la restitution.

Les payouts sont désactivés par défaut. L'adaptateur reste limité au sandbox, comme le paiement entrant existant.

### MANUAL_EXTERNAL

OWNER/MANAGER atteste une restitution **déjà réalisée** : Wave, Orange Money, espèces, virement ou autre. Une référence est obligatoire pour les modes non espèces. Pour les espèces, une note de confirmation est obligatoire ; la référence est facultative. Une attestation explicite est requise dans tous les cas.

Cette opération est comptabilisée comme une restitution réussie déclarée par la boutique, sans appel provider. Son origine reste MANUAL_EXTERNAL. Le suivi cliente dit « régularisés par la boutique » et « Régularisation déclarée par la boutique », sans prétendre à une vérification Bictorys. Les notes, acteurs, références internes et destinataires ne sont pas exposés au suivi ; la référence externe affichée à la gérante est masquée.

## Transaction, concurrence et idempotence

1. Autorisation → verrou Order → verrous des obligations transport existantes → recalcul de finance → validation PICKED_UP et crédit → réservation Refund → commit.
2. Une transaction courte prend possession de l'envoi : CREATED → PENDING incertain → commit.
3. Le réseau intervient après commit, sans verrou Order ni transaction financière ouverte.
4. Réponse d'initiation et preuves finales mettent à jour Refund dans leurs propres transactions.

La référence métier est `SHIPPING_CREDIT:<orderId>:<calculationId>:<amount>`. Le modèle V1 impose **un seul remboursement logique par commande**, y compris après un échec certain. Les doubles clics avec les mêmes paramètres retrouvent l'opération ; des paramètres différents sont refusés. Une seconde restitution avec une autre méthode n'est pas autorisée lorsqu'une opération existe.

Décision prudente : pas de remplacement automatique d'un Refund FAILED/CANCELLED dans cette V1. La réconciliation peut encore reconnaître un succès tardif. Permettre un nouvel envoi après un échec certain nécessitera un modèle de tentatives séparées et une règle de traitement des succès tardifs, sans réécrire les snapshots de cette opération.

Les identifiants de transaction sont uniques dans les refunds. Un verrou de corrélation partagé avec les paiements entrants empêche d'affecter simultanément le même identifiant à un pay-in et un payout. Les succès sont monotones et immuables ; les replays ne republient pas l'événement de règlement.

Un crash après réservation ou pendant l'envoi laisse une opération consultable dans la file de vérification. L'application ne renvoie pas aveuglément l'argent. Si aucun identifiant provider n'a été reçu, la réconciliation reste en attente d'un webhook complet ou d'une investigation auprès du provider ; elle n'invente aucune preuve.

## Webhooks et réconciliation

Le endpoint existant `POST /api/payment/webhooks/bictorys` route les messages payout/refund ou les références GPR- vers le flux de remboursement. Une référence GP- de paiement entrant accompagnée d'un type refund reste rejetée par le parser entrant, sans observation financière ni règlement.

Le routage n'est pas une preuve. Pour réussir : secret valide, provider/account attendus, direction payout/refund explicite, transaction et référence marchande exactes, montant/devise, destinataire snapshoté et statut final doivent concorder. Une référence GPR- seule, un statut seul ou le résultat faible du endpoint /status ne suffisent jamais.

Le parser prend en charge une observation racine ou une enveloppe événement payout/refund avec data. Les éléments contradictoires, statuts inconnus, timestamps invalides, champs incomplets et doublons JSON sont refusés. Un montant signé sortant est normalisé après identification de la direction. Les variantes non documentées restent à vérifier jusqu'à validation de leur contrat réel.

Les receipts gardent le fingerprint, l'authenticité, l'observation normalisée lorsqu'elle existe et le résultat de traitement. Aucune valeur de secret, aucun payload brut ni données client ne sont journalisés. Les observations normalisées restent privées. Les receipts inconnus/incohérents sont en REVIEW_REQUIRED.

La réconciliation manuelle effectue une lecture ponctuelle de `GET /pay/v1/transactions/{id}` et exige les mêmes preuves complètes. Elle n'envoie jamais un nouveau payout. Une réponse pauvre ou incohérente maintient l'incertitude.

## Finance et clôture de la commande

```text
grossShippingPaid = initial shipping payé + SHIPPING_BALANCE effectivement réglés
netShippingPaid = grossShippingPaid - SHIPPING_CREDIT SUCCEEDED
delta = finalShippingAmount - netShippingPaid
```

Les tentatives CREATED/PENDING/FAILED/CANCELLED/REVIEW_REQUIRED ne sont pas déduites. Le total brut historique reste dans totalShippingPaidAmount ; successfulShippingCreditRefunds et netShippingPaid rendent la restitution explicite. Un dépassement des sources financières provoque une erreur, jamais une compensation silencieuse.

Refund SUCCEEDED publie `ShippingCreditRefundSettled` via le registre durable Spring Modulith dans sa transaction. Le consommateur ordering verrouille la commande, relit le Refund et le solde, puis complète uniquement une commande CONFIRMED et PICKED_UP devenue SETTLED. Il ne modifie pas pickedUpAt et ne republie pas OrderCommerciallyAccepted. Un rejeu conserve completedAt.

La clôture est éventuellement cohérente : le solde peut être réglé avant que le consommateur ait terminé la commande. Les publications échouées restent récupérables avec les opérations Modulith existantes décrites dans payment-operations.md.

## API privée

Sous `/api/organizations/{organizationId}` :

| Méthode | Route | Autorisation |
|---|---|---|
| GET | /orders/{orderId}/shipping-credit | VIEW_PAYMENTS |
| POST | /orders/{orderId}/shipping-credit | MANAGE_REFUNDS |
| GET | /refunds/review | VIEW_PAYMENTS |
| POST | /refunds/{refundId}/reconcile | MANAGE_REFUNDS |

Le corps de création contient uniquement `version, method, externalMethod, reference?, note?, confirmed`. Les champs inconnus sont rejetés. Le résultat expose les capacités serveur, la position financière et les restitutions. Les réponses privées sont no-store. La file de vérification est bornée à 100 opérations et inclut la commande concernée et un code de raison sûr.

## Schéma et immutabilité

019 crée payment_refunds et refund_webhook_receipts. Contraintes : montant positif entier, XOF, purpose, provenance/moyens cohérents, références et clé provider uniques, un remboursement logique par commande, identifiant provider unique, cohérence statut/date de réussite. Le trigger refuse la suppression des remboursements, la modification des snapshots et toute modification d'un remboursement réussi.

Les migrations 001–018 ne sont pas modifiées. 019 est elle-même immutable dès sa création. Aucune acceptation supplémentaire de checksum, aucun clearCheckSums, aucun reset ou backfill financier.

## Configuration

Ajouter les variables au véritable environnement local, sans committer leurs valeurs :

- GROUPIA_BICTORYS_PAYOUTS_ENABLED=false par défaut ;
- GROUPIA_BICTORYS_PRIVATE_KEY : clé privée sandbox test_secret-… ;
- GROUPIA_BICTORYS_MERCHANT_SECRET_CODE : code marchand ;
- BICTORYS_WEBHOOK_SECRET : secret webhook existant et distinct.

L'adaptateur entrant doit être activé/configuré via BICTORYS_ENABLED, BICTORYS_API_KEY et BICTORYS_API_URL. En mode payouts enabled, les secrets requis et timeouts sont validés au démarrage. Absents avec le flag disabled, ils ne bloquent pas le démarrage. Les représentations textuelles des propriétés et requêtes sont expurgées.

Les tests forcent les intégrations réelles désactivées et installent des adapters pointant exclusivement sur un serveur HTTP local. Aucun test Maven ne déclenche de payout sandbox réel.

## Recette manuelle

1. En environnement de test, créer un crédit transport avant retrait : montant visible, aucune régularisation possible.
2. Préparer puis remettre la commande : PICKED_UP, Order CONFIRMED, crédit toujours visible.
3. Vérifier OWNER/MANAGER et OPERATOR/VIEWER : seules les deux premières fonctions peuvent régulariser.
4. Payout disabled : ouvrir la modale, Bictorys indisponible, déclaration manuelle accessible.
5. Déclarer Wave/Orange Money sans référence : refus. Espèces sans note : refus.
6. Confirmer la restitution complète : net transport réglé, puis Order COMPLETED ; vérifier conservation des paiements, de pickedUpAt et du groupage DISTRIBUTING.
7. Suivi cliente : origine manuelle explicite, montant/moyen/date, aucun acteur ni note privée.
8. Avec des credentials sandbox explicitement configurés, initier un payout vers le numéro de la commande : PENDING ne réduit pas le crédit. Un vrai essai de sortie d'argent est une action manuelle distincte de la validation automatique.
9. Vérifier les receipts et la file de réconciliation pour un résultat incomplet. Un timeout ne permet pas un second envoi ni une substitution manuelle.
10. Une preuve cohérente SUCCEEDED règle le crédit ; son rejeu et un ancien statut PENDING ne créent ni nouveau règlement ni nouvelle clôture.
11. Tester clavier, 360 px, desktop, clair/sombre, erreurs réseau et double clic.
12. Appliquer 019 à une base isolée fraîche puis à une copie en 018 ; comparer les données et checksums historiques.

## Hors tranche

Clôture automatique GroupingCampaign.COMPLETED, remboursements partiels, portefeuille, autre bénéficiaire, remplacement d'une tentative échouée, ajustements financiers post-retrait et nouvelles notifications WhatsApp.

## Validation exécutée le 5 octobre 2026

| Commande / contrôle | Résultat final |
| --- | --- |
| Backend : `.\mvnw.cmd test` | 700 tests, 0 échec, 0 erreur, 0 ignoré ; BUILD SUCCESS |
| Backend : `.\mvnw.cmd verify` | 700 tests, 0 échec, 0 erreur, 0 ignoré ; BUILD SUCCESS, JAR produit |
| Spring Modulith | ApplicationModules.verify() inclus ; 3 tests d'architecture passent |
| Frontend : `npm test` | 260 tests, 29 fichiers, tous réussis |
| Frontend : `npm run build` | Build production réussi |
| Frontend : `npm run test:e2e` | 132 tests navigateur réussis |
| Migration | Base fraîche et upgrade 018 → 019 validés avec PostgreSQL isolé ; faits existants conservés |
| Historique SQL | Empreintes SHA-256 des 18 anciennes migrations identiques à celles du début de tranche |
| OpenAPI | Snapshot Angular identique au contrat backend exporté ; client régénéré |
| `git diff --check` | Réussi |

Les nouveaux tests backend comprennent 46 cas d'intégration du crédit, 14 cas du contrat/configuration payout et 1 cas d'upgrade/protection de migration. Les tests vérifient notamment permissions, montant/destinataire serveur, concurrence, idempotence, preuve financière, réseau hors transaction, timeout, réconciliation, provenance manuelle et clôture de commande. L'interface ajoute 19 cas unitaires et 13 scénarios navigateur couvrant aussi 360 px, desktop, clair/sombre et clavier.

Le premier passage de la suite navigateur complète a révélé une assertion de texte devenue obsolète dans Shipping Finance. Le scénario vérifie désormais distinctement le message avant retrait et après retrait, tout en conservant l'interdiction de faux paiement. Le passage complet final est vert.

Logs locaux : `Backend/target/credit-test.log`, `Backend/target/credit-verify.log`, `Frontend/artifacts/credit-unit.log`, `Frontend/artifacts/credit-build.log`, `Frontend/artifacts/credit-e2e-final.log`. Aucune dépendance Maven/npm ajoutée. Aucun payout réel n'a été exécuté : le contrat réel du compte sandbox reste à valider manuellement après configuration des secrets.

