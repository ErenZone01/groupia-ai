# Livraison directe — position en un clic et carte (prévisualisation V1)

## Expérience client

Sur `/v/:publicIdentifier/coordonnees`, choisir « Livraison éventuelle » présente :
1. **Utiliser ma position** : demande explicite de permission GPS via l'API Web
   `navigator.geolocation.getCurrentPosition` (HTTPS ou localhost requis) ;
   timeout 12 secondes, sans géolocalisation continue.
2. **Choisir un endroit sur la carte** : carte MapLibre GL JS alimentée par OpenFreeMap,
   repère positionnable au toucher ou déplaçable.
3. **Saisir mon adresse sans carte** : formulaire Sénégal existant (région, ville,
   commune, quartier et point de repère). Toujours disponible, sans GPS.

Une position GPS repérée doit être **confirmée** par la cliente ; la carte n'est
pas ouverte au chargement de la boutique (téléchargement à la demande).
Changement de boutique/retrait réinitialise les coordonnées et ignore un
callback de géolocalisation ancien. L'interface ne stocke ni ne transmet les
coordonnées à l'API de commande, à Bictorys ou à la gérante.

**Confidentialité :** OpenFreeMap reçoit les requêtes nécessaires au téléchargement
des tuiles affichées ; cela peut révéler approximativement la zone visualisée
par la cliente. Ajouter ce prestataire à l'information de confidentialité
avant exposition grand public. Pas de suivi GPS en arrière-plan.

## Limites de cette tranche

- Le mode `MAP` est uniquement une prévisualisation locale ; le point GPS n'est
  pas encore conservé comme adresse de commande. Aucune commande ni paiement
  n'est déclenché.
- Le **géocodage inverse** (coordonnées → région/ville/commune) n'est **pas
  encore branché**. Geoapify nécessitera une clé API, stockée côté serveur,
  des protections anti-abus et une règle de confidentialité adéquate.
- Les règles régionales actuelles (`direct_delivery_policies`) continuent
  d'être fondées sur la région déclarée pour les formulaires manuels.
  Une localisation sur carte ne prouve pas l'éligibilité à une livraison.
  **Interdire les paiements directs** tant que la vérification côté serveur
  des coordonnées avec des polygones administratifs vérifiés, les coûts de
  livraison et les limites de vente n'ont pas été intégrés.
- Carte centrée sur Dakar sans GPS ; déplacer/cliquer sur la carte est obligatoire
  avant de confirmer. Prévoir une meilleure orientation initiale pour les pays
  ouest-africains lors de l'internationalisation.

## Installation locale nécessaire pour cette PR

MapLibre 6 est déclaré dans `Frontend/package.json`, mais le connecteur GitHub
ne dispose pas de l'accès au registre npm pour générer le lockfile.
Le lockfile **doit** être mis à jour et commité avant fusion :

```powershell
cd Frontend
npm install --save-exact maplibre-gl@6.16.0
npm run lint
npm test
npm run build
npx playwright test e2e/direct-sales-v1.spec.ts
cd ..
git add Frontend/package.json Frontend/package-lock.json
git commit -m "build(map): lock MapLibre 6.16.0"
git push
```

Le worker MapLibre 6 et `maplibre-gl-shared.mjs` sont copiés dans les assets
par `angular.json`. Ne pas utiliser de CDN JavaScript non épinglé.

## Vérifications manuelles essentielles

- Sur HTTPS, autoriser le GPS, déplacer le repère puis confirmer.
- Refuser le GPS, choisir manuellement un point sur la carte.
- Couper le réseau ou le WebGL : la saisie manuelle reste disponible.
- Retrait : aucun GPS demandé, aucun formulaire d'adresse obligatoire.
- Basculer de boutique pendant qu'une permission GPS est en cours :
  la position ne doit pas apparaître dans la nouvelle boutique.
- Tester sur Android bas de gamme et faible connexion.
- Vérifier que `groupia.direct-sales.checkout.enabled=false` et que
  rien n'a changé dans le flux de paiement des groupages.

Sources : https://openfreemap.org/quick_start/
https://maplibre.org/maplibre-gl-js/docs/
