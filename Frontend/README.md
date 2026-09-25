# Groupia — fondation frontend

Application Angular **22.2.0**, standalone, Router, HttpClient et Signals. Cette tranche prépare l’architecture et l’interface ; elle ne met en œuvre aucun workflow métier. Le backend et les migrations restent indépendants.

## Lancer et vérifier

Pré-requis : Node ^22.22.3, ^24.15.0 ou >=26, et npm. Validation effectuée avec Node 24.16.0 et npm 11.10.0.

```powershell
cd Frontend
npm ci
npm start
# http://localhost:4200/accueil
npm test
npm run build -- --configuration production
npx playwright install chromium
npm run test:e2e
```

Les tests navigateur démarrent et arrêtent leurs propres serveurs sur 4200 et 4300. Libérer ces ports avant de les lancer. Le build de production doit exister avant `test:e2e`. `npm run format` applique Prettier.

## Architecture créée

```text
Frontend/
├── maquettes/                    références visuelles fournies, conservées
├── public/                       favicon et initialisation du thème avant Angular
├── src/
│   ├── app/
│   │   ├── core/
│   │   │   ├── auth/             session JWT
│   │   │   ├── http/             interceptor Bearer
│   │   │   ├── guards/           protection de l’espace gérante
│   │   │   ├── theme/            clair, sombre, système
│   │   │   ├── layout/           Sidebar, Topbar, MobileBottomNav, ManagerLayout
│   │   │   └── config/           environnements, profil et navigation
│   │   ├── shared/
│   │   │   ├── ui/               Button, IconButton, Card, Badge, Avatar, StatCard, Tabs
│   │   │   ├── forms/            Input, Select, Toggle, SearchInput
│   │   │   ├── feedback/         Skeleton, EmptyState, écran d’attente de feature
│   │   │   └── utils/            initiales
│   │   ├── features/
│   │   │   ├── auth/
│   │   │   ├── dashboard/
│   │   │   ├── boutique/
│   │   │   ├── catalog/
│   │   │   ├── groupages/
│   │   │   ├── orders/
│   │   │   ├── payments/
│   │   │   ├── public-groupage/
│   │   │   ├── checkout/
│   │   │   └── tracking/
│   │   └── api/generated/        emplacement documenté du futur client OpenAPI
│   └── styles/_tokens.scss       palettes et tokens sémantiques
├── e2e/                          vérifications Chromium
├── tools/serve-production.cjs    serveur local réservé aux tests
└── proxy.conf.json               proxy local vers Spring Boot :8080
```

Les dix features sont chargées avec `loadComponent`. L’espace gérante utilise un layout commun et un guard sur la route parente et ses enfants. Les pages publiques `/g/:publicId`, `/g/:publicId/commander` et `/suivi` sont accessibles sans session.

Les composants purement UI n’importent aucun service métier. Input, Select et Button sont des directives sur des éléments HTML natifs, pour conserver labels, formulaires et comportements clavier. Les onglets prennent en charge les flèches, Home et End. Les contrôles ont un focus visible, des états désactivés et, pour les boutons, un état de chargement.

## Design et aperçu

Les palettes demandées sont centralisées dans les CSS custom properties. Les composants utilisent ces variables ; Poppins est livrée localement avec l’application, sans appel à Google Fonts. La sidebar apparaît à partir de 1024 px ; en dessous, la navigation inférieure contient Accueil, Groupages, Commandes et Boutique.

Le script `public/theme-init.js` applique le thème avant le démarrage d’Angular. ThemeService persiste le choix dans localStorage, suit le système lorsque ce mode est choisi et synchronise les onglets. Un stockage indisponible ne bloque pas l’application.

`npm start` active un aperçu visuel avec des données locales, signalé dans l’interface. Les fichiers `*.development.ts` sont sélectionnés uniquement par les remplacements de la configuration development. Le dashboard de production ne contient aucune fixture métier : il affiche des emplacements vides. Aucun appel métier HTTP n’est effectué dans cette tranche.

`/atelier` permet de manipuler les composants en développement. Les maquettes ont servi de référence à la composition, aux cartes, à la navigation et aux thèmes ; les placeholders des autres features ne simulent pas de fonctionnalités terminées.

## Authentification préparée

AuthService conserve le jeton en mémoire et dans **sessionStorage**, pour la durée de l’onglet. Aucun jeton n’est écrit dans localStorage ou dans les logs. L’expiration est vérifiée à la restauration et à chaque utilisation. Si le stockage est indisponible, la session reste en mémoire. Le contrat `acceptSession(accessToken, expiresIn)` devra être appelé par le futur adaptateur de connexion ; ce n’est pas un DTO backend.

L’interceptor joint Bearer seulement à l’origine et au chemin de l’API configurée, hors routes anonymes auth/public. Un 401 invalide la session concernée, sans effacer une session plus récente. Le guard reste actif en production ; seul l’aperçu de développement permet l’accès sans connexion. L’autorisation réelle restera toujours à la charge du backend.

La page connexion est un écran préparatoire : le bouton est désactivé jusqu’au branchement réel. Aucun endpoint d’authentification, rafraîchissement de jeton ou DTO métier n’a été inventé.

## Dépendances

- Angular core/common/compiler/forms/platform-browser/router **22.2.0**.
- RxJS **7.8.2**, tslib **2.8.1**, Poppins **5.3.0**.
- Outils : Angular CLI/build/compiler-cli **22.2.0**, TypeScript **6.0.3**.
- Tests : Vitest **4.1.11**, jsdom **28.1.0**, Playwright **1.63.0**.
- Formatage : Prettier **3.9.9**.

Les versions résolues sont verrouillées dans package-lock.json. Aucune bibliothèque UI lourde. Les quelques icônes sont des SVG locaux encapsulés dans Icon ; Lucide Angular n’est pas nécessaire à cette tranche.

## Connexions à réaliser dans les prochaines tranches

1. Verrouiller un générateur compatible Angular 22 et générer le client depuis `/v3/api-docs` dans `api/generated`.
2. Brancher la connexion réelle, le profil, l’expiration de session dans les parcours et la déconnexion.
3. Relier le dashboard et les features aux lectures API, puis ajouter leurs états chargement/erreur/vide.
4. Implémenter séparément les parcours boutique, catalogue, groupages, commandes, paiements, commande publique et suivi.
5. Définir la configuration de déploiement de l’API et le fallback SPA côté hébergement.

Aucune modification Spring Boot, SQL, Redis, Kafka ou RabbitMQ dans cette tranche.

## Vérification de cette tranche — 25 septembre 2026

- `npm test` : **50 tests réussis**, 7 fichiers, aucun échec.
- `npm run build -- --configuration production` : **réussi**, bundle initial 269,77 kB (transfert estimé 73,08 kB).
- `npm run test:e2e` : **12 tests Chromium réussis**, aucun échec.
- Tailles vérifiées : 360, 768, 1024 et 1440 px, en clair et sombre ; aucun débordement horizontal sur le dashboard.
- Navigation mobile, focus clavier, onglets, toggle, persistance du thème, changement du thème système, guard de production et route publique vérifiés.
- Aucune requête métier `/api/` sur le dashboard d’aperçu. Contrôle des bundles de production : aucun des noms ou montants des fixtures recherchées.
- Captures produites par les tests : `artifacts/dashboard-{360|1440}-{light|dark}.png` ; dossier local ignoré par Git. Rendus mobile clair et desktop clair/sombre inspectés visuellement.

Ces contrôles couvrent la fondation frontend, pas les parcours métier ni une certification WCAG complète.
