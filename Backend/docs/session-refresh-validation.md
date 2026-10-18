# Session / Refresh Token V1 — rapport de livraison

Branche : `session-refresh-token-v1`. Base : `20ebc73cdc8d3325252a1480698bdd3f57fbc7b8`.
Validations finales vertes le 18 octobre 2026. Commit/push autorisés sur cette branche uniquement ; aucun merge ni création de PR.

| Point | Implémentation / preuve |
| --- | --- |
| 1. Architecture | Sessions dans identity, JWT court et refresh opaque HttpOnly, état durable PostgreSQL. Aucun nouveau composant réseau ou module métier. |
| 2. Migration | 028-auth-sessions.sql, incluse au changelog maître ; aucune migration historique réécrite. |
| 3. Tables / contraintes | auth_sessions + auth_refresh_tokens ; FK compte et session, PK (session,génération), hash unique 32 octets, index partiel un seul token courant, index utilisateur et expiration, dates cohérentes, triggers d’immutabilité. |
| 4. Génération / hash | SecureRandom 32 octets, Base64url canonique sans padding, HMAC-SHA256 avec pepper explicite indépendant. Aucun brut en base. |
| 5. Cookie | HTTPS : __Secure-groupia_refresh, HttpOnly, Secure, SameSite=Lax, Path=/api/auth, sans Domain. HTTP loopback explicitement non-prod : groupia_refresh. Max-Age plafonné. |
| 6. Login | BCrypt 12, dummy hash, ACTIVE, erreur générique conservés ; nouvelle famille et access seulement après commit. Remplacement limité à l’ancien cookie présenté. |
| 7. Refresh | Cookie seul, état compte/session relu sous verrou ; contrôle des expirations et quotas ; aucun Bearer/body/query utilisé comme refresh. |
| 8. Rotation | Consommation Rn + insertion Rn+1 + mise à jour idle dans une transaction atomique. Absolu inchangé et JWT plafonné à l’absolu. |
| 9. Replay | Ancien token au-delà de grace : révocation commitée, puis 401 AUTH_REFRESH_REUSED. Historique conservé jusqu’à expiration absolue/purge. |
| 10. Grace | 15 secondes par défaut ; 409 AUTH_REFRESH_CONCURRENT sans révocation ni Set-Cookie. |
| 11. Verrouillage | PostgreSQL FOR SHARE sur compte puis FOR UPDATE sur session ; multi-instance, sans verrou global. Test de deux services et race directe de l’index unique. |
| 12. Logout | Courant ou prédécesseur connu révoque la famille ; absent/inconnu idempotent 204 ; cookie expiré après commit. Pas de blacklist access V1. |
| 13. CSRF | POST JSON, Origin exacte et unique, X-Groupia-Auth=1 ; rejet cross-site Fetch Metadata. Le cookie ne donne aucun accès aux APIs métier. |
| 14. Quotas | Login/register conservés. Refresh 30/min/session reconnue, identité HMAC distincte et compteur sous verrou de session ; aucun bucket pour token inconnu, logout toujours possible. |
| 15. JWT | iss/sub/aud/iat/exp/jti conservés ; sid UUID validé et auth_time stable ajoutés. Aucune PII ni rôle boutique. |
| 16. Mémoire frontend | Access uniquement en mémoire. Suppression de groupia.session au bootstrap ; aucun refresh dans Storage ou IndexedDB. |
| 17. Bootstrap | RESTORING / AUTHENTICATED / ANONYMOUS / RESTORATION_UNAVAILABLE ; guards attendent, pages publiques non bloquées. |
| 18. Interceptor | Single-flight ; seuls les vrais 401 Bearer autorisent un rejeu unique conservant corps, méthode, paramètres et Idempotency-Key. Aucun rejeu financier réseau/5xx. |
| 19. Multi-onglets | Web Lock commun login/refresh/logout, BroadcastChannel limité aux access mémoire, vérification après acquisition ; fallback 409 avec trois tentatives maximum. |
| 20. Generation | Stable pour même sid ; changement de session/logout/invalidation incrémente. Réponses tardives et anciens 401 ne remplacent pas une nouvelle identité. |
| 21. Réseau | Access valide conservé pendant panne ; sinon reprise explicite. 403 sans boucle automatique ; 429 respecte Retry-After. Logout hors ligne mémorise seulement une intention non secrète. |
| 22. OpenAPI | Client Angular régénéré depuis la spécification Spring testée ; sessionId exposé, aucun refresh JSON, logout documenté 204. |
| 23. Backend test | mvnw.cmd test : 1 078 tests PASS ; mvnw.cmd verify : 1 081 tests PASS, 0 échec, 0 erreur, 0 ignoré. Trois contrôles supplémentaires ont été ajoutés entre les deux passages. |
| 24. Angular | 356 tests PASS ; npm run lint et build production PASS. Quatre avertissements NG8107 préexistants dans ShippingOrderEditor, hors de cette tranche. |
| 25. Playwright | 182 tests PASS (7,6 min), dont 7 scénarios dédiés aux sessions ; aucun échec. |
| 26. Migrations | Fresh PostgreSQL et upgrade 027→028 testés ; comptes historiques et checksums préservés. Python : 4 tests PASS ; check_migrations.py PASS (22 migrations gelées par cet outil), complété par comparaison des 27 blobs historiques avec main. |
| 27. Modulith | ApplicationModules.verify() PASS ; 3 tests ModularityTests verts. |
| 28. Secrets | tools/scan_secrets.py : 0 finding ; aucune valeur sensible affichée. |
| 29. Commit | SHA communiqué avec le rapport final après les validations et le push. |
| 30. Fichiers majeurs | Voir ci-dessous. |
| 31. Limites résiduelles | Voir ci-dessous et l’ADR 0028. |
| 32. Historique SQL | Vérification des 27 blobs Git 001–027 contre la base ; 028 seule nouvelle migration. |
| 33. Git | main reste à la base attendue ; aucun merge ni PR automatique. Commit/push sur la branche demandée uniquement. |

