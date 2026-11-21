# SasPay — observations stables et triage des incidents locaux

## Observations du pilote (21 novembre 2026)

Les requêtes de diagnostic de l'opérateur sur le compte de test LIVE ont
indiqué **103 dossiers**, répartis en :
- 83 historiques `AUDIT_INCOMPLETE` (créés avant la restauration du checkpoint);
- 14 webhooks authentifiés en `REVIEW_REQUIRED / UNKNOWN_REFERENCE`;
- 2 webhooks authentifiés en `REVIEW_REQUIRED / WEBHOOK_PARSE_FAILED`;
- 3 paiements `CREATED / PROVIDER_STATE_UNCERTAIN`;
- 1 paiement `PENDING` sans marqueur de rapprochement.

Le checkpoint a avancé et atteint le 21 novembre 2026 à 05:41.
Une même entrée webhook/paiement était comptée à nouveau à chaque cycle ;
son nombre d'observations passait de 1 à 3, puis 4.

**Cela ne prouve pas 20 transactions financièrement échouées.**
Les signatures authentiques des webhooks ne garantissent ni une référence
transaction connue, ni le succès de la transaction sous-jacente.

## Correction du cycle de vie des observations locales

Le moniteur associe à chaque anomalie locale un hash du contenu workflow
utile à sa résolution :
- webhook : statut de traitement, code d'erreur, nombre de tentatives ;
- paiement : statut, indicateur de rapprochement, motif, identifiant fournisseur ;
- retrait : statut, indicateur de rapprochement, motif, identifiant fournisseur ;
- vérification de retrait : statut, motif, identifiant fournisseur et prochaine échéance.

Les simples mises à jour techniques de version JPA ou de date de vérification
ne constituent **pas** une nouvelle preuve financière et ne rouvrent pas le dossier.

Une réobservation identique conserve le même nombre d'occurrences et ne
réouvre plus un dossier administrativement clôturé. Un changement de traitement, de référence fournisseur ou d'état local provoque une observation supplémentaire et rouvre le dossier.
Cette distinction est garantie par une **seule instruction SQL atomique**,
même après un crash ou avec plusieurs instances grâce au bail existant.

La table `saspay_audit_local_signal_states` ne conserve que des empreintes
SHA-256. Les anciennes observations et décisions administratives ne sont pas
réécrites ni supprimées. La toute première surveillance avec cette migration
enregistre une observation initiale supplémentaire pour les anomalies
préexistantes ; les cycles inchangés suivants sont stables.

Les échecs répétés du moniteur et les dépassements de limite sont stabilisés
sur une signature issue de l'étape/du checkpoint. Une nouvelle défaillance
après une reprise réussie peut donc produire une nouvelle observation.

Les anomalies détectées en *comparant directement l'historique fournisseur*
restent soumises à la logique de réobservation du module historique : leur
signature ne fait pas partie de ce lot. Il faut les traiter séparément si
elles apparaissent ; ne jamais les considérer comme résolues par une clôture
administrative seule.

## Triage en lecture seule avant toute décision

Utiliser ces requêtes **sur la base du compte marchand attendu uniquement**,
en conservant leurs sorties privées.

```sql
SELECT processing_status,
       COALESCE(last_error_code, 'AUCUN') AS code,
       COUNT(*) AS nombre,
       MIN(received_at) AS premiere_reception,
       MAX(received_at) AS derniere_reception,
       COUNT(*) FILTER (WHERE payment_id IS NOT NULL) AS relies_a_un_paiement,
       MIN(attempt_count) AS tentatives_min,
       MAX(attempt_count) AS tentatives_max
FROM payment_webhook_events
WHERE provider='SASPAY'
  AND provider_account_reference = :merchant_id
  AND authenticity_verification_result='VALID'
  AND processing_status='REVIEW_REQUIRED'
GROUP BY processing_status, last_error_code;
```

```sql
SELECT status, reconciliation_required,
       COALESCE(reconciliation_reason,'AUCUN') AS raison,
       COUNT(*) AS nombre,
       MIN(initiated_at) AS plus_ancien,
       MAX(initiated_at) AS plus_recent,
       COUNT(*) FILTER (WHERE provider_charge_id IS NOT NULL)
           AS avec_session_fournisseur,
       COUNT(*) FILTER (WHERE provider_transaction_id IS NOT NULL)
           AS avec_transaction_fournisseur
FROM payments
WHERE provider='SASPAY'
  AND provider_account_reference = :merchant_id
  AND (reconciliation_required
    OR (status IN ('CREATED','PENDING')
        AND initiated_at < now()-interval '60 minutes'))
GROUP BY status,reconciliation_required,reconciliation_reason;
```

Remplacer le paramètre `:merchant_id` dans l'outil SQL (ne pas publier
l'identifiant marchand). Il n'existe **aucun** `UPDATE`, `DELETE` ou
`INSERT` dans ces diagnostics.

## Consignes de sécurité et de validation

- Une `UNKNOWN_REFERENCE` ne doit pas être associée arbitrairement à une
  commande : diagnostiquer le mapping, la famille de webhook, les notifications
  de payout séparées et les éventuels tests historiques.
- `WEBHOOK_PARSE_FAILED` exige une inspection ciblée du contrat de
  normalisation et du code de forme, sans exporter de payload personnel.
- `PROVIDER_STATE_UNCERTAIN` exige une **preuve fournisseur indépendante**
  avant de changer l'état du paiement.
- Un paiement `PENDING` ne peut pas être marqué réussi sans la preuve
  requise, même s'il est ancien.
- La clôture administrative ne libère pas de réserve, ne confirme pas de
  paiement et ne change aucun solde.
- Contrôler deux scans successifs après fusion : nombre de dossiers et
  `occurrence_count` stables pour les sources inchangées, et checkpoint
  continuant à avancer. Après revue manuelle, vérifier le scénario où une
  source change réellement de version et rouvre son dossier.
