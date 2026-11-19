# SasPay — incidents persistants et reprise historique (jalon suivant)

## Portée

Cette évolution complète l'audit en lecture seule de la PR #100.
Elle **ne modifie ni paiements, ni retraits, ni soldes, ni réservations**, et n'émet
**aucun POST** au fournisseur. Seuls deux registres opérationnels sont écrits :
`saspay_audit_incidents` et `saspay_audit_checkpoints`.

- Chaque anomalie garde un code interne strict et une empreinte SHA-256
  (jamais la référence brute, le numéro ou la réponse SasPay).
- La clé unique compte marchand + code + empreinte permet une remontée
  idempotente, même après redémarrage ou entre plusieurs réplicas.
- L'horodatage initial n'est jamais remplacé ; les occurrences s'incrémentent.
  Les incidents restent **OPEN** jusqu'à revue. Aucun acquittement ni résolution
  automatique ne résulte d'une simple réponse fournisseur.
- Le contrôle local surveille les webhooks SasPay valides en échec ou en
  attente excessive, les paiements/retraits marqués pour revue et les retraits
  dont la vérification est en retard. Les files existantes continuent seules
  à effectuer les vérifications indépendantes.
- Une interrogation de plus de 250 lignes dans une catégorie crée une alerte
  `MONITORING_SCAN_OVERFLOW`. Le surplus ne doit pas être considéré comme
  audité par cette seule tranche.

## Paramétrage (inactif par défaut)

```dotenv
SASPAY_ENABLED=true
SASPAY_HISTORY_AUDIT_ENABLED=true
SASPAY_INCIDENT_MONITOR_ENABLED=false
SASPAY_HISTORY_AUDIT_INTERVAL_MS=900000
# Optionnel, seulement au premier démarrage avant le premier checkpoint :
SASPAY_INCIDENT_MONITOR_START_AT=
```

L'activation de `SASPAY_INCIDENT_MONITOR_ENABLED=true` remplace le planning
historique log-only par le contrôle avec incidents durables. **Ne l'activer
qu'après les tests en recette et la vérification de l'accès en lecture de
l'API et des quotas SasPay.** Les montants maximum autorisés par la
configuration précédente restent inchangés.

### Reprise après panne

Chaque exécution possède une **location à durée finie** (`lease_token`, 30 min)
pour éviter plusieurs scans concurrents entre instances. Le point de contrôle
n'avance que si chaque page du fournisseur a été lue, la comparaison a
terminé sans erreur et les incidents ont bien été enregistrés.

Les lectures sont découpées en périodes consécutives de **6 heures**, au
maximum **4 périodes par passage**, avec 5 minutes de marge pour la
stabilisation des données. La reprise dure donc plusieurs passages si la
panne a été longue ; elle ne saute jamais volontairement la période
manquante. Une pagination tronquée, une erreur fournisseur ou l'expiration
de la location conserve le point de contrôle précédent.

Au premier lancement uniquement :
- si `SASPAY_INCIDENT_MONITOR_START_AT` est renseigné, la reprise commence à
  cet instant ISO UTC, par exemple `2026-11-01T00:00:00Z` ;
- sinon elle débute 48 heures avant le premier lancement. Les transactions
  antérieures ne sont **pas** rétroactivement garanties.
- Modifier `SASPAY_INCIDENT_MONITOR_START_AT` après l'initialisation ne
  rembobine **jamais** un point de contrôle existant.

Les anomalies concernent l'**historique vu chez SasPay**. Le rapprochement
inverse (transactions Groupia absentes de la liste SasPay), les soldes
consolidés et le moteur de résolution supervisée restent à implémenter.

## Exploitation

Les incidents sont inspectables uniquement par accès administrateur
à la base. Aucun endpoint public et aucune exposition d'identifiants de
vendeuses n'ont été ajoutés. L'interface d'administration sécurisée est
une étape distincte à effectuer avant ouverture aux opérateurs.

```sql
SELECT incident_code, status, first_seen_at, last_seen_at, occurrence_count
FROM saspay_audit_incidents
ORDER BY last_seen_at DESC LIMIT 100;

SELECT completed_until, last_success_at, last_failure_code, lease_until
FROM saspay_audit_checkpoints;
```

La bonne santé des jobs GitHub Actions n'est pas une preuve de concordance
avec des fonds réellement détenus par SasPay : réaliser d'abord une
comparaison de transactions non sensibles sur l'environnement autorisé.
