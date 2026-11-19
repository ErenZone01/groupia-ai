# SasPay — contrôle périodique de l'historique (première tranche)

Cette tranche interroge exclusivement `GET /transactions/` de SasPay avec
`merchant`, `created_at__gte`, `created_at__lte`, `page_size=100` et un curseur
de pagination. Les pages sont rapprochées, **sans aucune modification des états
financiers**, avec `payments` et `merchant_payouts` via le couple
`(provider_account_reference, provider_transaction_id)`.

## Activation contrôlée

Désactivé par défaut :

```dotenv
SASPAY_HISTORY_AUDIT_ENABLED=false
SASPAY_HISTORY_AUDIT_INTERVAL_MS=900000
```

La tâche ne peut être instanciée que si `SASPAY_ENABLED=true` **et**
`SASPAY_HISTORY_AUDIT_ENABLED=true`. Vérifier les autorisations d'accès,
quotas et règles contractuelles du fournisseur avant activation. Aucune
modification du plafond d'encaissement (1 000 000 XOF par défaut) ou des
retraits n'est effectuée dans cette tranche.

À chaque exécution, la tâche inspecte les **48 dernières heures UTC**, avec
une fenêtre de recouvrement pour détecter les confirmations tardives. Les
pages sont lues jusqu'à `next=null`, avec maximum 30 pages de 100 opérations.
Le curseur provenant du fournisseur est analysé mais **jamais utilisé comme
URL à appeler** ; le client construit lui-même chaque requête vers l'hôte
SasPay déjà configuré. Une pagination tronquée ou cyclique fait échouer le
contrôle, qui ne peut alors pas être considéré complet.

## Contrôles

- Compte marchand SasPay attendu, devise XOF, sens/type d'opération.
- Correspondance unique entre identifiant fournisseur et paiements/retraits Groupia.
- Montant et état Groupia comparés à l'état SasPay ; écart ou validation
  fournisseur non encore confirmée localement = anomalie.
- Pour les encaissements réussis : mode ADD_ON, net, frais et total débité,
  avec comparaison aux justificatifs locaux lorsqu'ils sont présents.
- Reconnaissance des décaissements SasPay `RETRAIT` **ou** `TRANSFERT`.
  Les frais et bénéficiaires des retraits restent contrôlés par le
  vérificateur de versement existant à deux GET indépendants.

Les logs comportent des codes d'anomalie et un **empreinte tronquée de
l'identifiant fournisseur**, jamais les montants, numéros de téléphone, clés,
URL de paiement ni contenu brut fournisseur. Une transaction inconnue de
Groupia peut être un mouvement SasPay externe : elle est signalée pour revue,
mais n'entraîne jamais une correction du solde ou un retrait automatique.

## Limites de cette première tranche

Il s'agit d'un contrôle **périodique en lecture seule**, pas d'un système
définitif de clôture comptable. Aucun nouveau POST, remboursement, rapprochement
de wallet, gel automatique ou écriture financière n'est déclenché.

- Une panne supérieure à 48 h nécessite une **reprise historique dédiée**,
  pas encore implémentée ici.
- Les alertes sont dans les logs d'exploitation ; pas encore de tableau de
  suivi persistant, d'accusé de traitement ni de métriques administrateur.
- Les transactions Groupia absentes de la liste fournisseur demandent
  un contrôle inverse / une réconciliation par identifiant et un contrôle
  d'exhaustivité du wallet, à traiter dans la tranche suivante.
- Une liste SasPay `SUCCESS` ne permet **jamais** d'activer un abonnement,
  confirmer une commande ou libérer un retrait : seuls les parcours
  indépendants de vérification et les réservations existants ont cette
  autorité.

Tests de contrat : `SaspayHistoryAuditTests`, serveur HTTP SasPay local
synthétique. Après fusion et revue, activer explicitement en recette
et vérifier les alertes avant tout usage en production.
