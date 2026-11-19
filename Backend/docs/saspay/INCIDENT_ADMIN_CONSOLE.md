# SasPay — console administrateur des incidents (PR suivante)

## Objectif

Un espace distinct des écrans des commerçantes permet la consultation des anomalies
persistantes du rapprochement SasPay : filtres, pagination, détails, historique et
**décisions supervisées**. Aucune opération financière ne peut être lancée ici.

## Autorisation

La console s'active uniquement pour des comptes **Groupia authentifiés et actifs**,
explicitement ajoutés à une liste d'opérateurs par UUID. Le serveur revérifie cette
autorisation à chaque lecture et écriture, y compris pour les API de détail.

```dotenv
# prerequisites : SASPAY_ENABLED=true, compte merchant configuré
SASPAY_INCIDENT_CONSOLE_ENABLED=false
SASPAY_INCIDENT_OPERATOR_IDS=
```

Après validation de l'intégration, affecter les UUID autorisés (séparés par une
virgule) et passer `SASPAY_INCIDENT_CONSOLE_ENABLED=true`. **Ne jamais publier
les UUID d'opérateur dans une variable Angular ou un endpoint public**.

Page web : `/app/admin/incidents-saspay` ; le lien apparaît dans le menu
uniquement pour les comptes ayant obtenu une réponse d'autorisation positive.
La sécurité est exclusivement imposée par le backend : l'affichage conditionnel
du lien n'accorde aucun droit.

Les endpoints privés, sans cache, sont :

- `GET /api/platform/saspay/incidents/access` (réponse binaire d'autorisation).
- `GET /api/platform/saspay/incidents?status=&code=&page=0&size=20`
- `GET /api/platform/saspay/incidents/{incidentId}`
- `POST /api/platform/saspay/incidents/{incidentId}/decisions`

## Protection du registre financier

Le journal `saspay_audit_incident_decisions` est **append-only** : aucune
suppression ou réécriture autorisée. Les preuves historiques `saspay_audit_incidents`
ne changent pas et ne deviennent pas un justificatif de réussite financière.

Les décisions exigent :

- une action dans `REVIEW_STARTED`, `REVIEW_NOTE`, `CLOSED` ;
- une référence de preuve non ambiguë de 5 à 120 caractères ASCII autorisés ;
- un motif détaillé de 20 à 500 caractères, sans caractères de contrôle ;
- la confirmation explicite et le compte de dernières observations vu à l'écran ;
- l'identité de l'opérateur obtenue côté serveur, jamais fournie par le navigateur.

Le registre de suivi est administrativement `OPEN`, `IN_REVIEW` ou `CLOSED`.
La clôture n'exprime pas une preuve de paiement, de non-exécution ou de restitution.
Une nouvelle observation après une décision recrée **automatiquement un état
OPEN** sur la console, sans supprimer l'ancien historique de décisions.

Le contrôle concurrent de `occurrence_count` empêche de clôturer un dossier
dont l'état a changé pendant l'enquête. Les décisions sont ordonnées par
numéro de séquence croissant de base, non par UUID ni horodatages susceptibles
de se répéter.

**Aucune mutation de :** `payments`, `merchant_payouts`, `merchant_ledger_entries`,
`provider_collections`, `saspay_audit_incidents` ou du solde du fournisseur.
**Aucun appel POST** à SasPay dans ce lot.

## Limites explicites

- Cette console ne remplace pas un rapprochement comptable quotidien sur les
  soldes et les obligations de toutes les vendeuses.
- Les UUID bruts des opérations ne sont pas affichés ; seules des empreintes
  tronquées permettent la corrélation administrateur des dossiers.
- La clôture administrative ne lève jamais à elle seule un blocage financier ou
  un `reconciliation_required`.
- La résolution financière effective d'un retrait ou remboursement passe
  exclusivement par les workflows probatoires existants.
- La liste affiche les 100 décisions les plus récentes du dossier.

## Revue avant fusion

Exécuter les vérifications de migration PostgreSQL, tous les tests backend,
le build Angular, les tests UI et les suites de sécurité. Tester la route
avec un utilisateur non autorisé et un opérateur explicitement autorisé.
Cette PR ne change aucun plafond SasPay et n'active aucun service en production.
