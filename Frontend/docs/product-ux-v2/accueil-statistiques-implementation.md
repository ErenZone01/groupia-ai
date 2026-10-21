# Brief d'implémentation — Accueil orienté actions / Statistiques détaillées

**Décision produit : VALIDÉE** (21 octobre 2026). Lire d'abord `groupia-experience-charter.md`. Cette instruction prime sur l'ancienne disposition de la PR #29, sans annuler les exigences de précision financière, d'isolation et d'accessibilité déjà implémentées.

**Objectif** : une gérante ouvre Groupia et voit dans cet ordre : ce qu'elle peut faire, où sont ses groupages, ce qui a changé ; elle peut analyser ses ventes sur une page spécialisée. Conserver le travail graphique de PR #29, mais ne pas le monopoliser sur l'accueil.

## Travail demandé sur la branche PR #29 existante

Branche `business-dashboard-ui-v2`, PR #29 déjà ouverte. **Ne pas merger**. Pas de seconde PR sauf contrainte technique impérative explicitement signalée. Travailler à partir du dernier head ; ne pas écraser la charte.

### Étape A — rendre les responsabilités explicites

1. Nouvelle route lazy `/app/statistiques`, titre `Statistiques · Groupia`, gardée par l'auth et boutique actuelle via `app`. Réutiliser `BusinessOverview`, `BusinessAnalyticsStore` et `SalesChart` déjà développés dans la PR #29.
2. Dans `navigation.ts`, ajouter `Statistiques` comme page secondaire sur mobile (dans `Plus`), visible en sidebar desktop dans un ordre cohérent. **Ne pas créer un cinquième onglet primaire** et ne pas casser focus / escape / dialog du menu Plus. Contrôler la recherche de page dans topbar.
3. Mettre à jour les titres et liens contextuels. Aucun nom de route ancienne ne doit casser. Les statistiques restent isolées par boutique et session, disposent de périodes 3/6/12 mois et d'une précision XOF contrôlée. Garder les tests de ce contrat.
4. Sur la page Statistiques : titre clair, définition métier lisible sans occuper la moitié de la page, graphiques N−1 et données accessibles ; pas de copie des listes de commandes ou des menus métiers.
5. Une section `Statistiques` autonome (pas appelée `Accueil` ou `Tableau de bord`) garantit que les comparaisons de périodes ne soient plus dispersées.

### Étape B — reconstruire l'Accueil comme poste de travail

Nouvelle page `/app/accueil` : ne pas mettre `<g-business-overview>` ni la grande courbe en haut.

**En-tête (premier écran)** : titre `Accueil`, boutique active, action `Créer un groupage` compacte. Éviter slogans génériques et double titre. Mobile : les tâches concrètes et 1er groupage doivent être visibles rapidement sans scroller à travers quatre KPI. Pas d'immense bannière.

