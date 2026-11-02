# Vente directe — contrôle de destination au checkout (tranche suivante)

## Objectif
Ne plus faire confiance au précontrôle GPS public du frontend : lors de
`POST /api/public/direct-stores/{publicIdentifier}/checkout`, Spring Boot
recalcule la région de livraison depuis **les coordonnées reçues dans cette
requête**, puis applique la politique de zones **actuelle** de la boutique dans
la même transaction que la création du brouillon.

Aucun changement de paiement : `GROUPIA_DIRECT_CHECKOUT_ENABLED=false`
par défaut, et `paymentAvailable=false`, même avec un point GPS couvert.

## Contrat des commandes
Corps historique inchangé : `contact`, `fulfillmentMethod`, `lines`.
Propriété supplémentaire facultative à la racine :

```json
{
  "contact": {
    "firstName": "Awa",
    "lastName": "Ndiaye",
    "phone": "+221771234567",
    "deliveryAddress": {
      "country": "SN",
      "details": "Près de la pharmacie"
    }
  },
  "fulfillmentMethod": "DELIVERY",
  "deliveryPoint": { "latitude": 14.6937, "longitude": -17.4474 },
  "lines": [
    {
      "offerId": "remplacer-par-uuid",
      "quantity": 1,
      "expectedUnitPriceXof": 2000,
      "expectedPriceRevision": 1
    }
  ]
}
```

`Idempotency-Key` obligatoire (16–128 caractères valides) si ce brouillon
est activé. Les articles et prix proviennent du devis serveur existant.
Le `deliveryPoint` est une **destination de livraison choisie**, pas une
preuve que la cliente est physiquement présente sur place.

- **Livraison GPS :** le serveur calcule la région via les limites
  administratives embarquées, vérifie le mode `ALL/SELECTED/DISABLED` de
  la gérante et refuse les positions hors zone, hors du Sénégal ou proches
  d'une frontière indéterminée. La région envoyée par le navigateur est
  ignorée.
- **Livraison manuelle :** formulaire actuel conservé. Un brouillon peut
  toujours être créé si la région déclarée est dans les zones permises,
  mais le snapshot est marqué `UNVERIFIED_MANUAL`. Cette indication doit
  impérativement empêcher une future autorisation automatique de paiement
  ou de livraison tant que la gérante n'a pas confirmé l'adresse.
- **Retrait :** aucun point GPS ni adresse de livraison ; les anciennes
  règles sont conservées.

Les snapshots de livraison distinguent `locationMethod=MAP` et
`locationMethod=MANUAL`. Un point carte porte la région dérivée, la source
des limites et `coverageStatus=REGION_MATCHED`. Cela signifie seulement
qu'un point *fourni* est à l'intérieur d'une région déclarée autorisée.
Les snapshots restent privés, et ne sont jamais transmis aux analyses.

## Idempotence et compatibilité
Les requêtes de retrait et de livraison manuelle gardent **exactement**
leur empreinte idempotente historique. Les nouvelles requêtes GPS ont
une empreinte distincte intégrant le point : changer de coordonnées sous
la même clé provoque un conflit (409), sans créer de seconde commande.

## Limites / étapes suivantes
Ce travail est exclusivement backend ; le formulaire public reste en
**prévisualisation sans création de commande**. Ne pas activer le checkout
sur cette seule tranche. Avant paiement :
- devis de livraison explicite et accepté, différent des seuls articles ;
- étapes de validation d'adresse manuelle / hors frontières, avec statuts ;
- parcours client de création de commande et écran de retour ;
- machine d'états de paiement Bictorys et webhooks idempotents dédiés ;
- reprise après erreur, suivi et vérification de la réception.

Tests : ancienne API, adresse manuelle, GPS Dakar/Thiès/Abidjan/invalide,
ignorance de région client mensongère, empreinte idempotente et séparation
strictement conservée d'avec les commandes de groupage.
