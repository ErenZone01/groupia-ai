# Groupia — parcours gérante et cliente

La tranche Commandes côté gérante est décrite dans [son bilan et ses limites](docs/manager-orders.md).

Le parcours public panier → coordonnées → paiement → suivi est documenté dans [le bilan et la recette manuelle](docs/public-checkout.md).

Angular **22.2.0**, composants standalone, Signals, Router, HttpClient, SCSS. La fondation et le design system sont conservés. Le backend Spring Boot et les migrations SQL ne sont pas modifiés.

## Démarrer

Pré-requis : Node ^22.22.3, ^24.15.0 ou >=26. Versions de validation : Node 24.16.0, npm 11.10.0.

```powershell
cd Frontend
npm ci
npm start
```

Ouvrir http://localhost:4200/connexion. Le backend doit être lancé séparément sur http://localhost:8080. Le proxy de développement transfère `/api/...` et `/v3/api-docs` à Spring Boot. En production, configurer le reverse proxy sur la même origine et le fallback des routes vers index.html.

Il n’y a plus de connexion simulée ni de fixtures métier dans l’application. Les données de test sont exclusivement dans les tests unitaires et `e2e/api-mock.ts`. Le tableau de bord affiche les nombres de commandes fournis par le backend et les cinq commandes les plus récentes, sans métriques financières inventées.

## Parcours livré

1. **Connexion et inscription** : formulaires réels, déconnexion et expiration automatique. L’inscription renvoie un compte, puis redirige vers la connexion ; elle ne fabrique pas de session.
2. **Boutique** : chargement des boutiques autorisées ; onboarding si aucune ; sélection automatique de l’unique boutique, ou sélecteur dans Ma boutique. Seul l’identifiant choisi est mémorisé et il est revalidé sur chaque nouvelle session.
3. **Catalogue** : liste paginée, description, statut traduit, variantes réelles, création simple ou avec options. Une variante sans attributs s’affiche « Standard ». SKU facultatif ; les clés canoniques ne sont jamais affichées.
4. **Groupages** : liste paginée et wizard Informations → Livraison → Produits → Vérification. Enregistrement en brouillon ou publication.
5. **Détail** : aperçu, produits, paramètres et commandes filtrées par groupage. Ajout de produits, modification du prix, publication, fermeture avec confirmation et partage du lien public.
6. **Page cliente** : vitrine, panier persistant dans la session, coordonnées sans compte, checkout idempotent, préparation Bictorys et suivi serveur. Voir docs/public-checkout.md.

Routes principales :

| Route | Accès |
| --- | --- |
| /connexion, /inscription | Public |
| /app/accueil | Authentifié |
| /app/boutique | Authentifié |
| /app/products | Authentifié |
| /app/orders, /app/orders/:orderId | Authentifié |
| /app/groupages | Authentifié |
| /app/groupages/nouveau | Authentifié |
| /app/groupages/:id | Authentifié |
| /public/groupages/:publicIdentifier | Public, sans JWT |
| /public/groupages/:publicIdentifier/cart, /checkout | Public, sans JWT |
| /public/order | Public, token de suivi en session |

Les anciens liens /accueil, /catalogue, /groupages, /boutique, /commandes et /paiements redirigent vers le nouvel espace /app. /g/:publicId reste un alias de la vitrine. Toutes les pages métier sont lazy-loaded. /app/atelier conserve la démonstration des composants, protégée par la connexion.

## Génération OpenAPI