**Priorités réelles** (`À suivre`) : seulement les actions effectivement justifiées par des états métier / permissions ; aucune alerte extrapolée.
- `DRAFT` : `Reprendre le brouillon` lien vers `/app/groupages/{id}`.
- `OPEN` et identifiant public présent : `Partager le groupage`, lien au détail où le bouton `Copier le lien` existe déjà.
- Statuts `ARRIVED`/`DISTRIBUTING` : lien `Suivre la distribution` vers le détail si l'action est effectivement disponible et autorisée.
- Éviter d'affirmer qu'une commande PENDING_PAYMENT demande une action immédiate de la gérante. Éviter d'afficher des erreurs financières comme résolubles quand les permissions ne sont pas vérifiées.
- Prioriser et limiter à **1 à 3 actions** maximum ; même groupage affiché une fois dans les priorités, pas une copie intégrale immédiatement dans `Groupages en cours` si aucune valeur supplémentaire.
- Si les API actuelles ne donnent pas assez d'informations pour une priorité, **ne pas inventer**. Masquer la zone ou présenter `Tout est à jour pour l'instant` uniquement si le périmètre réellement contrôlé justifie cette affirmation ; sinon afficher des liens d'orientation neutres.

**Mes groupages en cours** : aperçu de 1 à 3 items réellement retournés par l'API des campagnes, statut, échéance si fournie, un accès au détail. Éviter de charger la totalité des campagnes. Attention : `GroupiaApi.campaigns(org,page)` lit 20 éléments/page ; `CampaignSummary` possède `id,status,title,closesAt,publicIdentifier`. Vérifier le tri serveur avant de promettre `récents` ou `urgents`; ne pas prétendre avoir parcouru tous les groupages. Garder listes/filtrage/complet dans `/app/groupages`. Le cycle fournisseur, transport, distribution et clôture reste dans le détail du groupage.

**Mon activité** : une synthèse **très compacte** (max 1–2 chiffres) avec lien fort `Voir les statistiques`. Pour des valeurs réelles, on peut réutiliser l'API `months=3`, mais afficher explicitement `3 mois calendaires dont mois courant` ; ne pas dire `ce mois-ci` si l'API ne fournit pas précisément ce périmètre. **Ne pas** importer le composant complet de courbe, ni faire plus d'une agrégation. Si surcharge de requêtes pour la page, préférer un lien simple plutôt qu'une fausse synthèse ou une réponse lente.

**Dernières commandes** : 2 à 3 lignes maximum, uniquement si utiles à l'accueil et faciles à ouvrir. `OrdersApi.list(org,{size:3})` existe ; éviter les quatre requêtes du dashboard ancien (3 comptages par statut + récentes) si elles n'ont plus d'utilité. La liste exhaustive/filtrage reste sur `/app/orders`. Distinguer montants `initialAmountDue` d'une vente `goodsSales` : ils ne mesurent pas la même chose.

**Onboarding** : pour une boutique sans groupage et/ou produit, guider de manière progressive : ajouter un produit, créer un groupage, publier et partager. Tester la présence de produit seulement via une requête paginée **bornée** si nécessaire (ex. `GroupiaApi.products(org,0,1)`), pas via `allProducts()` qui recharge les détails de tout le catalogue. Si des groupages sont déjà publiés, ne pas montrer un onboarding de novice. Ne pas dupliquer toutes les étapes du wizard.

**États** : chargement, vide, erreurs indépendantes pour groupages, activité et commandes ; aucune erreur d'API ne doit être interprétée comme 0 vente ou 0 groupage.

### Étape C — personnalité, densité, performance

- Conserver le logo, les couleurs identitaires vert profond / turquoise / accent orange. Orange réservé à l'action primaire de la page ; pas d'orange pour tous les éléments.
- Réduire l'effet « gros titres / gros chiffres / blocs énormes » : une échelle cohérente (H1 ~22–24 mobile, 24–28 desktop, H2 18–20, chiffres importants 24–30 max en contexte, corps 15–16). Tester rendu Poppins et poids typographiques ; **ne pas réintroduire 13px comme texte global**.
- Palette neutre majoritaire, surfaces discrètes, peu d'ombres, carte seulement pour un groupe d'informations réel, pas une carte pour chaque petit lien.
- Priorité à une lecture à balayage rapide, espaces cohérents, retour à la ligne mobile sans chevauchement avec bottom nav.
- Animation fonctionnelle courte 120–200ms, `prefers-reduced-motion`, pas de motion décorative permanente ou de dépendance lourde.
- Ne pas ralentir le premier écran pour des données secondaires : requêtes bornées, cache seulement si sûre par boutique/session, annulation et masquage des résultats obsolètes. Ne pas entraîner un double fetch systématique à la navigation accueil/statistiques.

### Étape D — qualité et validation

- Vérifier sécurité du contexte boutique, de la session et de l'organisation sur chaque flux, expiration et changements rapides, sans laisser apparaître une donnée d'un autre tenant.
- Conserver API finance : les ventes d'articles confirmées sont brutes avant remboursements, hors frais, **pas** bénéfice / fonds en caisse / payout.
- Réviser les tests du dashboard `ux-foundations.spec.ts`, `business-dashboard.spec.ts`, `business.spec.ts`, `foundation.spec.ts` selon la séparation des écrans. **Ne jamais supprimer un test de sécurité pour faire passer la CI ; déplacer les assertions qui portent maintenant sur Statistiques**.
- Test E2E des liens `Accueil → Groupage` et `Accueil → Statistiques`, mobile Plus → Statistiques, focus/escape, changement de boutique, déconnexion, bornage des requêtes, pas d'overflow.
- Captures Playwright **avant/après** à 390 et 1280, clair/sombre, avec boutique vide et active, chargement/erreur. Captures synthétiques clairement labellisées, pas de valeurs fictives en production.
- Tests responsive 360/390/430/768/1024/1280/1440, zoom texte 200%, clavier/tactile, contrastes WCAG AA.
- `npm ci`, `npm audit --audit-level=high`, `npm run lint`, `npm test`, `npm run build`, `npm run test:e2e`; GitHub Actions Security/Backend/Frontend verts. Mesurer changement bundle gzip et documenter s'il augmente.
- Mettre à jour la matrice des pages de `groupia-experience-charter.md` avec les arbitrages réellement implémentés ; décrire les fonctions restant dans chaque écran.
- Ne pas toucher migrations, backend, règles métier, paiements, reversements, R2, etc. Tout blocage de données/API non existante doit être signalé, non simulé.

### Livraison et acceptation

- Travail poussé sur la **même branche PR #29**, toujours sans merge.
- Refaire la galerie visuelle avec les nouvelles routes : **Accueil** ne doit plus être dominé par la courbe et **Statistiques** garde la puissance de la PR #29.
- Documenter les nouvelles routes, le placement et le sens de chaque information, tests et coûts de chargement. Donner commits, captures et lien de PR.
- Une CI verte ne suffit pas pour accepter le design ; montrer et faire valider les captures de la nouvelle version avant fusion.
