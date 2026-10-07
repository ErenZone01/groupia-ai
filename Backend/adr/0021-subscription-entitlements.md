# ADR-0021 — Entitlements d'abonnement et continuité des workflows

Date : 2026-10-07. Statut : accepté pour Subscription V1.

## Contexte

Les boutiques ont besoin d'un essai gratuit et d'un abonnement, mais une
expiration ne doit pas annuler des commandes, paiements ou engagements
commerciaux déjà commencés. Aucun contrat de preuve de paiement Bictorys
d'abonnement n'est actuellement validé.

## Décision

Créer un module `subscription` distinct, propriétaire de l'abonnement, du contact
de facturation et des périodes payées. Chaque nouvelle boutique reçoit un mois
calendaire d'essai dans son fuseau horaire. La migration 021 donne aux boutiques
historiques un essai attribué au cutover, avec provenance explicite et backfill
idempotent.

Calculer l'entitlement côté serveur. Le contrôle limite uniquement la création
d'un groupage et l'ouverture d'un brouillon. Il ne s'applique ni au checkout
public d'un groupage déjà ouvert, ni à sa clôture, ni aux workflows commandes,
paiements, transport, remise, refunds ou settlement déjà engagés.

OWNER et MANAGER consultent les informations d'abonnement ; OWNER seul peut
modifier les coordonnées de facturation, lancer ou annuler un abonnement.
L'activation d'une période payée exige une preuve serveur de paiement validée.
Le simple démarrage ou paramétrage d'un abonnement auprès d'un provider ne
constitue pas cette preuve.

Garder le provider d'abonnement désactivé par défaut. N'inventer aucun tarif de
production : le montant XOF et l'identifiant du plan sont une configuration
serveur. Aucun endpoint ne reçoit de montant du navigateur et aucun faux succès
de paiement ne doit être affiché.

## Conséquences et limites

`grouping` n'importe que `subscription::api`; le module subscription n'accède
qu'aux faits publics d'organization. Le provisioning événementiel et le contrôle
à la première résolution sont idempotents pour couvrir les courses.

L'état d'abonnement n'est pas une autorité sur les obligations existantes :
l'accès aux workflows déjà engagés reste inchangé après expiration. Le paiement
automatique et la validation des webhooks d'abonnement Bictorys restent bloqués
jusqu'à un contrat provider fiable. Les migrations 001–020 ne changent pas ;
021 est la seule migration de schéma de cette tranche.

Voir [contrat, permissions et recette](../docs/subscription-v1.md).
