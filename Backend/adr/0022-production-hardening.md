# ADR-0022 — Quotas d’authentification et politique de production

Date : 2026-10-10. Statut : proposé pour validation de production-hardening-v1.

## Contexte

Le monolithe a déjà ses frontières Modulith et invariants financiers testés, mais login/register restent exposés sans quota partagé. Le contrat 201/409 de register permet d’identifier les numéros inscrits. Il manque une politique explicite de headers, de supervision et de CI. Les migrations 001–022 sont gelées.

## Décision

- identity possède les compteurs PostgreSQL via 023. HMAC par scope, quotas par adresse distante et téléphone normalisé, fenêtre fixe, plafond global et nettoyage borné. Chaque admission est une transaction indépendante et atomique. Pas d’accès à customeraccess, pas de Redis.
- Le registre public accepte une requête valide par 202 vide, compte nouveau ou existant. Aucun accès automatique ni remplacement des identifiants. Login vérifie toujours les identifiants et consomme le budget même en cas de succès.
- L’application ne fait pas confiance aux headers proxy. L’edge doit limiter les IP clientes. `groupia.security.auth-rate-limit.network-enabled` contrôle uniquement le quota réseau ; il vaut false par défaut en prod derrière le proxy partagé et true hors prod. Le quota sujet PostgreSQL/HMAC reste actif lorsque le limiteur est activé. Une réactivation réseau en prod exige une configuration explicite de trusted proxies et des tests anti-usurpation ; le drapeau seul ne rend pas X-Forwarded-For fiable.
- Le profil prod ferme la documentation et isole Actuator sur loopback. Métriques par token de collecte dédié ; health minimal. JWT et session cliente/CSRF restent séparés.
- Angular strict, CSP avec nonce par document, Trusted Types et pas de scripts inline/eval. L’exception style-src-attr est limitée aux styles dynamiques existants.
- CI bloquante, scans sans valeurs sensibles, outils/actions épinglés, format Java progressif et manifeste SHA-256 comparé à la base immuable.

## Conséquences et limites

Les clients doivent suivre le nouveau contrat d’inscription. Le verrou d’admission PostgreSQL est bref mais global : mesurer sa contention avant toute optimisation. Un simple verrou par bucket ne protège pas maxBuckets entre créations concurrentes de clés distinctes. Il faudrait conserver une réservation globale de capacité et coordonner les verrous avec le cleanup ; cette évolution est différée. Des tests multi-connexion vérifient le budget d’une même clé et la capacité face à des clés distinctes. Une indisponibilité DB ne permet pas de contourner les quotas. Le réseau edge et les sauvegardes/rotations restent des responsabilités de déploiement, non des garanties acquises par le code. Aucun merge, changement d’historique ou rotation externe n’est effectué dans cette branche.

Détails, variables et exploitation : [production-hardening.md](../docs/production-hardening.md). Analyse préalable : [production-hardening-gap.md](../docs/production-hardening-gap.md).
