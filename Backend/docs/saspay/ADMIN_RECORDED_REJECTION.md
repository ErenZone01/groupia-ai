# Rapprochement administrateur des refus SasPay historiques

Les nouvelles réponses d'initialisation `403 payout_not_enabled` sont déjà traitées
automatiquement comme `FAILED` par Groupia (PR #90). Le commerçant peut demander
un nouveau devis et réessayer, sans intervention administrative.

## Cas historique : refus 403 déjà consigné dans Groupia

Pour les anciennes intentions créées avant la PR #90, utiliser l'API de revue
**uniquement après vérification des preuves fournisseurs et du journal HTTP source**.
La confirmation de l'activation récente du compte prouve l'état de l'habilitation,
mais ne remplace pas à elle seule la preuve d'une non-exécution pour chaque tentative.

Seul l'opérateur explicitement autorisé par les paramètres existants
`GROUPIA_PAYOUT_ADMINISTRATION_ENABLED` et
`GROUPIA_PAYOUT_ADMINISTRATION_OPERATOR_IDS`, et possédant les deux permissions
`EXECUTE_PAYOUTS` et `MANAGE_SETTLEMENTS`, peut utiliser ce chemin.

Préparer la preuve : journal backend corrélé à l'UUID du payout, réponse
HTTP 403 exacte avec code `payout_not_enabled`, inventaire complet des
essais pour sa clé, absence de fournisseur ID et absence de transfert en attente.
Conserver le journal et l'email SasPay hors Git, dans le dossier d'audit.

Endpoint :

```http
POST /api/organizations/{organizationId}/merchant-payouts/{payoutId}/administration/recorded-rejection
Authorization: Bearer <session admin>
Content-Type: application/json

{
  "expectedVersion": 2,
  "providerCode": "payout_not_enabled",
  "evidenceReference": "GROUPIA-LOG-2026-11-17T0525Z",
  "reason": "Rejet HTTP 403 confirmé, tentatives revues et aucune exécution future possible.",
  "allAttemptsReviewed": true,
  "definitiveNonExecution": true,
  "futureExecutionImpossible": true,
  "confirmed": true
}
```

**Les trois déclarations `true` sont une attestation administrative, pas une
preuve cryptographique du fournisseur.** Ne les déclarer qu'après revue
effective du dossier. La référence de preuve est un identifiant du document
archivé ; la base stocke un digest de l'attestation, pas la réponse brute SasPay.

Le backend verrouille la boutique et n'accepte la clôture que si :
- le statut actuel est `REVIEW_REQUIRED` avec
  `reconciliation_reason=SASPAY_PAYOUT_HTTP_REJECTED` ;
- le payout appartient à la boutique, à un compte SASPAY courant, à une vente
  directe, n'a pas d'identifiant de retrait externe et conserve sa réservation ;
- la version soumise est exactement la version courante et aucun incident
  financier contradictoire n'existe.

Une seule transaction PostgreSQL insère l'audit immuable
`merchant_payout_administrations` puis clôture le payout à `NON_EXECUTE`.
Le trigger financier existant exige l'audit avant la transition. L'ancien
payout, sa clé d'idempotence et ses données de bénéficiaire ne sont jamais
effacés ni rejoués. Une répétition identique retourne le même audit ; une
nouvelle preuve/acteur/motif est un conflit.

Le solde de la vente devient disponible après la transaction : un **nouveau**
devis et une **nouvelle** clé d'idempotence sont obligatoires pour tout retrait
suivant. Ce endpoint ne déclenche jamais lui-même un POST SasPay.

**DIR-21 :** `PAYOUT_MANUAL_RECOVERY_UNCERTAIN` est expressément exclu du
chemin simplifié. Une vraie preuve fournisseur ou la clôture administrée
précédente est requise ; ne pas faire de `UPDATE` manuel ni copier le motif
de DIR-24 dans cette ligne.

Pour la production grand public, les vendeuses n'accèdent jamais à cet
endpoint. Elles retrouvent leur solde automatiquement après un refus
authentifié ; dans les autres cas elles voient `À vérifier` et un opérateur
habilité résout l'incident. Un contrôle supplémentaire par le fournisseur
reste nécessaire pour les timeouts ou POST avec issue inconnue.
