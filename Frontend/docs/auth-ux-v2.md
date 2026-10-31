# Groupia — Authentification UX V2

Maquettes approuvées : connexion et inscription, desktop + mobile, univers premium vert Groupia.

## Portée
- `/connexion` : accueil, téléphone, mot de passe, visibilité du mot de passe,
  session expirée, erreurs renvoyées par le backend, retour aux routes `/app/...` autorisées.
- `/inscription` : téléphone, email facultatif, mot de passe (15 caractères minimum,
  72 octets UTF-8 maximum), confirmation du mot de passe, redirection après inscription.
- Décor de commerce et mini-illustration réalisés avec des éléments CSS/SVG, sans
  exemples de ventes ni statistiques présentés comme des données réelles.
- Versions mobile et desktop, thèmes clair/sombre, taille des champs et focus accessibles.
- Logo et authentification existants ; **aucun changement backend, migration, API ni cookie**.

## Écarts assumés par rapport aux images conceptuelles
Les images étaient des références de design, et non des fonctionnalités déjà disponibles.

- Connexion **uniquement par téléphone** actuellement. Pas de connexion par email.
- La requête d'inscription accepte uniquement `phoneNumber`, `password`, et un
  `email` facultatif. Le prénom, le nom et le nom de la boutique sont à
  configurer dans un futur flux backend adapté ; **pas de champs décoratifs non sauvegardés**.
- Pas de bouton Google/Apple, de récupération de mot de passe ni de « se souvenir de moi »
  tant que ces parcours ne sont pas pris en charge de bout en bout.
- Ne pas prétendre que les clientes doivent créer un compte : les parcours
  d'achat publics de Groupia sont indépendants de l'espace gérante.

## Sécurité et ergonomie
- Le mot de passe reste une chaîne transmise uniquement au service
  d'authentification existant, sans nouvel enregistrement côté navigateur.
- Bouton afficher/masquer indépendant de la soumission.
- Formulaires invalides non soumis ; confirmation contrôlée également côté composant.
- La soumission évite les doubles appels pendant le chargement.
- Le message « session expirée » et les erreurs réseau restent disponibles.

## Vérifications à effectuer en local
Les tests ont été ajoutés mais **pas exécutés** depuis l'éditeur GitHub distant.

```powershell
git fetch origin
git switch --track origin/auth-ux-v2
cd Frontend
npm run lint
npm test
npm run build
npx playwright test e2e/auth-ux-v2.spec.ts
npm run test:e2e
```

Contrôler visuellement 320/360/390/768/1440 px, clair/sombre,
focus clavier, zoom navigateur 200 %, saisie téléphone internationale,
mots de passe UTF-8, serveur indisponible, réouverture de session.
Les tests d'intégration backend et CI complète restent requis avant fusion.

## Dépendance PR
Branche basée sur `public-checkout-ux-v2-address` (PR #44), elle-même
empilée sur #43, #42 et #41. Préserver l'ordre de fusion et les contrôles CI.
