# Vente directe — démarrer la préparation après règlement vérifié (V1)

## Commande marchande

`POST /api/organizations/{organizationId}/direct-orders/{orderId}/start-preparation`

Exige la permission `MANAGE_FULFILLMENT` de l'organisation concernée. Cette
commande ne prend pas de montant ni d'identifiant de transaction depuis le
navigateur. Réponse : `orderNumber`, `status=PREPARING`,
`preparingAt`. Les champs `preparing_at` et `preparing_by` journalisent
l'instant et l'auteur de la transition. Le double clic est idempotent.

## Règles et verrouillages

- Il faut partir de `PAYMENT_CONFIRMED`, jamais d'une commande annulée
  ou simplement préparée au paiement.
- La transaction verrouille successivement vitrine puis commande (ordre
  commun au checkout et à l'annulation) puis l'obligation financière.
- Le journal immuable `direct_order_verified_settlements` doit désigner
  une seule preuve correspondant à la commande et à l'organisation.
- Le module paiement doit confirmer l'obligation `DIRECT_ORDER_INITIAL`
  `SETTLED`, `CLOSED`, sans tentative active, sans anomalie ouverte et
  sans réconciliation en cours.
- `settlementEvidence` doit confirmer le même paiement `SUCCEEDED`,
  non inversé, avec le même montant XOF, la même transaction fournisseur
  et le même instant de confirmation que la commande et le reçu serveur.
- La mise à jour est conditionnelle à l'état précédent et à l'existence
  du reçu immuable ; une transaction incohérente échoue sans modification.
- Les événements de paiement rejoués après `PREPARING` restent
  idempotents et ne régressent pas le statut commercial.
- Les commandes historiques ne sont pas modifiées par la migration.

## Limites intentionnelles

`PREPARING` permet à la gérante de **commencer la préparation interne
des articles**, mais ne constitue PAS une autorisation de livrer, de
confirmer un retrait, de rembourser ou de reverser des fonds.

Un retournement ou un litige **après** le début de la préparation doit
être contrôlé de nouveau à chaque future transition de remise/livraison.
L'API de lecture `payment-status` conserve `fulfillmentAllowed=false`.

Le paiement direct Bictorys reste explicitement bloqué par
`DIRECT_PAYMENT_NOT_ENABLED`. Aucun appel au fournisseur n'est ajouté
dans cette tranche.

## Vérification

- Migration PostgreSQL `043` : `PREPARING`, audit non nul et
  chronologie cohérente avec `payment_confirmed_at`.
- Tests unitaires : règlement conforme, rejeu, absence de preuve,
  paiements litigieux/reversés, refus de commande impayée ou annulée,
  permissions et transaction sans mise à jour.
- Test PostgreSQL du checkout : accès non authentifié, refus sur commande
  impayée, interdiction cross-tenant et refus après annulation.
- Test de rejeu Modulith après début de préparation.
- Ne fusionner qu'après Security, Backend (tests, migrations, Modulith,
  Spotless) et Frontend réussis.
