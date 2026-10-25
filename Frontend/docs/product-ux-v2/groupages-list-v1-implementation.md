# Groupia — Liste des Groupages V1

**Statut : implémentation candidate à revoir visuellement**. Suite des travaux : navigation V6 (#35), Accueil V3/V3.4 (#36). Cette PR reste limitée à la **liste** `/app/groupages`, sans modifier la création, le détail, ni le paiement.

## Intentions UX

- En-tête lié à l'activité Groupages et un seul CTA **Créer un groupage**.
- Cartes adaptatives avec titre, statut issu du backend, indication contextuelle *non prescriptive* et échéance si disponible.
- Un bouton visible **Voir le groupage** ouvre le détail ; actions complexes (publication, partage, livraison, commandes, reversement) restent dans la page propriétaire.
- État vide réellement réservé à une boutique sans groupage (retour `totalElements === 0`). Une page vide avec un total non nul **n'est pas un onboarding**.
- Erreur réseau distincte d'une absence de groupages, bouton Réessayer sans quitter la page.
- Pagination **serveur 20 par page**. Pas de filtres « Tous / Ouverts / À faire » calculés sur une seule page et présentés comme globaux.
- Aucun nouveau compteur, aucune date ou permission inventée ; aucun faux lien direct vers une boutique ou vente directe.

## Isolation des boutiques

Le composant invalide les demandes obsolètes via numéro séquentiel **et** identité `selectedId` ; toutes les données sont masquées au changement ou à la disparition de la boutique. Les réponses arrivant en retard d'une ancienne boutique ne peuvent pas la réafficher. Un nouveau tenant recharge page zéro.

## Leçons des PR précédentes — garde-fous

1. **CSS Angular :** garder les styles de page limités (budget composant warning 4 kB / erreur 8 kB). Pas d'augmentation du budget projet.
2. **Tests de captures :** vérifier explicitement les états `populated`, `empty`, `error`, `loading` et faire évoluer les assertions quand un état UX change. Ne pas conserver des tests de l'ancien onboarding comme s'ils validaient le nouveau.
3. **Playwright :** sélecteurs par rôle/nom exact et portée du composant ; éviter les textes ambiguës. Les scénarios ajoutés isolent cartes, CTA, erreur, pagination et affichage responsive.
4. **Navigation V6 :** le shell, le tiroir et sa fermeture par backdrop ne sont pas modifiés dans cette PR.
5. **Règles métier :** pas de modification de statut, endpoints, contrats générés, paiements, backend ou migrations.
6. **Validation avant merge :** `npm ci`, audit, lint, tests unitaires, build, E2E, Security/Backend/Frontend PASS, puis **inspection humaine des captures** 360/390/768/1280/1440, clair et sombre, zoom texte à 200 % et focus clavier.

## Limites conscientes

- Pas de recherche ni de filtres multi-pages : cela demanderait un contrat backend de recherche/filtrage, à réaliser dans un sprint dédié.
- Pas de KPI agrégés sur cette page : le nombre total retourné par l'API suffit ; synthèse décisionnelle dans Accueil et Statistiques.
- Pas de vente directe : non disponible actuellement.

