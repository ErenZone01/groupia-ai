# ADR 0028 — Sessions et rotation des refresh tokens

Statut : implémenté et validé le 18 octobre 2026. Voir le rapport ../docs/session-refresh-validation.md.

## Décision

Le module identity possède les sessions PostgreSQL. Le JWT d'accès reste court (15 minutes par défaut) et porte `sid` et `auth_time`, sans rôle boutique ni PII. Le refresh token contient 32 octets SecureRandom encodés en Base64url canonique sans padding. Seul HMAC-SHA256 avec un pepper explicitement configuré, distinct de toutes les autres clés Groupia, est persisté. Aucun refresh brut n'est stocké côté serveur ou retourné en JSON.

La migration 028 ajoute `auth_sessions` et `auth_refresh_tokens`. L'identifiant de session est l'identifiant de famille. Les tokens ont une PK `(session_id,generation)`, un hash unique de 32 octets et un index unique partiel limitant à un token non consommé par session. Des contraintes temporelles et triggers interdisent de réécrire l'identité, l'expiration absolue, une révocation ou une consommation déjà enregistrée. Les index utilisateur et expiration servent aux opérations et à la purge.

## Transactions, concurrence et replay

Login conserve BCrypt 12 et le hash factice, puis crée une famille dans sa transaction. Un cookie précédent connu permet de révoquer uniquement cette ancienne famille de navigateur. Les autres appareils restent connectés.

Refresh verrouille le compte `FOR SHARE`, puis la session `FOR UPDATE`, relit le token et contrôle ACTIVE, révocation et expirations. Cet ordre évite qu'une désactivation concurrente soit ignorée et doit être respecté par de futures opérations d'administration. Le verrou de ligne fonctionne entre instances PostgreSQL ; aucun verrou global ni mutex JVM ne protège la rotation.

Le token courant est consommé puis son successeur inséré dans la même transaction. Un prédécesseur consommé depuis au plus 15 secondes donne 409 `AUTH_REFRESH_CONCURRENT`, sans Set-Cookie ni révocation. Après cette fenêtre, toute la famille est révoquée. Le service retourne un résultat ; le controller transforme ensuite le résultat en 401 `AUTH_REFRESH_REUSED`, après commit de la révocation. Une panne DB remonte sans credential de succès. Logout accepte un token courant ou historique et révoque la famille de manière idempotente, puis expire le cookie.

Les anciens hashes restent présents jusqu'à l'expiration absolue. Une purge horaire supprime au plus 100 familles expirées avec `SKIP LOCKED` et cascade sur leurs tokens. Cette cadence est volontairement bornée : mesurer le backlog avant montée en charge et ajuster le délai si nécessaire.

## Durées et quotas

Par défaut : access 15 minutes, session absolue 30 jours, idle 7 jours, grace 15 secondes. Idle est plafonné à l'absolu, immuable après login. Chaque JWT est plafonné à cette même expiration absolue. `auth_time` reste l'instant du mot de passe ; `iat`, `exp` et `jti` changent à chaque émission.

Le scope de quota refresh est distinct des quotas login/register : compteur et fenêtre portés par la session, sous son verrou, avec identité HMAC domaine `refresh-session`. Cela évite de modifier la migration 023 ou de réutiliser son verrou global. Par défaut 30 admissions/minute/session reconnue ; les tokens inconnus ne créent aucun bucket. La révocation d'un replay hors grace précède le quota. Logout n'est jamais limité par ce quota. X-Forwarded-For n'est pas une source de confiance.

## Cookie et protection navigateur

HTTPS : `__Secure-groupia_refresh; HttpOnly; Secure; SameSite=Lax; Path=/api/auth`, sans Domain. Max-Age ne dépasse pas l'idle ni l'absolu. Développement HTTP loopback explicitement configuré : `groupia_refresh`, Secure=false. Le mode production refuse cette configuration.

Register, login, refresh et logout exigent POST, application/json, `X-Groupia-Auth: 1` et une Origin exactement autorisée côté serveur. Origin absente, null, dupliquée, inconnue et Sec-Fetch-Site cross-site sont refusés. Aucun host forwarded fourni par le client ne configure la confiance. Refresh/logout n'acceptent de credential que dans le cookie, jamais Bearer, query ou corps. Le cookie n'authentifie aucune API métier. Spring reste STATELESS.

La topologie actuelle est même origine : navigateur → Cloudflare tunnel → Angular/proxy `/api` → Spring. Aucun CORS supplémentaire n'est nécessaire. Un futur changement de topologie exige une revue des origins et des cookies ; les cookies tiers ne sont pas le design principal.

## Frontend

Au bootstrap, l'ancienne clé `sessionStorage[groupia.session]` est supprimée. Access et sa date d'expiration existent seulement en mémoire. Le refresh n'est jamais visible en JavaScript. La restauration démarre sans bloquer les pages publiques ; les guards attendent et distinguent RESTORING, AUTHENTICATED, ANONYMOUS et RESTORATION_UNAVAILABLE.

Un refresh anticipé configurable (60 secondes par défaut) n'est tenté que pour un onglet visible avec activité récente (5 minutes). Le single-flight local, un Web Lock commun login/refresh/logout et BroadcastChannel coordonnent les onglets. Le canal transmet seulement le credential d'accès mémoire et sid, jamais refresh/cookie/hash. Après acquisition du verrou, l'état est recontrôlé. Sans Web Locks, le 409 serveur autorise trois tentatives au total avec une attente bornée.

L'interceptor ne renouvelle et ne rejoue qu'un véritable 401 Bearer de la couche sécurité (`AUTHENTICATION_REQUIRED` ou WWW-Authenticate Bearer). Rejeu une seule fois en conservant méthode, corps, paramètres et Idempotency-Key. Aucune commande financière n'est rejouée après erreur réseau/timeout/5xx.

Un refresh du même sid conserve generation(), donc les stores métier ne sont pas vidés. Changement d'identité ou déconnexion l'incrémente. Une barrière interne invalide les réponses tardives. 401 invalide la session ; 403 expose une erreur de sécurité sans boucle ; 429 respecte Retry-After ; une panne temporaire conserve un access encore valide ou affiche un écran de reprise.

Le logout vide la mémoire même hors ligne et diffuse sa demande. Une intention non secrète `groupia.logout-pending` empêche toute restauration involontaire au rechargement ; une reprise du logout est possible. Même après succès, le tombstone reste jusqu'au prochain login explicite pour refuser les messages tardifs. Aucun JWT n'y est stocké.

## Limites acceptées et exploitation

Pas de blacklist access JWT V1 : après logout un access déjà émis peut rester utilisable pendant son court TTL (et la tolérance d'horloge du validateur). Un changement de pepper invalide les refresh existants ; déployer simultanément la nouvelle clé sur toutes les instances, révoquer les familles existantes via une opération contrôlée et prévoir la reconnexion. Aucun fallback vers une ancienne clé, aucun secret auto-généré en production.

Si la rotation est commitée mais que cookie et réponse sont perdus, une reconnexion peut exceptionnellement être nécessaire. Aucun refresh brut serveur ni dérivation réversible ne résout ce compromis.

Le futur step-up des payouts pourra utiliser `auth_time`, qui ne devient pas récent au refresh. Aucun step-up ni changement financier n'est introduit ici. XSS reste capable d'utiliser la session d'un navigateur ouvert : HttpOnly protège la lecture du refresh, pas une application déjà compromise.
