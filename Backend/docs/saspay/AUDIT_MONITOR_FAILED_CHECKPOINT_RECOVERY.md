# SasPay — reprise après 71 alertes AUDIT_INCOMPLETE

Cette correction traite un défaut du chemin périodique de la surveillance
SasPay. Elle **ne valide ni n'invalide les encaissements ou retraits** et
n'autorise aucune opération fournisseur de paiement.

## Symptômes observés

- Le diagnostic manuel GET/GET peut réussir.
- Le rapprochement planifié laisse `completed_until` à NULL.
- `AUDIT_INCOMPLETE` se reproduit toutes les 15 minutes avec une nouvelle
  empreinte d'incident, sans clôture de la fenêtre.
- Les incidents sont uniquement des signaux du moniteur ; ils ne prouvent
  pas 71 anomalies financières différentes.

## Causes et corrections

1. Les filtres SQL temporels étaient transmis sous forme de `java.time.Instant`
   au pilote PostgreSQL. Certains pilotes refusent cette liaison et demandent
   un type JDBC explicite. Le scanner transmet désormais
   `java.sql.Timestamp.from(instant)` pour les paramètres `TIMESTAMPTZ`.
2. Chaque échec au démarrage utilisait `now - 48 h` dans l'empreinte de
   l'incident. À chaque cycle, le temps changeait, créant une nouvelle alerte.
   Le même incident `AUDIT_INCOMPLETE` est maintenant regroupé par compte
   marchand avec comptage des occurrences. **Les anciens incidents ne sont
   jamais supprimés, ni modifiés rétroactivement**.
3. Le checkpoint enregistre une famille et une étape d'échec sûre
   (`DATABASE_LOCAL_SCAN`, `PROVIDER_HISTORY_READ`, etc.).
   Le journal affiche étape, famille, SQLState autorisé et HTTP status
   fournisseur, sans les causes brutes, tokens, références clients ou données
   personnelles. L'interface montre ce code même lorsque
   `completed_until` est encore NULL.

## Validation et remise en route

- Tant que ce correctif n'est pas fusionné et redéployé, laisser
  `SASPAY_HISTORY_AUDIT_ENABLED=false` et
  `SASPAY_INCIDENT_MONITOR_ENABLED=false`.
- La console opérateur peut rester activée.
- Ne toucher ni aux clés SasPay, ni aux plafonds, ni aux retraits.
- Après validation CI et fusion, déployer le backend puis activer les deux
  flags séparément en maintenant les permissions existantes.
- Attendre un cycle (initialDelay 60 secondes) et actualiser la console :
  vérifier `completed_until` non NULL puis progression successive, et
  `last_failure_code` NULL.
- En cas de nouvel échec, le journal safe du moniteur (stage/family/sqlState)
  permet de déterminer la cause ; **désactiver à nouveau le moniteur** et
  traiter ce code, sans purger les preuves.
- La première initialisation (sans `SASPAY_INCIDENT_MONITOR_START_AT`)
  reprend 48h avant le lancement ; les opérations plus anciennes nécessitent
  une reprise dédiée. Les 71 alertes historiques restent visibles et
  peuvent être administrativement examinées et clôturées avec des preuves,
  mais ne doivent pas être effacées ni auto-clôturées.

## Garanties

- Les scans restent uniquement `GET` vers SasPay et `SELECT` des tables
  financières.
- Les seules mutations sont dans les tables d'incidents et de checkpoints.
- Le rapprochement inverse et le grand livre multi-vendeurs restent hors
  périmètre de cette correction ciblée.
