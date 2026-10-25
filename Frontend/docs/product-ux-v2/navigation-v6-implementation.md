# Groupia — Navigation V6, implémentation du premier shell métier

**Statut : conception V6 approuvée comme base V1 le 25 octobre 2026 ; implémentation en PR #35 (à valider sur captures et tests).**

Cette note complète la charte Groupia et l'issue [#34](https://github.com/ErenZone01/groupia-ai/issues/34). Elle ne valide pas la vente directe ni des permissions/API qui n'existent pas.

## Répartition des rubriques

| Domaine | Page de référence | Navigation mobile V1 | Navigation desktop |
| --- | --- | --- | --- |
| Situation, tâches | Accueil | Onglet visible | Espace de travail |
| Groupages & cycle | Groupages | Onglet visible | Espace de travail |
| Toutes les commandes | Commandes | Onglet visible | Espace de travail |
| Catalogue, variantes et médias | Produits | Onglet visible | Espace de travail |
| Historique pay-in/anomalies | Paiements | Menu droit · Finances et analyse | Finances et analyse |
| Reversements marchands | Mes versements | Menu droit · Finances et analyse | Finances et analyse |
| Analyse commerciale | Statistiques | Menu droit · Finances et analyse | Finances et analyse |
| Identité de la boutique | Ma boutique | Menu droit · Boutique | Boutique |
| Plan et abonnement | Abonnement | Menu droit · Boutique | Boutique |

**Ne pas ajouter Activités tant que la vente directe n'est pas réellement disponible.** Lorsque les groupages et ventes directes seront opérationnels, réévaluer l'entrée unique Activités pour les deux modes, sans casser les URL existantes.

## En-tête

- **Logo Groupia :** accueil, en haut à gauche sur mobile et en haut de la sidebar desktop.
- **Bouton + :** actions **existantes** « Créer un groupage » et « Ajouter un produit dans le catalogue » (ce second lien mène à la page Produits, qui possède le formulaire existant). Aucun faux formulaire accessible depuis le shell.
- **Menu :** en haut à droite sur mobile, libellé explicite, panneau modal depuis le côté droit ; sur ordinateur, la sidebar permanente rend ce bouton inutile.
- **Thème et déconnexion :** dans le tiroir sur mobile ; conservés dans la barre supérieure sur ordinateur.
- **Compte/boutique :** endroit stable, jamais confondu avec les offres ou la facturation.

## Intention des écrans

Une page propriétaire garde la liste et les opérations complètes. L'Accueil n'affiche que des actions et extraits ciblés. Les statistiques financières n'entrent pas dans la liste des tâches ; les paiements et versements ne sont pas la même chose que des ventes d'articles.

Le menu doit rester navigable au clavier : dialogue modal natif, Échap et bouton de fermeture, clic sur backdrop, retour du focus au bouton Menu, scroll restauré et fermeture lors de navigation/changement de breakpoint. Glissement vers la droite facultatif pour fermer. **Ne pas exiger un balayage depuis le bord droit pour ouvrir**, ce geste entre en conflit avec la navigation système Android.

## Mise en œuvre et QA

- `navigation.ts` est le **registre unique** des routes, ordres et groupes pour sidebar/menu mobile et recherche dans la topbar.
- Les quatre destinations mobiles n'incluent pas une cinquième entrée « Plus ». L'entrée `Menu` est accessible en haut.
- Les sous-pages des groupages, commandes et paiements conservent leur destination parent active.
- Pas de route fictive « Paramètres » ni « Ventes directes » ; les futures pages devront avoir leurs vrais contrats API et permissions avant exposition.
- Avant merge : couverture 360/390/430/768/1024/1280/1440 en clair/sombre, navigation clavier/souris/tactile, 200 % zoom texte, changement boutique, session expirée, CI et captures comparées.
- Prochaine itération distincte : déclinaison visuelle de l'Accueil approuvé V3/V3.4, puis UX des autres pages. L'implémentation V6 n'autorise pas une refonte simultanée des flux financiers.

