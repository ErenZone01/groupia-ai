# Groupia — Authentification V3 et profil personnel

La V3 reprend la direction validée en maquette, en continuité du design Groupia V2.

## Connexion et contenu visuel

- Connexion **par téléphone** et mot de passe, identité de marque vert premium.
- Carrousel à trois slides : groupages existants, vente directe **à venir**,
  lien public de groupage utilisable par les clientes **sans compte**.
- Passage automatique toutes les 6,5 secondes ; flèches, onglets, indicateurs,
  pause au survol/focus, onglet masqué et préférence « réduire les animations ».
- Pas de promesse de prix barrés, d'économies, d'utilisateurs ou de fonctionnalités
  non implémentées.
- Google/Apple, récupération de mot de passe et « se souvenir de moi » non affichés
  tant que les parcours complets ne sont pas disponibles.

## Inscription V3

Requis : prénom, nom (80 caractères maximum chacun), date de naissance
(réelle, passée, >= 1900-01-01), téléphone, mot de passe et confirmation.
Email toujours facultatif. Le frontend bloque les valeurs manquantes et
le backend vérifie également tous les champs : aucune simple validation HTML
ne suffit à contourner cette exigence.

Backend : `POST /api/auth/register` conserve le code 202 et sa réponse
anonymisée pour ne pas divulguer l'existence d'un compte.
Nouvelle migration **031** : colonnes `first_name`, `last_name` et
`birth_date`. Les colonnes sont nullable uniquement pour préserver
les comptes déjà enregistrés ; **les nouveaux clients HTTP sont obligés de
renseigner les trois champs**. Aucun profil fictif ajouté aux anciens comptes.

## Accueil et confidentialité

- `GET /api/auth/me` est un endpoint **privé**, protégé par JWT Bearer et
  par la vérification du compte actif, avec `Cache-Control: no-store`.
- Il expose uniquement `firstName` et `lastName`, **jamais la date de
  naissance**. Aucun nom ni date ajouté au JWT.
- L'accueil affiche « Bonjour [prénom] ! » quand un profil est connu.
  Les anciens comptes sans prénom conservent leur accueil normal.
- L'intercepteur existant a été adapté : `/auth/me` n'est pas traité
  comme une opération publique sans Bearer.

## Tests locaux requis avant fusion

```powershell
git fetch origin
git switch --track origin/auth-ux-v3-profile-slider
cd Backend
.\mvnw.cmd spotless:apply
.\mvnw.cmd verify
cd ../Frontend
npm run lint
npm test
npm run build
npx playwright test e2e/auth-ux-v2.spec.ts e2e/auth-ux-v3.spec.ts e2e/business.spec.ts
npm run test:e2e
```

Vérifier également les requêtes OpenAPI régénérées avec `npm run api:generate`
lorsque le backend V3 est démarré, la navigation clavier, les résolutions
320/360/390/768/1440 px et les thèmes clair/sombre. La compilation, les
tests et l'audit visuel n'ont pas été exécutés depuis l'éditeur GitHub distant.
Ne pas fusionner cette PR avant validation.

## Empilement
PR V3 sur `auth-ux-v2` (#45), dépend de #44, #43, #42 et #41.
