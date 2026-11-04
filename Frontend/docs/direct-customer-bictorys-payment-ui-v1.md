# Vente directe — paiement client Bictorys (interface V1)

## Comportement

La page privée de suivi de commande utilise le jeton 256 bits dans le fragment
d'URL et dans les corps de ses appels POST uniquement.

Le bouton « Payer … avec Bictorys » n'apparaît **que si** :
- le statut serveur financier est `PREPARED` et `paymentAvailable=true` ;
- la commande est encore `PENDING_PAYMENT_INTEGRATION` ;
- les deux lectures indépendantes correspondent sur le numéro public, la
  monnaie XOF et le total accepté, strictement positif ;
- la page ne signale aucun litige, incohérence ou paiement déjà lancé.

Le backend renseigne `paymentAvailable=true` uniquement lorsque l'activation
`groupia.direct-sales.payment-initiation.enabled` a été explicitement
autorisée, qu'un état `PREPARED` valide est visible et que Bictorys est
disponible. La valeur par défaut du feature flag est **false**.

L'initiation `POST /api/public/direct-orders/payments` contient seulement le
jeton et l'en-tête `Idempotency-Key`. Le montant, la boutique, l'identité,
le prestataire et la devise restent contrôlés et revérifiés côté serveur.

Après réponse de l'initiation, la page n'affiche **jamais « payé »**.
Le lien du prestataire, si reçu, est validé par une liste de domaines HTTPS
Bictorys autorisés et exposé comme action volontaire, avec ouverture
dans un nouvel onglet, `noopener`, `noreferrer` et `no-referrer`.
Une tentative en attente bloque tout deuxième clic.
Une erreur réseau conserve la clé d'idempotence en mémoire jusqu'à reprise,
afin qu'un réessai n'entraîne pas deux demandes distinctes.
La rotation du jeton invalide les réponses arrivées en retard.

Seuls les faits financiers vérifiés par le backend, le webhook authentifié et le
journal immuable peuvent changer le statut de paiement confirmé.

## Avant l'ouverture du pilote

Ne pas activer le flag par défaut. Il faut d'abord valider, en environnement
Bictorys sandbox, les webhooks, les échecs incertains, les remboursements,
la réconciliation et la concurrence paiement/annulation. Les intégrations
réelles doivent également être vérifiées avec l'acheminement légal et
contractuel des fonds.

Cette tranche n'implémente ni reversement ni livraison effective.
