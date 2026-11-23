# Finalisation financière — 23 novembre 2026

Base auditée : `c43ae2d6` (PR #105 incluse). Branche : `codex/saspay-financial-finalization`.
Ce rapport distingue le code, les simulations et les preuves LIVE. Aucune opération financière réelle n'est autorisée par cette mission.

## Lot A — matrice issue de l'inspection

| Fonctionnalité | État existant | Problème réel / limite | Correction prévue | Validation nécessaire |
| --- | --- | --- | --- | --- |
| Encaissement hosted | Existant | Session distincte de transaction ; POST sans garantie d'idempotence ; récupération bornée à 3 pages | Conserver les vérifications et interdire tout POST de récupération | Contrats HTTP, timeout, reprise |
| Récupération legacy | Partielle | Poller exclut devis + CREATED sans ID et PENDING avec transaction seule ; ordre ancien peut affamer les suivants | Sélection exhaustive des états actifs, bail DB, temporisation, diagnostic explicite sans ID | PostgreSQL, concurrence, crash, legacy GET |
| Authentification et parsing webhook | Existant / partiel | HMAC, reçus, retry et diagnostics de forme existent ; SaspayException ne fournit pas le code de parsing typé | Codes fermés, JSON ambigu rejeté, diagnostic sans valeur personnelle | Contrats, déduplication, faux succès |
| Corrélation tardive webhook | Partielle | Corrélation exacte par compte/transaction ; cinq essais puis revue | Préserver les dossiers clôturés ; documenter la revue après épuisement | Webhook avant référence, historique inchangé |
| Surveillance | Existante | Checkpoint, incidents et décisions append-only, empreintes matérielles PR #105 | Ne pas incorporer les compteurs techniques de récupération aux empreintes financières | Tests PR #103–105 |
| Solde / réserves | Existant | Compte source, org, priorité des dettes et réservations vérifiés | Réutiliser le registre et les verrous | Payout concurrent, fonds insuffisants |
| Devis de retrait | Partiel | Expiration à réservation mais pas immédiatement avant POST après précontrôles réseau | Contrôler expiration avant transmission | Horloge contrôlée, aucun POST sur devis expiré |
| Ancienne récupération pilote payout | Partielle | POST possible avec résultat antérieur inconnu, même clé et devis expiré | Retirer ce chemin de réémission ; conserver vérification GET et revue administrative avec preuves | Aucun appel provider, réserve inchangée |
| Annulations / remboursements | Existant avec limites | Dossier externe audité ; aucune API SasPay native de remboursement dans la copie OpenAPI ; remboursement partiel universel non implémenté | Conserver REFUND_DUE tant que remboursement externe non déclaré ; documenter les limites | Annulation, doublon, fonds déjà attribués |
| Abonnement | Existant, plan unique configuré | Facture, montant serveur, période et activation prouvée ; expiration peut permettre nouvelle facture malgré tentative incertaine | Bloquer remplacement tant que paiement précédent incertain/en cours | Expiration, double création, événement tardif |
| Plans multiples / changement de plan | Absent | Seul STANDARD_MONTHLY et tarif configurable existent ; aucune règle de prorata produit | Ne pas inventer de tarifs ni de prélèvement récurrent | Décision produit nécessaire |
| Isolation / événements | Existant | Permissions centralisées, verrous, contraintes uniques, preuves, Modulith persistant | Maintenir frontières et contrôles existants | ModularityTests, intégrations et autorisations |

Inspection : payment (adaptateur, client, transactions, webhooks, remboursements, modèles), settlement (soldes directs/groupages, réservations, provider, administration), subscription (facturation, activation, droits), ordering/directsales (annulations et événements), grouping, organization, notification et audit. Le package audit est essentiellement structurel : les preuves concrètes résident dans les registres de chaque module. Migrations 001–063, CI, suites Java, scripts de sécurité et configuration Playwright examinés.

Contrats : copie OpenAPI versionnée et documentation officielle consultée le 23 novembre : [webhooks](https://docs.saspay.me/api-reference/webhooks), [payout initialize](https://docs.saspay.me/api-reference/payouts/initialize). Les événements settlement ont un payload annoncé non stabilisé ; ils ne peuvent pas constituer une preuve de règlement. Aucun réseau supplémentaire n'est activé par cette mission.

## Données historiques et limites d'investigation

Les trois CREATED avec devis sans référence ne prouvent ni envoi ni absence d'envoi. L'expiration du devis ne prouve pas l'échec du paiement. Sans référence fournisseur fiable, conserver l'incertitude et obtenir des preuves opérateur/fournisseur. Aucun POST, rapprochement par montant seul ou mise à jour SQL corrective.

La base locale de développement a ensuite été inspectée avec `BEGIN READ ONLY` : les quatre combinaisons signalées sont présentes, ainsi que les deux reçus sans observation normalisée et les **104 incidents**. Aucun UPDATE ni migration n'a été exécuté sur cette base. Les identifiants individuels et les payloads ne sont pas publiés dans ce dépôt public.

Le **23 novembre**, un seul `GET /payments/{id}/verify/` réel a confirmé **FAILED** pour l'ancien PENDING. Les dix comparaisons sont positives : transaction, marchand, type PAIEMENT, INBOUND, XOF, ADD_ON, principal 1 000 XOF, net 1 000 XOF, frais acceptés 139 XOF, total 1 139 XOF. Cette lecture ne déplace aucun fonds et ne modifie pas Groupia. Après déploiement contrôlé, le poller devra refaire sa propre vérification et appliquer la transition ; le diagnostic externe ne lui sert pas de preuve importée.

Pour les deux erreurs du 17 novembre, la base conserve seulement empreinte, date et erreur générique. Une lecture GET de l'historique fournisseur (une page, 40 livraisons, pagination épuisée) montre `transaction.created` puis `transaction.success`, type **TRANSFERT**, aux heures correspondantes (09:15:56 et 09:16:18 UTC). Les sérialisations usuelles du payload restitué ne reproduisent pas les empreintes conservées : c'est une **piste compatible avec l'ancien routage des transferts**, pas une association démontrée. Le routage actuel accepte déjà TRANSFERT comme indication sortante, jamais comme encaissement. Aucun dossier n'a été modifié ou clôturé.

Les 14 UNKNOWN_REFERENCE clôturés, les 84 AUDIT_INCOMPLETE et toutes les décisions restent conservés.

## Corrections réalisées

### Récupération et webhooks (lot B)

- Migration **064**, additive : table de planification `saspay_payment_recovery`. Aucun backfill de statuts ou de preuves financières.
- Admission progressive de tous les paiements SasPay CREATED/PENDING du **compte configuré**, indépendamment du devis et des identifiants présents. Délai initial de 60 secondes, admission maximale de 100 lignes et traitement maximal de 25 par cycle.
- Prise en charge persistante avec `FOR UPDATE SKIP LOCKED`, bail de 30 minutes et jeton de propriétaire. Après crash, reprise à expiration du bail ; une fin de travail obsolète ne peut pas effacer le bail d'un autre worker. Les appels sortants restent exclusivement GET. Un bail expiré peut conduire à répéter une lecture, jamais un débit ; les verrous financiers continuent de protéger l'allocation.
- Prochain contrôle à 60 secondes lorsque la vérification est cohérente ; temporisation exponentielle, plafonnée à une heure, pour les résultats incertains. Les erreurs anciennes ne monopolisent plus la première page. Les compteurs et dates techniques restent hors des empreintes financières de la PR #105.
- Legacy avec transaction : vérification du devis historique même expiré (l'expiration bloque une **nouvelle initiation**, pas la vérification d'un paiement déjà envoyé). Legacy sans référence : `SASPAY_LEGACY_REFERENCE_UNAVAILABLE`, aucun appel fournisseur et aucune nouvelle session.
- Codes de diagnostic à vocabulaire fermé, persistés avec le motif de réconciliation. Messages lisibles ajoutés à la page financière existante, sans refonte graphique.
- Détection des clés JSON dupliquées et des documents JSON concaténés dans les encaissements et webhooks ; même contrôle des réponses de reversement. Les noms de champs inconnus sont masqués dans les diagnostics de forme (un nom de champ peut lui-même contenir des données personnelles).
- Contrôle des multiples valeurs de `X-Webhook-Event` au niveau HTTP. Bictorys conserve ses contrats, ses parseurs et ses règles de confirmation.

### Reversements (lot C)

- Expiration du devis contrôlée avant les précontrôles et juste avant le POST, y compris après les lectures de tarifs et de portefeuille. Refus classé **NOT_ATTEMPTED** : le chemin de gestion existant sait distinguer ce refus local d'un résultat fournisseur inconnu.
- Ancien endpoint de récupération pilote retiré : réponse 410 après autorisation. Il ne peut plus réémettre un retrait incertain sous prétexte de réutiliser sa clé. Les réserves et les états historiques restent inchangés. La vérification GET et les dossiers administratifs avec preuves restent les voies autorisées.
- Aucun nouveau réseau ni plafond fournisseur supposé. Les contrôles de bénéficiaire, de provenance, de solvabilité globale, de frais déduits et de double preuve sont conservés.

### Abonnements et remboursements (lot D)

- Une échéance expirée ne peut plus être remplacée tant que sa tentative est en cours ou qu'une anomalie financière demeure. Un succès tardif sans droit attribuable bloque également un nouveau paiement jusqu'à sa revue. Une échéance expirée sans tentative peut toujours être remplacée.
- Factures et périodes restent des snapshots du plan **STANDARD_MONTHLY**, au prix configuré ; aucun nouveau prix, prorata ou abonnement automatique ajouté.
- Les remboursements conservent leur parcours existant : REFUND_DUE, justificatif de remboursement externe et événement idempotent EXTERNALLY_RECORDED. Ce dernier est une déclaration auditée, **pas une confirmation API SasPay**. Aucune route de remboursement native n'a été inventée.

### Durcissement et outils (lot E)

- Diagnostic réutilisable `tools/saspay_inspect_legacy_payment.py` : UUID validé, origine TEST/LIVE déduite de la clé, GET unique sans redirection, corps borné, comparaisons décimales et sortie sans identifiants ni payload.
- Audit npm rendu exécutable sous Windows (`npm.cmd`), sans modifier les seuils ni l'exception historique de sécurité.

## Matrice de couverture financière

« Tests existants » signifie des scénarios présents dans le dépôt ; les résultats des commandes de cette mission sont indiqués séparément ci-dessous. Aucun stub n'est assimilé à un test LIVE.

| Fonctionnalité | Implémentée | Couverture automatique | Preuve LIVE | Limite / blocage |
| --- | --- | --- | --- | --- |
| Encaissement hosted direct/groupage | Oui | HostedContract, SaspayIntegration, direct/shipping E2E | Encaissements rapportés par l'utilisateur | Vérifications d'exploitation finales |
| Paiement legacy transaction seule | Oui, récupération corrigée | Régression PostgreSQL + GET HTTP | Lecture réelle FAILED, sans mutation | Transition locale après déploiement contrôlé |
| Legacy sans référence | Escalade sûre | Aucun POST, refus de relance, queue PostgreSQL | Trois cas observés en base READ ONLY | Référence/preuve fournisseur indispensable |
| Webhooks HMAC, doublons, ordre | Oui | Contrats, reçus DB, succès/échec tardifs, JSON ambigu | Webhooks authentifiés rapportés | Deux parse failures historiques non attribués avec certitude |
| Reprise crash / replicas | Oui | Concurrence PostgreSQL, bail expiré, completion obsolète, équité | Non | Reprise d'un GET permise après bail |
| Soldes, réserves, dettes groupage | Oui | MerchantSettlement, UnifiedMerchantPayout, concurrence et autorisations existantes | Deux retraits Wave rapportés | Pas de généralisation à tous réseaux/vendeurs |
| Devis payout / expiration / frais | Oui, contrôle renforcé | Stub HTTP, horloge, aucun POST après expiration | 1 000 brut / 37 frais / 963 net rapportés | Frais tiers externes non documentés |
| Payout incertain | Réserve + GET + revue | Échecs, double preuve, récupération pilote interdite | Pas de nouvel essai | Aucune réémission sans preuve de non-exécution |
| Remboursement externe total | Oui, déclaration auditée | CommercialCancellation et SaspayIntegration existants | Non | Pas de confirmation fournisseur native |
| Remboursement partiel SasPay générique | Non, bloqué | Refus de chemins incompatibles existants | Non | API native et allocation partielle non établies |
| Remboursement après reversement | Revue / remboursement externe selon parcours existant | Garde-fous de solde et d'état existants | Non | Pas d'automatisation universelle ni de récupération de fonds inventée |
| Abonnement manuel / activation / expiration | Oui | SubscriptionIntegration, isolation, E2E | Non | Tarifs et enforcement à valider en exploitation |
| Plusieurs plans / changement avec prorata | Non | Sans objet | Non | Un seul plan configuré ; règles produit non définies |
| Renouvellement automatique | Non, renouvellement manuel explicite | Factures + idempotence | Non | Aucune capacité de prélèvement récurrent revendiquée |
| Incidents / décisions / fraîcheur | Oui | Tests console, monitoring, migrations, empreintes stables | 104 dossiers observés conservés | Clôture administrative distincte de règlement |

## Procédure de validation finale sous contrôle humain

1. Sauvegarder la base et les justificatifs ; vérifier sur une copie les migrations et invariants, notamment les 104 incidents et les 14 dossiers clôturés. Aucun reset de checksum ni suppression.
2. Revoir et fusionner manuellement la PR seulement après contrôles verts ; déployer avec les paramètres marchand/environnement et les autorisations réseau/KYC vérifiés. Ne pas réactiver l'ancien endpoint de récupération pilote.
3. Vérifier d'abord les GET : reprise du legacy PENDING, absence de nouveau POST, maintien des trois CREATED en revue et visibilité de leurs motifs. Vérifier les délais/baux dans la table opérationnelle et la fraîcheur du monitoring.
4. Faire confirmer par SasPay la référence ou la non-exécution de chaque tentative sans ID ; pour les deux webhooks, demander la livraison originale ou une preuve corrélée. Ne pas déduire un succès ou un échec d'une simple proximité temporelle.
5. Uniquement après autorisation explicite de l'opérateur, choisir un petit montant et un réseau effectivement activé : confirmer devis, principal, frais, total, preuve indépendante puis solde. Tester ensuite un retrait autorisé et confronter brut/frais/net au bénéficiaire. Ces étapes ne sont jamais déclenchées par les tests ni par un GET de page.
6. Vérifier doublons et retards en sandbox ; vérifier une échéance d'abonnement au tarif configuré et son activation unique. Un remboursement externe exige son justificatif réel ; aucun faux succès pour terminer le parcours.

Lecture opérationnelle après déploiement (aucune écriture) :

```sql
BEGIN READ ONLY;
SELECT p.id, p.status, p.reconciliation_reason, p.last_checked_at,
       r.attempt_count, r.last_attempt_at, r.last_completed_at,
       r.next_check_at, r.lease_until, r.last_result
FROM payments p LEFT JOIN saspay_payment_recovery r ON r.payment_id=p.id
WHERE p.provider='SASPAY' AND p.status IN ('CREATED','PENDING')
ORDER BY r.next_check_at NULLS FIRST, p.initiated_at;
COMMIT;
```

## Anomalies et capacités restant ouvertes

- Trois initiations legacy sans ID : absence d'envoi ou non-exécution non démontrée. Maintenir blocage.
- Un legacy vérifié FAILED par lecture réelle, mais état local volontairement inchangé avant déploiement.
- Deux reçus historiques : piste TRANSFERT, empreintes non corroborées ; aucune clôture.
- API de remboursement native, remboursement partiel universel, plans multiples/prorata et prélèvement récurrent : non livrés comme capacités opérationnelles. Cela limite la portée de la finalisation ; ne pas déclarer l'ensemble des critères de production acquis.
- Les pilotes LIVE rapportés ne prouvent ni tous les réseaux ni toutes les autorisations commerciales. Aucun audit indépendant ASVS/PCI/réglementaire n'est revendiqué.

## Validation

- Première passe ciblée : 205 tests, un défaut de classification JSON et deux erreurs dues au même cast PostgreSQL manquant. Corrections effectuées, sans assouplir les assertions financières.
- Revalidation des quatre suites concernées : **130 tests, zéro échec/erreur** (SasPay contracts, queue PostgreSQL, intégration financière, provider payout). Les 20 tests d'abonnement et les tests historiques d'incidents ont passé la première passe ciblée.
- Angular après les messages de récupération : **553 tests / 53 fichiers passent**.
- Playwright complet : **391 scénarios passent** ; nouvelle passe financière après les derniers libellés en cours.
- Python : **88 tests passent** ; 22 migrations gelées conformes à leur baseline.
- Audit npm : **0 vulnérabilité production**, 9 signalements modérés dans l'arbre de développement, aucun high/critical. Le seuil CI passe ; ces neuf signalements ne sont pas présentés comme inexistants.
- Scan de secrets initial : zéro résultat ; scan final, `mvnw verify` global et GitHub Actions à renseigner à leur terminaison. La première suite globale de référence a été interrompue volontairement pour prioriser les corrections ; elle n'est pas comptabilisée comme validation complète.

Aucun succès LIVE supplémentaire revendiqué. Les deux retraits de 1 000 XOF (963 nets / 37 frais) sont des preuves rapportées par l'utilisateur, limitées au parcours Wave concerné. La seule vérification réelle supplémentaire du paiement historique est une lecture GET, sans mouvement de fonds.
