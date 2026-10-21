# Groupia — fondations UX/UI V2

Branche : `ux-ui-foundations-v2`, issue de `main` au commit `9fcf561b582ed20c3afe32b679def5ef20695c2a`.

## Diagnostic et choix

Le corps de texte utilisait 13 px, les contrôles et les libellés étaient peu différenciés, et le tableau de bord présentait plusieurs blocs de même importance sans action principale de création. La navigation était fonctionnelle : son comportement n'avait pas besoin d'être réécrit.

| Avant | Après |
| --- | --- |
| Corps de texte 13 px, texte secondaire 13 px | Corps 16 px, texte secondaire 14 px, interligne 1,65 ; Poppins conservée |
| Survol par filtre et déplacement du bouton | Couleurs sémantiques de survol et état pressé sans déplacement |
| Bordures de champs peu visibles | Token distinct pour les contrôles, focus de 3 px, champs et boutons de 48 px ; champs fichier dimensionnés selon leur conteneur |
| Interrupteur de 26 px de haut | Cible tactile de 44 px, piste visuelle compacte conservée |
| Blocs d'accueil de même poids visuel | Accueil → création de groupage → activité → commandes récentes et raccourcis |
| Pas de CTA principal de création | « Créer un groupage » mène au wizard existant `/app/groupages/nouveau` |
| Commandes récentes dans le composant de liste général | Liste de présentation dédiée : référence, date, montant, statut et lien vers le détail |
| Navigation dense et état actif discret | Sidebar vert profond, destination active claire ; barre mobile et panneau Plus harmonisés |

La palette turquoise/orange, les thèmes clair/sombre, la marque et les routes sont conservés. Les surfaces neutres sont légèrement réchauffées ; bordures, ombres et rayons utilisent les tokens communs. Aucune dépendance n'est ajoutée.

## Périmètre des fichiers

- `src/styles/_tokens.scss` et `src/styles.scss` : fondations, boutons, champs et états partagés.
- `src/app/shared/ui/{badge,card,icon-button,stat-card,tabs}.ts` : composants de présentation.
- `src/app/shared/forms/{search-input,toggle}.ts` et `src/app/shared/feedback/{empty-state,skeleton}.ts` : contrôles et retours visuels.
- `src/app/core/layout/manager-layout.ts`, `sidebar.scss`, `topbar.scss`, `mobile-bottom-nav.scss` : shell gérante. La logique du menu Plus est inchangée.
- `src/app/features/dashboard/dashboard.page.{ts,html,scss}` et `recent-orders.{ts,scss,spec.ts}` : composition de l'accueil et présentation des commandes récentes.
- `e2e/ux-foundations.spec.ts` : 17 scénarios supplémentaires, sans modification des tests existants.
- Ce dossier : captures et compte rendu.

Le store du tableau de bord reste inchangé : quatre lectures API, mêmes totaux et mêmes statuts. « Payées » désigne toujours les commandes confirmées ; une note le précise. Une erreur affiche des valeurs indisponibles, jamais des zéros inventés. Les montants et dates reprennent les formateurs existants. Les exemples chiffrés des captures sont exclusivement des réponses API simulées dans Playwright.

## Vérifications

Exécution locale du 20 octobre 2026 :

| Vérification | Résultat |
| --- | --- |
| `npm run lint` | PASS |
| `npm test` | PASS — 393 tests dans 37 fichiers, dont 2 nouveaux tests de commandes récentes |
| `npm run build` | PASS — production ; avertissements NG8107 existants ci-dessous |
| `npx playwright test e2e/product-media.spec.ts e2e/ux-foundations.spec.ts` après correction | PASS — 23 tests |
| `npm run test:e2e` final | PASS — 214 tests, aucun échec ni test ignoré (6,7 min) |
| `git diff --check` | PASS |
| Inspection des captures | Desktop/mobile, clair/sombre, accueil vide, commandes récentes et menu Plus |

Les fixtures restent dans les tests ; aucune donnée métier fictive n'est ajoutée à l'application.

Les nouveaux tests couvrent les largeurs 360, 390, 430, 768, 1024 et 1280 px dans les deux thèmes, l'absence de débordement horizontal, les données reçues, le lien principal activé au clavier, le focus du menu Plus à l'ouverture et après Échap, les états vide/erreur, le reflow à 200 % du texte et `prefers-reduced-motion`. Ils vérifient aussi 16 paires de couleurs par thème : 4,5:1 pour le texte et 3:1 pour les contrôles/focus sélectionnés. Un test complémentaire couvre la largeur du champ fichier natif à 360 px.

La première exécution complète a révélé un débordement dans `galerie produit accessible 360px` (212 réussites, 1 échec). Le champ fichier natif mesurait 352 px après agrandissement de la police. La correction est exclusivement dans le style global des champs fichier (`width: 100%`, `min-width: 0`, `max-width: 100%`) ; le scénario de galerie existant est conservé intégralement.

## Captures et limites

Les captures `before/` proviennent de la version initiale. Les captures de l'accueil vide utilisent le même scénario avant/après. Les captures avec commandes montrent en complément la nouvelle hiérarchie avec les fixtures de test.

| Écran | Avant | Après |
| --- | --- | --- |
| Mobile 360 px, clair | [Capture](before/dashboard-360-light.png) | [Capture](after/dashboard-360-light.png) |
| Mobile 360 px, sombre | [Capture](before/dashboard-360-dark.png) | [Capture](after/dashboard-360-dark.png) |
| Desktop 1440 px, clair | [Capture](before/dashboard-1440-light.png) | [Capture](after/dashboard-1440-light.png) |
| Desktop 1440 px, sombre | [Capture](before/dashboard-1440-dark.png) | [Capture](after/dashboard-1440-dark.png) |

Vues complémentaires : [commandes récentes à 1280 px](after/populated-dashboard-1280-light.png), [mobile sombre à 360 px](after/populated-dashboard-360-dark.png), [menu Plus au clavier](after/populated-plus-360-dark.png).

Les captures pleine page positionnent les éléments fixes à la position de défilement initiale : la barre mobile apparaît donc au milieu de l'image longue. Les tests contrôlent aussi le focus et la navigation dans le viewport réel. Les 12 captures de la matrice principale sont régénérées dans `test-results/` par Playwright ; les vues représentatives sont conservées ici.

- Validation automatisée dans Chromium et inspection visuelle des captures ; aucun essai sur appareil physique ou avec lecteur d'écran.
- Les contrôles de contraste ciblés et les tests clavier ne constituent pas une certification WCAG exhaustive.
- Les styles partagés s'appliquent également aux écrans existants : c'est le principal risque transversal, couvert par la suite E2E complète. Les templates du parcours public et du wizard ne sont pas refondus.
- Les avertissements Angular `NG8107` du composant existant `ShippingOrderEditor` sont hors périmètre et ne bloquent pas le build.
- Aucun fichier backend, migration, contrat API, authentification, règle financière ou permission n'est modifié.
