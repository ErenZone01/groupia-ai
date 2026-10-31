# Vente directe — tranche 2B, fondation serveur (en revue)

## Portée de cette étape

Cette tranche introduit un modèle de **brouillon de commande directe** qui ne
réutilise pas les commandes de groupage `orders` (liées à `grouping_campaigns`).
La migration `033-direct-orders.sql` crée `direct_orders`,
`direct_order_items` et une séquence `DIR-...`, avec références tenant,
contraintes d'intégrité, frais et prix historisés et idempotence.

**Le checkout public reste désactivé par défaut** via
`GROUPIA_DIRECT_CHECKOUT_ENABLED=false`. Son activation ne doit pas
intervenir avant la finalisation du paiement, des protections anti-abus et de la
validation du parcours utilisateur. Il n'existe pas encore de frontend
"Confirmer la commande" et `checkoutAvailable=false` demeure sur les devis.
Aucun paiement Bictorys, suivi client ni réservation de stock ne sont créés.

## Contrat backend (test/inactif par défaut)

`POST /api/public/direct-stores/{publicIdentifier}/checkout`

En-tête obligatoire :
`Idempotency-Key: <16..128 caractères ASCII autorisés>`

Exemple de requête :

```json
{
  "contact": {
    "firstName": "Awa",
    "lastName": "Diop",
    "phone": "+221771234567"
  },
  "fulfillmentMethod": "PICKUP",
  "lines": [
    {
      "offerId": "00000000-0000-0000-0000-000000000001",
      "quantity": 2,
      "expectedUnitPriceXof": 8500,
      "expectedPriceRevision": 1
    }
  ]
}
```

Pour `DELIVERY`, inclure `contact.deliveryAddress` avec `country: "SN"`,
`region`, `city`, `commune`, `details` et, facultativement, `neighborhood`.
Ce sont des coordonnées et **non une promesse de livraison**. Aucun prix de
livraison ne peut être annoncé à ce stade. `amountBeforeShippingXof` exclut
donc la livraison. Pour `PICKUP`, ne pas transmettre `deliveryAddress`.

Succès initial **201**, relecture idempotente **200** :
```json
{
  "orderNumber": "DIR-000000000001",
  "status": "PENDING_PAYMENT_INTEGRATION",
  "currency": "XOF",
  "fulfillmentMethod": "PICKUP",
  "goodsSubtotalXof": 17000,
  "platformFeeXof": 300,
  "amountBeforeShippingXof": 17300,
  "shippingQuoted": true,
  "paymentAvailable": false
}
```

Ce résultat est **un brouillon interne, pas une commande payée ou acceptée**.
Le même couple `organisation + clé` et le même contenu reçoivent le même
numéro ; la réutilisation de clé avec un contenu différent produit **409**.
Un prix, une révision ou une offre devenus obsolètes sont refusés.
Les montants sont toujours recalculés par le serveur ; les prix attendus en
entrée ne servent qu'à détecter un changement vu par le client.

## Garanties et limites

- 20 lignes maximum, 100 unités par ligne ; vérification d'organisation,
  d'abonnement, de publication et de commercialisation de la variante.
- Verrou transactionnel sur la boutique pour empêcher la création concurrente
  de doublons ou un changement de prix pendant la transaction.
- Snapshots des articles, du tarif, de la révision et des coordonnées client
  dans des tables séparées ; aucun paiement ni notification déclenché.
- La livraison n'a pas encore de tarif ni de promesse de délai ; aucune
  réservation de stock n'est effectuée.
- À compléter avant activation : limitation distribuée des créations publiques,
  politique de conservation des brouillons/PII, intégration Bictorys,
  suivi client avec preuve d'accès, tarification livraison et UI complète.

## Tests locaux recommandés (PowerShell)

```powershell
git fetch origin
git switch direct-sales-checkout-v1
git pull --ff-only
cd Backend
.\mvnw.cmd spotless:check
.\mvnw.cmd "-Dtest=DirectStorefrontIntegrationTests,ModularityTests,OpenApiIntegrationTests" test
.\mvnw.cmd verify
cd ..\Frontend
npm run lint
npm test
npm run build
```

Si `spotless:check` échoue sur les nouveaux fichiers Java, lancer
`./mvnw.cmd spotless:apply`, inspecter `git diff` et refaire `spotless:check`.
Ne jamais modifier rétroactivement les migrations 001–032.
