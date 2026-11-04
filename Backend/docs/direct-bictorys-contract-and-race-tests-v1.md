# Vente directe : contrat de tests Bictorys avant pilote

## Ce que vérifie la suite automatique

`DirectBictorysSandboxIntegrationTests` utilise le vrai contexte
Spring Boot, PostgreSQL via Testcontainers, l'API publique et le module
de paiement existant. Les interactions Bictorys sont dirigées vers un
serveur HTTP **local et synthétique**, sans identifiants ni fonds réels.

Parcours contrôlés :
1. Le jeton privé d'une commande éligible conduit à un état payable.
2. L'initiation émet un seul appel Bictorys pour une clé idempotente,
   avec le total XOF et le contact reconstruits depuis les snapshots serveur.
3. La réponse fournisseur fournit une URL autorisée, mais **ne
   confirme jamais un paiement**.
4. Seul un webhook authentifié et cohérent avec la référence, l'identité
   de la transaction et le montant crée la preuve de règlement
   append-only et confirme la commande. Les doublons sont idempotents.
5. Les webhooks non authentifiés et les montants falsifiés ne règlent rien.
6. Une commande annulée ou un jeton révoqué ne peut plus initier de
   paiement.
7. La concurrence entre annulation commerçante et initiation cliente
   respecte l'ordre des verrous et n'admet jamais une commande
   annulée qui a créé une charge fournisseur.

## Limites avant une activation réelle

Ce test de contrat local **n'est pas une validation réelle du sandbox
Bictorys distant**. Les conditions restantes comprennent : accès à la
sandbox du prestataire, vérification des champs/reçus réels, signature
webhook effective, délais réseau, reprise après redémarrage, remboursement,
litiges, attribution du compte commerçant, plafonds et reversements.

Le flag `groupia.direct-sales.payment-initiation.enabled` reste
`false` par défaut. Le test le passe à `true` **uniquement dans son
contexte de test**. Aucune configuration de production n'est changée.

## Précision des horodatages de règlement

Le paiement confirmé publie un événement asynchrone et sa preuve est relue
depuis PostgreSQL. Java permet les nanosecondes, mais `TIMESTAMPTZ` conserve
les microsecondes. `Payment.apply` utilise désormais une date canonique
tronquée à la microseconde, identique pour l'événement et la preuve persistée.
La suite d'intégration vérifie l'égalité stricte entre `payments.confirmed_at`
et `direct_orders.payment_confirmed_at`. Cette normalisation ne contourne
ni la vérification des montants ni l'exigence de preuves fournisseur.