Générateur : [ng-openapi-gen 1.1.0](https://github.com/cyclosproject/ng-openapi-gen), verrouillé dans package-lock.json. Il génère des services HttpClient et des modèles compatibles Angular standalone.

```powershell
# Récupère le contrat réel sur http://localhost:8080/v3/api-docs,
# enregistre openapi.json puis régénère les modèles et services.
npm run api:generate

# Régénération hors ligne depuis le snapshot versionné, sans appel réseau.
npm run api:generate:snapshot

# Facultatif : autre URL du contrat
$env:GROUPIA_OPENAPI_URL = 'http://localhost:8080/v3/api-docs'
npm run api:generate
```

- `openapi.json` est le contrat backend, pas une copie de DTO écrite à la main.
- `ng-openapi-gen.json` et `tools/generate-api.mjs` pilotent la génération.
- `src/app/api/generated` contient **53 modèles et 7 services générés**. Ne pas modifier ni reformater ces fichiers à la main.
- `GroupiaApi` fournit des noms stables à l’application ; les suffixes d’opérations générés par Spring restent confinés dans cet adaptateur.
- Spring déclare plusieurs réponses structurées en `*/*`. Le script utilise l’option officielle `customizedResponseType` pour les décoder en JSON. Le contrat source reste intact.
- `provideApiConfiguration('')` utilise les URLs relatives. L’interceptor s’applique au client généré.
- Les contrats générés alimentent les parcours public et Commandes côté gérante ; le calcul fournisseur reste bloqué par les données absentes de la projection de liste.

**Provenance du snapshot initial** : export réel `Backend/target/groupia-openapi.json`, produit le 24 septembre 2026 par `OpenApiIntegrationTests` via GET /v3/api-docs. Le serveur local refusait les connexions sur 8080 lors de cette tranche. Les endpoints et invariants utilisés ont aussi été relus dans les controllers/domaines actuels. Rafraîchir avec `npm run api:generate` au prochain démarrage du backend.

## Architecture et fichiers principaux

```text
src/app/
├── api/
│   ├── generated/                     sorties du générateur
│   └── groupia-api.ts                  façade des services générés
├── core/
│   ├── auth/auth.service.ts           login/register/session/logout/expiration
│   ├── boutique/boutique-context.service.ts
│   ├── http/
│   │   ├── auth.interceptor.ts        Bearer limité à l’API privée
│   │   └── api-error.ts               messages français sûrs
│   ├── guards/auth.guard.ts
│   ├── layout/                        layout existant + gate Boutique
│   ├── config/                        navigation et environnements
│   └── theme/                         fondation conservée
├── features/
│   ├── auth/                          connexion et inscription
│   ├── boutique/                      onboarding, sélection, déconnexion
│   ├── catalog/
│   │   ├── catalog.page.ts
│   │   ├── catalog.store.ts
│   │   ├── product-form.ts
│   │   └── variant-editor.ts
│   ├── groupages/
│   │   ├── groupages.page.ts
│   │   ├── groupage-wizard.page.*
│   │   ├── groupage-detail.page.*
│   │   ├── groupage.store.ts
│   │   ├── product-picker.ts
│   │   ├── shipping-fields.ts
│   │   ├── groupage-settings.ts
│   │   └── offer-price-editor.ts
│   └── public-groupage/
│       ├── public-groupage.page.*
│       └── public-selection.service.ts
├── shared/                            composants existants + toasts + formatage
└── testing/                           configuration HTTP des tests
```

Aucun service UI générique ne porte de règle métier. Les stores des features possèdent leur état ; le contexte Boutique est partagé par Signals. Les requêtes catalogue/détail périmées ne remplacent pas des réponses plus récentes. Le contexte ignore les réponses d’une session déconnectée.

## Endpoints réellement appelés

| Méthode | Chemin |
| --- | --- |
| POST | /api/auth/login |
| POST | /api/auth/register |
| GET, POST | /api/organizations |
| GET, POST | /api/organizations/{organizationId}/products |
| GET | /api/organizations/{organizationId}/products/{productId} |
| GET, POST | /api/organizations/{organizationId}/grouping-campaigns |
| GET, PUT | /api/organizations/{organizationId}/grouping-campaigns/{campaignId} |
| POST | /api/organizations/{organizationId}/grouping-campaigns/{campaignId}/offers |
| PUT | /api/organizations/{organizationId}/grouping-campaigns/{campaignId}/offers/{offerId} |
| POST | /api/organizations/{organizationId}/grouping-campaigns/{campaignId}/open |
| POST | /api/organizations/{organizationId}/grouping-campaigns/{campaignId}/close |
| GET | /api/public/groupings/{publicIdentifier} |

Les identifiants de boutique viennent du contexte autorisé, jamais d’une saisie manuelle.

## Décisions et limites explicites

- Authentification : mémoire + sessionStorage ; pas de refresh-token, pas de token affiché ou journalisé. Un 401 privé invalide uniquement la session qui a émis la requête. Les requêtes publiques ne reçoivent pas le JWT. Le backend reste l’autorité pour les droits.
- Aucun prénom n’est inventé : les APIs de ce sprint ne fournissent pas de profil/prénom. Le layout affiche le nom réel de la boutique.
- Livraison : AIR / SEA / ROAD / OTHER, PER_KG / PER_CBM / PER_ITEM / FIXED, ESTIMATED_AT_CHECKOUT / **AFTER_FINAL_ASSESSMENT**, XOF et HALF_UP, conformément au backend. Le tarif est requis ; zéro n’est permis que pour le forfait.
- Le backend exige des estimations de poids/volume pour l’encaissement estimé au kg/m³. Le formulaire des variantes propose ces champs ; l’erreur métier explique leur absence.
- Le catalogue ne porte pas de prix. « Prix dans ce groupage » appartient à chaque variante sélectionnée.
- Les montants entiers sont limités à la précision sûre de JavaScript ; aucune incrémentation de version côté client, aucun succès métier anticipé.
- Après chaque mutation, CampaignView remplace l’état précédent. Les appels create → add → open sont séquentiels. Après conflit de version/prix, le groupage est relu sans rejouer automatiquement la mutation. Si cette relecture échoue, les mutations sont bloquées jusqu’à actualisation.
- La création multi-requêtes n’est pas une transaction frontend : en cas d’échec après création, le brouillon et les produits déjà ajoutés sont conservés, avec un lien pour reprendre. Pas de retry automatique des POST sans clé d’idempotence exposée.
- Les résumés catalogue ne contiennent ni description ni variantes. La page lit donc les détails par lots de 6 ; le détail d’un groupage parcourt le catalogue pour retrouver les noms des variantes. Cela évite d’inventer un endpoint. Un endpoint d’enrichissement ou un cache pourra réduire ces lectures plus tard.
- `orderable=false` désactive la sélection. Les variantes inactives ou inutilisables ne peuvent pas être ajoutées. Les snapshots du panier sont conservés. Une offre indisponible bloque le checkout ; un nouveau prix nécessite un accord explicite. Le panier reste dans la session et n’est vidé qu’après confirmation du suivi.
- Les dates utilisent le fuseau de l’appareil et sont envoyées en ISO UTC.
- Les maquettes et palettes restent conservées. Aucun Material, NgRx ou bibliothèque UI lourde.

## Vérification

```powershell
npm test
npm run build -- --configuration production
npx playwright install chromium
npm run test:e2e
```

Les tests navigateur démarrent un serveur development sur **4201** et un serveur local du build production sur **4300**, puis les arrêtent. Le serveur utilisateur sur 4200 peut rester ouvert.

Les E2E interceptent toutes les APIs avec des données réservées aux tests. Ils ne créent aucune donnée dans PostgreSQL, n’appellent pas Bictorys et vérifient les corps des mutations, les versions serveur et l’absence de JWT public. Ces tests ne constituent pas une recette live du backend.

Captures locales dans `artifacts/`, rapport Playwright dans `playwright-report/` (ignorés par Git). npm run format exclut les sorties générées via .prettierignore.

## Suite prévue

Agrégation fournisseur côté backend, enrichissement de la liste des commandes (cliente/quantités), récupération sécurisée du suivi et évolutions catalogue. Le checkout et le suivi public sont désormais implémentés ; leur recette sandbox manuelle est décrite dans docs/public-checkout.md.

## Résultats de validation — 25 septembre 2026

- `npm test` : **85 tests réussis**, 14 fichiers.
- `npm run test:e2e` : **31 tests navigateur réussis**, Chromium ; parcours mobile/desktop, 360 px, tablette, light/dark, clavier et build production.
- `npm run build -- --configuration production` : **réussi**, budgets respectés ; bundle initial 297,06 kB (transfert estimé 82,96 kB).
- `npm run api:generate:snapshot` : réussi ; génération reproductible vérifiée par comparaison des empreintes des fichiers.
- `git diff --check` : réussi. Aucun fichier backend ni migration SQL modifié.
- Dépendance directe ajoutée : `ng-openapi-gen@1.1.0` (développement).

Le backend sur `localhost:8080` était indisponible pendant cette validation. Le client utilise l’export OpenAPI réel produit par les tests du backend le 24 septembre 2026, conservé dans `openapi.json`. La recette avec le serveur réel reste à effectuer ; les tests navigateur ci-dessus utilisent des réponses HTTP simulées, isolées des écrans applicatifs. Dès que le backend tourne, `npm run api:generate` permet de régénérer directement depuis `/v3/api-docs`.