## Fichiers majeurs

Les chemins identity sont relatifs à Backend/src/main/java/sn/groupia ; db/changelog à Backend/src/main/resources. Les tests backend sont dans Backend/src/test/java/sn/groupia.

- identity/application/service/RefreshSessions.java : transactions et résultats commités.
- identity/infrastructure/persistence/JdbcAuthSessions.java : verrous et stockage.
- identity/infrastructure/security/SessionSettings.java et RefreshSecrets.java : configuration et cryptographie.
- identity/infrastructure/web/AuthController.java et AuthOriginFilter.java : cookies et protection navigateur.
- db/changelog/028-auth-sessions.sql : schéma, index, contraintes, immutabilité.
- RefreshSessionIntegrationTests, RefreshSessionMigrationTests, SessionSettingsTests : tests de sécurité et concurrence.
- Frontend/src/app/core/auth : mémoire, coordination, message de reprise ; guard et interceptor adaptés.
- Frontend/src/app/features/auth/session-unavailable.page.ts : indisponibilité distincte d’une déconnexion.
- Frontend/openapi.json et api/generated : contrat régénéré.
- Frontend/e2e/refresh-session.spec.ts : cookies réels du navigateur, reload, deux pages, déconnexion et renouvellement silencieux.
- ADR 0028, guide session-refresh-token.md, README frontend et documentation de sécurité actualisés.

Les autres changements de tests ajoutent sid aux JWT synthétiques, les headers Origin/CSRF aux appels auth, les nouvelles tables aux nettoyages et 028 aux comparaisons de checksums historiques.

## Limites acceptées / exploitation

- Un JWT déjà émis peut rester valide jusqu’à son court TTL après logout ; aucune blacklist V1.
- Une rotation commitée dont la réponse et le cookie sont perdus peut exceptionnellement nécessiter une reconnexion.
- Un changement de pepper invalide les refresh existants : déploiement coordonné des instances et reconnexion à prévoir.
- XSS reste capable d’utiliser une session ouverte ; HttpOnly empêche la lecture du refresh, pas toute action d’un script compromis.
- Le tombstone non secret du logout reste jusqu’au login explicite afin d’écarter les réponses tardives.
- Le nettoyage est borné à 100 familles expirées par passage ; surveiller le backlog avant montée en charge.
- Les tests Playwright utilisent des contrats API simulés ; les transactions et contraintes sont validées séparément sur PostgreSQL réel.
- Aucun step-up payout ajouté ; auth_time est prêt pour une tranche dédiée.

Configurer explicitement GROUPIA_REFRESH_TOKEN_PEPPER_BASE64 et GROUPIA_AUTH_ALLOWED_ORIGINS avant lancement. Le tunnel HTTPS actuel utilise cookie-secure=true. Changer de tunnel implique de mettre à jour l’Origin exacte autorisée. Aucun secret local réel n’est généré ou modifié par cette tranche.
