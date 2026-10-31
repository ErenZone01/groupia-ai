# Groupia — Vitrine publique et panier UX V2

## Périmètre

Refonte des pages **publiques** `/public/groupages/:publicIdentifier` et
`/public/groupages/:publicIdentifier/cart` selon les maquettes approuvées.

- Hero de groupage, informations de clôture, mode de transport et statut :
  **tous dérivés de l'API** `PublicGroupingView`.
- Aucune route Chine → Dakar, date d'arrivée, destination ou nom de boutique codé en dur.
  La maquette utilisait un exemple illustratif ; l'API actuelle ne fournit pas toujours
  de trajet ou de date d'arrivée.
- Les catégories, favoris et stocks quantitatifs ne sont pas inventés : seules les
  **offres**, variantes et disponibilités déterminées par le modèle public sont affichées.
- Recherche locale parmi les offres renvoyées (nom commercial, nom catalogue, description,
  attributs de variante) sans appel API ou recherche globale simulée.
- Galerie de photos : affichage de l'original `displayUrl` quand disponible,
  `object-fit: contain`, miniatures accessibles et changements d'image par carte.
- Panneau « Ma sélection » (desktop) et barre de panier (mobile), utilisant le
  `CartStore` réel, sans doubler le lien de panier accessible à l'écran.
- Panier avec photos, quantités modifiables, sous-total et accès au checkout existant.
- Les contrôles d'acceptation d'un nouveau prix et d'indisponibilité restent actifs :
  jamais de passage en commande si `cart.ready()` est faux.
- Les frais de transport sont décrits à partir de
  `shippingTerms.shippingCollectionMode` :
  **AFTER_FINAL_ASSESSMENT** = évalués après la commande ;
  **ESTIMATED_AT_CHECKOUT** = estimés lors de la commande ;
  autre/absent = indéterminés à préciser. Aucune somme n'est inventée.
- Conservation des règles de disponibilité, sécurité publique sans jeton, identifiants
  de commandes, snapshot des prix et validations checkout. Aucun backend modifié.

## Architecture frontend

- `PublicGroupagePage` : chargement public, recherche, état global et panier mobile.
- `PublicOfferCard` : image/miniatures, prix/variante, ajout.
- `PublicSelectionPanel` : sélection réactive, quantités et récapitulatif.
- `PublicShell` : option `wide` exclusivement pour le panier ; autres vues inchangées.
- `CartPage` / `CartLines` : présentation responsive et parcours de commande existant.
- CSS réparti entre composants pour rester sous le budget Angular de 8 Ko par composant.

## Vérifications nécessaires

GitHub Actions est temporairement bloqué par le quota mensuel : ces vérifications
**ne sont pas attestées comme exécutées** depuis l'éditeur distant.

Dans `Frontend` :

```powershell
npm run lint
npm test
npm run build
npx playwright test e2e/public-storefront-ux-v2.spec.ts
npm run test:e2e
```

Vérifier manuellement les versions 320/360/390/768/1440 px en clair/sombre,
une campagne sans description / date, une campagne fermée, les photos verticales
et longues, une variante indisponible, les modifications de prix après sélection,
le panier vide et les frais selon leur mode de collecte.

## Chaîne des PR

Cette branche est basée sur `groupage-wizard-ux-v1` (PR #42),
elle-même basée sur `catalog-ux-v3` (PR #41). Ne pas fusionner prématurément
sur `main` sans résoudre les dépendances et vérifier les contrôles obligatoires.
