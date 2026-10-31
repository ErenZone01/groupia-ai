# Vente directe — V1, tranche 2A : panier et devis serveur

## Parcours livré

Sur la vitrine publique `/v/{publicIdentifier}`, une cliente peut ajouter les variantes
publiées au panier et accéder à `/v/{publicIdentifier}/panier`. Le panier réside
uniquement en mémoire dans l'onglet. Il est remis à zéro à tout changement de
boutique. Aucun prénom, téléphone, adresse, token ou prix n'y est conservé.

Le panier affiche une **estimation non contractuelle** du prix actuel des articles,
calculée par le serveur. La future création de commande devra valider les offres
et les prix **à nouveau dans sa propre transaction**, puis historiser ses montants
avant de lancer un paiement. Les prix sont en XOF, avec des entiers exacts.

Le bouton Acheter/Payer n'est pas exposé dans cette tranche. Le backend ne crée
**ni commande, ni paiement, ni réservation**, et ne modifie aucune campagne de
groupage. Les coûts de livraison et de plateforme ne sont pas intégrés au total
intermédiaire, avec une indication visible sur le parcours.

## Contrat public

`POST /api/public/direct-stores/{publicIdentifier}/quote`

```json
{ "lines": [{ "offerId": "uuid", "quantity": 2 }] }
```

Réponse **200** (avec `Cache-Control: no-store`) :
```json
{
  "currency": "XOF",
  "displayName": "Boutique",
  "goodsSubtotalXof": 17000,
  "checkoutAvailable": false,
  "lines": [{
    "offerId": "uuid",
    "variantId": "uuid",
    "quantity": 2,
    "unitPriceXof": 8500,
    "lineTotalXof": 17000,
    "priceRevision": 1,
    "productName": "Produit",
    "attributes": {}
  }]
}
```

Refus **400** si panier vide, plus de 20 lignes, quantité hors 1..100,
doublons, UUID absent ou somme supérieure au plafond serveur.
Refus **404** si vitrine inconnue ou organisation désactivée.
Refus **409** si une offre a été dépubliée, retirée, ou si sa variante n'est
plus commercialisable. La réponse ne divulgue pas les données des autres
organisations.

Aucun montant n'est accepté en entrée : la vérité tarifaire provient de
`direct_offers` et du catalogue de l'organisation. Le devis n'est pas une
réservation de stock ni un engagement de prix.

## Suite (tranche 2B)

- Modèle de commandes `DIRECT` indépendant de `grouping_campaigns` et
  séparé des commandes `GROUPING`, avec snapshots immuables des lignes.
- Coordonnées client, adresse de livraison/retrait et validation explicite du
  montant total (dont frais de plateforme) avant confirmation.
- Protection idempotente anti-double commande et anti-double débit ;
  paiement Bictorys avec webhooks vérifiés et suivi client.
- Rapports et reversements isolés par organisation et par mode de vente.
- Mise à jour du panier à la réservation, suivi des indisponibilités,
  tests de concurrence et d'intégrité des montants.

## Vérifications recommandées sur la branche

```powershell
git fetch origin
git switch --track origin/direct-sales-cart-quote-v1
cd Backend
.\mvnw.cmd spotless:check
.\mvnw.cmd "-Dtest=DirectStorefrontIntegrationTests,ModularityTests" test
.\mvnw.cmd verify
cd ..\Frontend
npm run lint
npm test
npm run build
npx playwright test e2e/direct-sales-v1.spec.ts
```

`spotless:check` ne modifie rien. Si Spotless échoue sur les nouveaux fichiers
Java, exécuter `spotless:apply` en sachant que cette commande modifie le
formatage local, puis relancer `spotless:check` avant de committer.

Les migrations 001–032 sont inchangées dans cette tranche.
