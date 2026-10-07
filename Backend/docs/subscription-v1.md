# Subscription V1

## Périmètre

Le module `subscription` donne à chaque boutique un essai gratuit d'un mois
calendaire, puis expose son entitlement, ses coordonnées de facturation et
l'historique des périodes payées. Le trial est calculé dans le fuseau
`organizations.timezone` ; il n'est pas assimilé à 30 jours.

Les boutiques déjà présentes lors du déploiement reçoivent un essai attribué au
cutover enregistré par Liquibase (`LEGACY_MIGRATION_TRIAL`). Cette date est
distincte de leur création d'origine. Le provisioning déclenché par
`OrganizationCreated` et son chemin de secours sont idempotents.

## Entitlement et continuité

Le serveur calcule l'accès depuis les dates de trial et les seules périodes
`PAID` qui ont été enregistrées après validation d'une preuve serveur. Le
navigateur ne transmet ni montant ni statut faisant autorité.

Quand l'entitlement ne permet plus de démarrer une nouvelle activité :

- la création d'un groupage et l'ouverture d'un brouillon sont refusées avec
  `403 SUBSCRIPTION_REQUIRED` lorsque l'application de la règle est activée ;
- les brouillons restent consultables et modifiables ;
- un groupage déjà ouvert reste consultable par le public et peut recevoir des
  commandes ;
- les workflows commandes, paiements, transport, préparation/remise, refunds,
  settlement et clôture restent accessibles.

L'expiration ne révoque pas les commandes ni les obligations existantes.
L'indicateur d'application globale est `GROUPIA_SUBSCRIPTION_ENFORCEMENT_ENABLED`
(désactivé par défaut dans la configuration fournie).

## API et permissions

Les routes sont sous `/api/organizations/{organizationId}/subscription` :

- `GET` : entitlement, état de configuration du plan/provider et contact ;
- `GET /billing-contact`, `PUT /billing-contact` : lecture/modification des
  coordonnées ;
- `POST /start`, `POST /cancel` : démarrage ou annulation côté propriétaire ;
- `GET /history` : périodes payées.

OWNER et MANAGER peuvent consulter. OWNER seul peut modifier le contact,
démarrer ou annuler. Le prix et le code plan proviennent de la configuration
serveur ; les coordonnées requises sont validées côté serveur.

## Paiement et disponibilité

La configuration du customer/subscription chez un provider ne crée aucune
période payée et ne donne aucun accès payant. La seule façon d'ajouter une
période `PAID` est le traitement interne d'une preuve validée ; aucune preuve
webhook Bictorys pour les abonnements n'est encore reconnue.

Le provider est indisponible par défaut (`GROUPIA_BICTORYS_SUBSCRIPTIONS_ENABLED=false`).
Un démarrage demandé dans cet état retourne explicitement
`503 SUBSCRIPTION_BILLING_UNAVAILABLE`. L'application, les entitlements et les
workflows existants restent utilisables. Ne pas activer le provider Bictorys
avant validation du contrat de paiement récurrent et de sa preuve de règlement.

Le montant mensuel XOF et le plan provider ne sont pas préremplis avec une
valeur de production. Ils doivent être fournis par configuration serveur avant
de rendre le démarrage possible.

## Schéma et migrations

La migration `021-subscriptions.sql` crée l'abonnement unique par boutique,
le contact de facturation, les périodes et les métadonnées de cutover, puis
attribue un trial aux boutiques existantes de façon idempotente. Les migrations
001–020 sont immuables. Après application de 021, tout correctif de schéma
nécessite une migration ultérieure.

Voir aussi [règles métier](business-rules.md), [modèle métier](domain-model.md),
[dépendances Modulith](module-dependencies.md) et [ADR-0021](../adr/0021-subscription-entitlements.md).
