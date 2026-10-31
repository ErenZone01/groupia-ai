# Groupia — Création de groupage, UX V1

Référence visuelle approuvée : `groupia_maquette_creation_groupage_v1.html` (maquette autonome revue le 28/10/2026).

## Portée

- Route existante `/app/groupages/nouveau` ; conservation du layout, des tokens clair/sombre, des contrôles d'accès et du parcours backend.
- Assistant en quatre étapes : Informations → Livraison → Produits → Vérification, avec retour aux étapes déjà visitées, confirmation explicite et résumé en direct.
- Écran d'expédition conforme aux méthodes existantes (AIR, SEA, ROAD, OTHER ; PER_KG, PER_CBM, PER_ITEM, FIXED ; AFTER_FINAL_ASSESSMENT, ESTIMATED_AT_CHECKOUT). La logique de validation reste `validShipping` ; les valeurs additionnelles existantes comme la devise XOF, l'arrondi et le diviseur sont conservées.
- Sélection visuelle des produits actifs et des variantes actives du catalogue, avec miniatures réelles ; recherche uniquement sur la page de catalogue chargée, sans inventer une recherche globale.
- L'éditeur de prix de chaque variante reste le seul endroit où définir un tarif de **l'offre du groupage**, distinct des frais de transport.
- Le composant `ProductPicker` garde son affichage `compact` par défaut pour les écrans de détail déjà implémentés ; seule cette page active `mode="wizard"`.

## Invariants métier

- Un brouillon peut être enregistré sans offre ; un groupage ne peut être publié sans variante sélectionnée.
- La création et la publication utilisent toujours `GroupageStore.create`, `GroupageStore.add` et `GroupageStore.open` (pas de création de nouvelle API).
- Les boutons du résumé n'effectuent aucune mutation ; la campagne reste privée avant confirmation de la publication.
- Validation des informations et du transport avant passage à l'étape suivante ; seuls les étapes précédemment atteintes sont accessibles.
- Aucune nouvelle migration, modification des paiements, des règlements, des commandes existantes ou du backend.
- Les informations de sélection et d'erreur du picker sont effacées à chaque changement de boutique.

## Vérifications à faire localement (GitHub Actions temporairement bloqué par le quota)

Dans `Frontend` :

```powershell
npm ci
npm run lint
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

Puis vérifier manuellement : 320/360/390/768/1280/1440 px, clair/sombre, titre et prix dans le résumé, dates futures et invalides, recherche sur page, changement de boutique, variantes exclues, brouillon vide, publication avec produit, annulation et erreurs réseau.

Le code a été écrit directement sur la branche GitHub, mais **les tests locaux doivent encore être exécutés par le détenteur du dépôt** avant validation de la PR. La CI pourra être relancée lorsque le quota GitHub Actions sera réinitialisé.
