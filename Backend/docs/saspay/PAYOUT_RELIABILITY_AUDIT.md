# Audit des retraits SasPay — 15 novembre 2026

Base : `main`, commit `7860e4a`. Aucun appel LIVE, transfert, changement de solde
ou intervention sur la base réelle n'a été effectué pendant cet audit.
Les clés existantes ne sont ni lues, ni recopiées, ni régénérées.

## Conclusion sur l'incident

La cause fournisseur exacte de la seconde tentative **n'est pas établie**.
Avant ce correctif, `SASPAY_PAYOUT_PROVIDER_REJECTED` désignait indistinctement :

- un statut différent de 200 sur les GET de tarifs ou de portefeuilles ;
- un statut différent de 201 sur le POST d'initialisation ;
- `success=false`, un `success` non booléen, ou une enveloppe sans `data` structurée.

Le statut HTTP, l'étape et le code métier fournisseur étaient perdus. Une erreur
JSON pouvait aussi devenir `SASPAY_PAYOUT_TRANSPORT_UNCERTAIN`. Le log fourni
ne permet pas de choisir entre ces chemins, ni de prouver qu'un POST a eu lieu.

Les diagnostics transmis par l'opérateur (scope BOTH, IP autorisée, GET tarifs et
27 portefeuilles accessibles) sont des éléments acquis ; l'audit n'attribue pas
arbitrairement le rejet à ces prérequis. `payout_not_enabled`, `invalid_customer`,
un rejet de routage ou un conflit restent des hypothèses, pas un diagnostic.
Il faut récupérer le statut/code de cette tentative auprès des journaux techniques
existants ou du support fournisseur. Un nouveau POST n'est pas un outil de diagnostic.

L'absence de réception Wave, de webhook, de mouvement apparent et de résultat dans
une liste de transactions ne prouve pas l'absence d'une transaction SasPay.

## Contrat vérifié

Les pages officielles ont été consultées le 15 novembre 2026 et comparées à
`openapi.json` conservé dans ce dossier. Aucun changement permissif du contrat
d'initialisation n'est introduit.

| Point | Contrat et décision |
| --- | --- |
| Initialisation | POST `/payouts/initialize/`, ACK documenté **201** avec `id`. 200/202 restent non conformes et incertains. |
| Corps | `amount` décimal en chaîne, `currency=XOF`, `country=SN`, `method=wave_sn` ou réseau explicitement configuré, `customer.phone`, `recipient.msisdn`, description et métadonnées. |
| Frais | `fee_charge_mode=DEDUCTED` explicite. Un défaut tarifaire ADD_ON n'interdit pas cet override. Aucun succès n'est déduit du devis. |
| Identité | Une clé persistée par intention dans `Idempotency-Key`. Les métadonnées gardent l'ID et la référence Groupia ; leur retour par SasPay n'est pas présumé. |

