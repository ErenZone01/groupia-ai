# Vente directe — tarifs de livraison régionaux V1

## Fonctionnement
Chaque gérante peut enregistrer un **montant fixe en F CFA par région** depuis sa page de
vente directe. Une valeur vide signifie « non tarifé » ; **0 F CFA** signifie explicitement
« livraison gratuite ». Aucun prix n'est inféré à partir de la distance ou de Geoapify.

Deux endpoints de gestion, authentifiés et isolés par organisation :
- `GET /api/organizations/{organizationId}/direct-store/shipping-fees`
- `PUT /api/organizations/{organizationId}/direct-store/shipping-fees`
  avec `{"regionFeesXof":{"Dakar":1500},"version":0}`.
Permission `VIEW_CATALOG` en lecture, `MANAGE_CATALOG` en écriture, verrou sur
la boutique et verrouillage optimiste par `version` pour éviter les écrasements.
Les tarifs sont conservés en JSONB par boutique et enregistrés dans une migration
additive `036-direct-shipping-fees.sql`. Aucun changement des migrations gelées.

## Devis public
`POST /api/public/direct-stores/{publicIdentifier}/shipping-quote` :
```json
{
  "fulfillmentMethod": "DELIVERY",
  "lines": [{ "offerId": "UUID", "quantity": 1 }],
  "region": null,
  "deliveryPoint": { "latitude": 14.6937, "longitude": -17.4474 }
}
```
Le backend lit les prix courants des articles et les frais de plateforme, calcule
la région à partir de sa carte locale pour un point GPS et vérifie la politique de
livraison de la boutique. La région manuelle reste une déclaration non vérifiée.

- `QUOTED` : frais et total estimé renvoyés ;
- `FEE_UNCONFIGURED` : tarif inconnu (pas 0) ;
- `OUTSIDE_ZONE`, `OUTSIDE_SENEGAL`, `UNCERTAIN`, `DELIVERY_DISABLED` :
  pas de frais/total proposés, livraison non autorisée ou zone non vérifiée.
- `PICKUP` : frais de livraison 0, sans région ni GPS.
- Prix entiers F CFA de 0 à 100 000 par région ; valeurs négatives, inconnues,
  fractions ou noms de région invalides refusés.

La réponse ne déclenche aucune commande, aucune réservation, aucun paiement.
`paymentAvailable` reste **false**. La page cliente montre le sous-total,
les frais de plateforme, les frais de livraison et le total **uniquement si cotés**.
Un devis peut changer entre la prévisualisation et la commande.

## Protection, confidentialité et limites
- Les coordonnées GPS ne sont pas sauvegardées par le service de devis.
- Les tarifs côté navigateur ne font pas autorité ; le serveur recalcule les prix.
- La protection régionale déjà créée sur le checkout ne dépend pas de Geoapify.
- **Il reste à intégrer le tarif dans l'agrégat de commande**, avec un snapshot
  historique, réévaluation sous verrou, acceptation explicite par la cliente,
  validation des adresses manuelles et paiements Bictorys idempotents.
- `GROUPIA_DIRECT_CHECKOUT_ENABLED=false` demeure le défaut.
- Ce tarif fixe régional est un premier modèle pour le Sénégal, pas une tarification
  par kilomètre ni une preuve de livraison faisable à une adresse spécifique.

Tests : GitHub Security, Maven verify/Spotless, tests Angular et Playwright ;
vérifier une région couverte, une non couverte, un tarif gratuit explicite,
une région non tarifée et les changements de boutique.
