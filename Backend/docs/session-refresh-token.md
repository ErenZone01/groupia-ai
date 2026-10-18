# Configuration des sessions V1

Lire l'ADR `../adr/0028-session-refresh-token.md` pour les règles et limites.

## Configuration explicite

Fournir `GROUPIA_REFRESH_TOKEN_PEPPER_BASE64` : au moins 32 octets aléatoires, encodés en Base64, distincts du JWT, du rate limit et des clés customer-access. Aucune valeur par défaut. Ne pas le committer, l'afficher ou l'envoyer dans une conversation.

`GROUPIA_AUTH_ALLOWED_ORIGINS` contient les origins exactes séparées par virgules, sans slash final. Pour le tunnel actuel, définir la valeur exacte de l'URL HTTPS cloudflared active, par exemple `https://mesh-penguin-automation-spreading.trycloudflare.com` si ce tunnel est encore celui utilisé. Un nouveau tunnel nécessite de mettre à jour cette configuration et redémarrer le backend. Ne pas accepter `*.trycloudflare.com` et ne pas déduire l'origin de X-Forwarded-Host.

Conserver `GROUPIA_AUTH_COOKIE_SECURE=true` pour le tunnel HTTPS. En HTTP local uniquement : false et origins limitées à `http://localhost:4200` ou `http://127.0.0.1:4200`. Ne pas mélanger une origin distante et le cookie insecure. Le profil prod refuse false et les origins HTTP.

Propriétés supplémentaires Spring sous `groupia.security.session` :

| Propriété | Défaut | Bornes |
| --- | --- | --- |
| absolute-ttl | 30d | 1 minute à 90 jours |
| idle-ttl | 7d | 1 minute à absolute-ttl |
| rotation-grace | 15s | 1 à 30 secondes |
| refresh-limit | 30 | 1 à 300 |
| refresh-window | 1m | 1 seconde à 15 minutes |
| cleanup-delay | 3600000 ms | planification de purge |

TTL access existant : `GROUPIA_JWT_ACCESS_TOKEN_TTL=15m`. Ne pas le rallonger pour remplacer le refresh. Frontend : injection `REFRESH_AHEAD_MS` (60000 par défaut).

## API et outils manuels

POST `/api/auth/register`, `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout` : application/json, Origin autorisée exacte, X-Groupia-Auth=1. Origin est fournie automatiquement par le navigateur ; Postman/curl doivent l'envoyer explicitement et conserver leur cookie jar. Ne jamais consigner les headers complets, cookies ou Authorization.

Login retourne accessToken, tokenType=Bearer, expiresIn et sessionId. Refresh a un corps vide (tout en envoyant Content-Type application/json), aucun Authorization, aucune query. Il retourne le même contrat et remplace le cookie après commit. Logout utilise aussi un corps vide et renvoie 204 avec cookie expiré.

409 AUTH_REFRESH_CONCURRENT : attendre brièvement, utiliser le cookie partagé mis à jour, reprise bornée. 401 AUTH_REFRESH_REUSED révoque durablement la famille. 429 expose Retry-After. Une erreur DB reste une erreur serveur ; ne pas la transformer en succès.

Les anciens JWT sans sid sont refusés : prévoir une reconnexion au déploiement. Le frontend efface `groupia.session` au bootstrap, sans migration du JWT vers un autre stockage.

## Vérification

Les tests PostgreSQL couvrent rotation, deux instances, replay commit, logout concurrent, quotas, expirations, rollback d'une rotation et upgrade 027→028. Les tests Angular couvrent single-flight, rejeu strict, génération, indisponibilité et messages tardifs. Playwright vérifie les cookies HttpOnly et deux pages d'un même contexte navigateur avec un serveur de contrat simulé ; les validations transactionnelles réelles sont celles des tests PostgreSQL. Aucun paiement provider réel n'est déclenché.