Source : [initialisation officielle](https://docs.saspay.me/api-reference/payouts/initialize).

`customer.phone` international et `recipient.msisdn` national utilisés actuellement
sont compatibles avec les formes publiées. Les formes nationale, internationale
sans `+` et E.164 sont normalisées pour comparer les preuves. La validation de
forme ne prouve pas l'existence du compte Mobile Money ni son titulaire ; une
preuve indépendante de bénéficiaire demeure exigée pour le succès.
Source : [formats officiels](https://docs.saspay.me/api-reference/reference/formats).

Les GET de vérification et de détail attendent 200. Un succès nécessite l'accord
sur marchand, ID, RETRAIT/OUTBOUND, devise, mode DEDUCTED, brut, frais, débit et net,
plus réseau et numéro crédité dans le détail. Pour 1 000 XOF et 37 XOF acceptés,
le débit attendu est 1 000 et le net 963. Aucun ACK ne suffit.
Sources : [verify](https://docs.saspay.me/api-reference/payouts/verify),
[détail](https://docs.saspay.me/api-reference/transactions/get).

Les enveloppes `success/data` et les réponses directes illustrées par les endpoints
restent prises en charge. Les erreurs 400/401/403/409/422/429 et 5xx sont distinguées.
Un 409 peut désigner une intention déjà en cours : il ne libère jamais la réserve.
Un code fournisseur n'est conservé que s'il appartient à la liste fermée documentée ;
les autres deviennent `UNRECOGNIZED`. Sources :
[enveloppes et idempotence](https://docs.saspay.me/api-reference/introduction),
[codes payout](https://docs.saspay.me/api-reference/payouts/initialize).

La tarification est contrôlée avant le POST, sans arrondi de frais inventé. Toutes
les pages de portefeuilles sont requises ; `count=27` n'est pas la taille de la
première page. La pagination est bornée et limitée à l'origine/route attendue.
Le solde SasPay est une preuve de liquidité, pas un solde vendeur.
Sources : [tarifs](https://docs.saspay.me/api-reference/reference/my-rates),
[soldes](https://docs.saspay.me/api-reference/wallet/balances-list).

## Défauts démontrés et corrections

1. **Diagnostic confondu** : exception structurée à vocabulaire fermé, avec étape,
   méthode, endpoint logique, statut HTTP, code fournisseur, catégorie et certitude.
   JSON, enveloppe, taille, type de contenu, référence et transport restent distincts.
   La sérialisation est terminée avant d'entrer dans le POST.
2. **Récupération après crash** : une réconciliation sans référence pouvait convertir
   `PAYOUT_MANUAL_RECOVERY_IN_PROGRESS` en `PAYOUT_REFERENCE_UNAVAILABLE`, rendant
   l'intention à nouveau éligible au mécanisme exceptionnel. Elle devient désormais
   `PAYOUT_MANUAL_RECOVERY_UNCERTAIN` et ne peut plus être réclamée. Le marqueur
   incertain n'est plus effacé par une erreur générique de réconciliation.
3. **Idempotence du consentement** : une même clé avec un autre `feeQuoteId` pouvait
   retourner silencieusement l'ancien payout. Les deux parcours refusent maintenant
   ce changement avec `PAYOUT_IDEMPOTENCY_CONFLICT`.
4. **Webhooks payout non routés** : RETRAIT était traité par le parseur PAIEMENT.
   Les notifications SasPay de retrait sont maintenant authentifiées, dédupliquées
   et persistées avant ACK. Seuls hash, compte, ID technique et diagnostic sont
   conservés. Un événement Modulith durable demande une vérification ; aucun montant,
   bénéficiaire ou statut du webhook n'est appliqué financièrement.
5. **Réconciliation uniquement manuelle** : ajout d'un worker GET uniquement, opt-in,
   traitant au plus dix références connues par passage avec une réservation de travail
   persistée de cinq minutes. Les répliques réclament les travaux par `SKIP LOCKED`.
   Un redémarrage ne peut jamais déclencher `initiate()`.
6. **Dette inter-parcours** : la réservation d'une vente directe utilisait uniquement
   son solde propre alors que l'agrégat boutique pouvait être diminué par une dette
   de groupage. Devis, réservation, affichage et dernier contrôle avant envoi sont
   désormais plafonnés par les fonds globaux. Le contrôle inclut aussi la récupération
   exceptionnelle. Une dette nouvelle conserve la réserve et bloque le POST.

Les webhooks `transaction.*` décrivent aussi les payouts. `settlement.*` concerne un
autre flux dont le payload n'est pas garanti : il n'est pas utilisé comme preuve.
Un webhook avant l'ACK ne lie pas un ID inconnu ; après liaison, le polling retrouve
le payout. Si l'ACK est définitivement perdu, une intervention fournisseur reste
nécessaire. Source : [webhooks officiels](https://docs.saspay.me/api-reference/webhooks).

Une notification postérieure à FAILED/CANCELLED peut demander un nouveau GET sans
changer ce statut. Un jeton de réservation du travail empêche la fin d'un GET ancien
d'effacer une notification reçue pendant cet appel. SUCCEEDED reste immuable.

## Invariants financiers audités

- `MerchantPayoutTransactions` autorise l'acteur et verrouille l'organisation puis
  la source. `CREATED`, `PENDING` et `REVIEW_REQUIRED` restent réservés. Une seule
  demande peut passer le claim ; un rejeu ne génère aucune nouvelle clé fournisseur.
- `MerchantBalances` et `DirectMerchantBalances` exigent des encaissements réellement
  réglés, du même fournisseur/compte, sans renversement ni anomalie. Les remboursements,
  paiements historiques, réservations et états commerciaux entrent dans l'éligibilité.
- `JdbcMerchantFinanceStore` lie un devis immuable à une seule intention ; les
  snapshots et écritures financières sont protégés en base. Le registre commun des
  sorties interdit une référence fournisseur déjà attribuée à une autre opération.
- Les preuves incohérentes ne libèrent rien. Un succès confirmé n'est pas régressé
  par un événement tardif. Les frais constatés doivent correspondre au devis accepté.
- La nouvelle migration 059 ne modifie aucune ligne financière existante. Le planning
  est dans une table distincte pour préserver les triggers d'immutabilité historiques.

La certitude `NOT_ATTEMPTED` signifie **aucun POST dans cet appel précis**. Elle ne
prouve jamais l'absence d'un envoi lors d'une tentative historique. Par prudence,
même un précontrôle refusé conserve l'intention réservée pour examen opérateur.
`UNKNOWN` s'applique dès l'entrée dans le transport POST, y compris 4xx/5xx ou JSON
invalide ; `ALREADY_SUBMITTED` désigne les GET d'une référence déjà liée.

## Exploitation et mise en production

1. Exiger les jobs GitHub Actions entièrement verts et une revue de la PR. Déployer
   la migration additive puis le backend ; aucun script de réparation financière.
2. Conserver les autorisations de payout et de récupération désactivées par défaut.
   Ce correctif ne lève pas les restrictions du pilote LIVE existant : une boutique,
   une vente directe Wave, au plus 1 000 XOF et une intention. Les groupages utilisent
   le même moteur testé mais ne sont pas ouverts au LIVE général par cette PR.
3. Le worker est désactivé par défaut. Une activation séparée de
   `groupia.settlement.saspay-payout-reconciliation.enabled=true` permet seulement les
   GET des références liées du compte configuré, lorsque le fournisseur est autorisé.
   Le délai par défaut est 60 secondes ; une tâche déjà réclamée attend cinq minutes.
4. Vérifier les abonnements `transaction.created/success/failed/cancelled` et la
   signature HMAC sur les octets originaux. Ne pas modifier la sécurité pour la recette.
5. Surveiller les logs `Merchant payout failure`, les retraits en revue, les reçus
   `payout_status_notifications.error_code`, les publications Modulith échouées et les
   tâches durablement sans résolution. Aucune URL réelle, corps HTTP, clé, signature,
   numéro complet, identité de bénéficiaire ou cause d'exception ne doit être journalisé.
6. En rollback, désactiver le worker et les nouvelles initiations. Conserver la
   migration additive, les reçus et les réservations. Ne pas supprimer les tables.

## Retrait historique : procédure strictement sans transfert

L'intention `e1ecc9ae-89ab-449f-bc91-1a7e59f03a0e` de la boutique
`b95a0515-1bcf-4740-8417-c594d02f211e`, commande `DIR-000000000021`, reste réservée,
`REVIEW_REQUIRED / PAYOUT_MANUAL_RECOVERY_UNCERTAIN`, sans référence fournisseur.
Elle est exclue du worker par l'absence d'ID. Ne pas appeler `recover-pilot`, ne pas
modifier le statut, ne pas créer une autre intention pour contourner la réserve.

Lecture opérateur autorisée sur une connexion en lecture seule :

```sql
SELECT id, organization_id, status, reconciliation_required, reconciliation_reason,
       provider, provider_account_reference, provider_transaction_id, business_reference,
       amount, currency, requested_at
FROM merchant_payouts
WHERE organization_id = :organization_id AND id = :payout_id;
```

Obtenir du support SasPay la corrélation de **la clé originale déjà stockée**, via un
canal sûr sans l'afficher dans les logs ou la PR : compte, référence métier, horodatage
des deux tentatives et résultat du traitement/idempotence. Demander le statut HTTP,
code métier, éventuel ID de transaction, preuve du débit/net/bénéficiaire, et résultat
de toute tentative gateway. Aucune clé nouvelle n'est créée.

La liste `/transactions/` peut aider à trouver des candidats, avec compte, RETRAIT,
OUTBOUND et fenêtre temporelle, en parcourant toutes les pages. Son `search` n'est
pas documenté comme recherche dans les métadonnées Groupia. Zéro résultat, même après
pagination exhaustive, ne clôt pas l'incident. Source :
[liste des transactions](https://docs.saspay.me/api-reference/transactions/list).

Si une transaction est trouvée, exiger les preuves GET concordantes et une procédure
de liaison explicitement autorisée et auditée ; aucun endpoint de liaison arbitraire
n'est ajouté ici. Si SasPay atteste formellement l'absence de transaction et de débit,
une éventuelle libération doit passer par une évolution métier auditée séparée,
testée et autorisée. Jamais par UPDATE SQL ni remise à CREATED.

## Validation LIVE future

Elle n'est ni exécutée ni autorisée par ce correctif. Après résolution de l'incident,
obtenir une autorisation explicite pour une intention précise, un bénéficiaire validé,
un plafond et un devis serveur accepté. Le pilote déjà consommé ne doit pas être remis
à zéro. Préparer d'abord la procédure et les preuves de succès/échec avec le simulateur.
Lors d'une recette ultérieure autorisée : un seul POST avec la clé persistée, puis GET
et rapprochement. À la moindre ambiguïté, arrêter les mutations et conserver la réserve.
L'ouverture générale exige une décision distincte et la preuve fournisseur des capacités
marketplace/bénéficiaires ; une CI verte seule ne suffit pas à certifier le LIVE.

## Validation automatique

Résultats et lien de PR : voir `PAYOUT_VALIDATION.md`. Les tests utilisent des serveurs
HTTP locaux et des PostgreSQL Testcontainers. Aucune donnée financière réelle n'est
chargée. Les scénarios existants de concurrence, doublon, isolation boutique, remboursements,
ventes directes, groupages, abonnements et migrations restent dans `mvnw verify`.
