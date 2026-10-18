# Production hardening / DevSecOps V1

## 1. Architecture de sécurité

Java 21, Boot 4.1.1, Modulith 2.1.1, PostgreSQL 18.6 et Angular 22 sont conservés. Modules fermés, accès inter-module via `::api`, aucun repository interne importé. Aucun composant réseau supplémentaire. JWT organisatrices en mémoire pour les APIs métier ; refresh HttpOnly limité aux endpoints auth avec Origin/CSRF dédiés (voir [Session V1](session-refresh-token.md)). L’accès cliente HttpOnly et son CSRF restent inchangés. Le profil `prod` doit être explicitement activé. Ne jamais activer `customer-access-local` en production.

L'inscription HTTP retourne désormais **202 sans corps**, que le numéro soit nouveau ou déjà inscrit, après validation des entrées. Aucune session n'est créée et aucun mot de passe existant n'est remplacé. Login conserve la vérification BCrypt (y compris le hash factice pour les comptes absents), les erreurs génériques et le JWT court. Les clients doivent ensuite demander la connexion, sans annoncer que la création est certaine. Le contrat OpenAPI et la collection Postman suivent ce changement volontaire anti-énumération.

## 2. Secrets et rotation

Voir [SECURITY-ROTATION.md](../../SECURITY-ROTATION.md). Aucune rotation fournisseur n'est effectuée automatiquement. Pas de réécriture Git. `.env`, certificats et sorties sont ignorés à la racine ; seuls les exemples vides sont versionnés. Gitleaks analyse le contenu courant, pas l'historique contaminé, et n'affiche que règle/fichier/ligne. Les rares exclusions sont exactes (fixture publique non fonctionnelle, hashes du manifeste), jamais un dossier complet de tests.

## 3. Variables et déploiement

- `SPRING_PROFILES_ACTIVE=prod`, `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` via le coffre du déploiement ; accès réseau DB privé et TLS selon votre infrastructure.
- `GROUPIA_JWT_SECRET_BASE64`, issuer et audience adaptés ; `GROUPIA_AUTH_RATE_LIMIT_KEY_BASE64` indépendant et partagé entre instances.
- `GROUPIA_MONITORING_TOKEN` pour le collecteur seulement ; absent = métriques interdites. `MANAGEMENT_PORT=9091` par défaut, lié à `127.0.0.1`.
- `GROUPIA_PUBLIC_BASE_URL` = origine HTTPS réelle pour les liens cliente. Cookies Secure obligatoires. Vérifier toutes les variables providers dans `.env.example`, séparées entre sandbox et production. Activer les adapters uniquement après validation de leur configuration réelle.
- `prod` n'importe pas `.env`, désactive Docker Compose automatique, devtools, Swagger et les dumps SQL Hibernate. Le pool, les timeouts DB et la rétention doivent être dimensionnés à partir de mesures.
- Compose local exige maintenant `POSTGRES_PASSWORD` fourni par l'opérateur. Aucun mot de passe de remplacement n'est inventé. Le changement d'environnement d'un container ne change pas le mot de passe d'une base déjà initialisée : conserver sa valeur réelle ou suivre la procédure de rotation DB, jamais supprimer son volume.

PostgreSQL est épinglé à **18.6**, version constatée sur l'image locale préexistante ; aucun changement de majeur implicite. Cloudflared est épinglé à **2026.10.0**, release officielle vérifiée. Les tests emploient PostgreSQL 18.6 isolé via Testcontainers. Node CI 24.16.0 LTS, Java CI Temurin 21. Les tags de version ne sont pas des digests immuables : surveiller leurs mises à jour de sécurité.

## 4. Headers et Angular

L'API reçoit `nosniff`, `DENY`, `no-referrer`, Permissions-Policy restrictive et, en prod, une CSP `default-src 'none'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'`. HSTS 1 an uniquement si la requête est effectivement TLS et le profil prod actif. Aucun HSTS local HTTP ; les en-têtes Forwarded ne déclenchent pas HSTS.

Le serveur de fichiers compilés `Frontend/tools/serve-production.cjs` est lié au loopback et sert GET/HEAD. Il doit être placé derrière un proxy TLS ; ce proxy route `/api/**` vers Spring, les autres chemins vers ce serveur. `HOST` ne doit être élargi que dans un réseau conteneur privé. `GROUPIA_HTTPS=true` est réservé à ce déploiement TLS, jamais au développement HTTP.

Sa CSP autorise seulement scripts, fonts et connexions same-origin. Angular reçoit un nonce aléatoire **par réponse HTML** via `ngCspNonce`; `inlineCritical` est désactivé. HTML `no-store`, pas de réutilisation de nonce au CDN. Trusted Types activé avec seulement `angular` et `angular#bundler`. Pas de `unsafe-eval` ni scripts inline. La seule exception est `style-src-attr 'unsafe-inline'`, pour les dimensions dynamiques/liaisons de styles des composants existants ; elle n'autorise pas les scripts. Les feuilles de style Angular nécessitent le nonce. Les tests navigateur contrôlent les violations CSP et le fonctionnement des chunks lazy-loadés.

Si vous remplacez ce serveur par nginx/CDN, reproduire l'injection du nonce et ces politiques ; un simple hébergement statique sans nonce ne suffit pas. Conserver les chemins de retour des paiements sur la même origine ; la navigation top-level vers un checkout fournisseur reste possible.

## 5. Swagger et Actuator

Swagger et `/v3/api-docs` restent utiles en développement, désactivés et refusés en prod. Actuator est sur un port local distinct : uniquement `health` et `metrics`. Health/probes affichent seulement le statut, jamais les composants/détails. Pas de env, beans, heapdump ou endpoint Modulith. Metrics exige le token de collecte dédié, comparé en temps constant, et n'accepte pas un JWT organisatrice à sa place. Ne pas publier le port management via Cloudflare ou le reverse proxy public. Le collecteur doit être local ou utiliser un canal privé authentifié.

## 6. Rate limiting authentification

Migration **023-auth-rate-limits.sql** : table possédée par identity, clé `(scope,key_hash)`, compteur, expiration et index d'expiration. Aucune donnée métier modifiée. HMAC-SHA256 séparé par scope ; aucune adresse/numéro brut en DB. Le téléphone est normalisé ; les formats invalides partagent un quota prudent. Lorsque `groupia.security.auth-rate-limit.network-enabled=true`, le filtre réseau précède le parsing JSON : les bodies malformés consomment aussi le quota réseau. Il utilise l'adresse distante du serveur, jamais Forwarded/X-Forwarded-For. Ce paramètre vaut **false par défaut dans le profil prod**, adapté au proxy partagé Groupia ; hors prod, il vaut true. Le quota par sujet PostgreSQL/HMAC reste actif dès que `enabled=true`, indépendamment de `network-enabled`. Désactiver le quota réseau ne crée aucun bucket réseau et ne désactive pas le quota sujet. Les bodies illisibles et les attaques réparties sur plusieurs sujets doivent être limités à l'edge.

Fenêtre fixe par défaut 15 minutes : login sujet 10, register sujet 5 ; si le quota réseau est explicitement activé, login réseau 60 et register réseau 20. **Succès et échecs consomment** ; un succès ne réinitialise pas le budget. 429 générique et `Retry-After` conservateur (durée totale de fenêtre). Configurer sous `groupia.security.auth-rate-limit.*`. Limite globale 100 000 buckets, maximum configurable 1 million ; refus fermé à saturation. Nettoyage toutes les 60 secondes, au plus 1 000 lignes par passage, et à l'admission d'une nouvelle clé. L'index d'expiration facilite ce nettoyage.

Transaction REQUIRES_NEW par admission, verrou advisory transactionnel commun, court et sans BCrypt ni appel fournisseur. Cela garantit les quotas entre plusieurs instances et persiste le compteur même si login échoue ensuite. Le verrou global et le count de capacité privilégient une borne exacte ; mesurer sous charge avant de remplacer ce modèle. Une panne DB ne doit jamais autoriser un login sans quota. À grande charge, ajuster le proxy et observer la contention, sans ajouter Redis par défaut.

**Réévaluation du verrou 742019023** : un verrou par bucket réduirait la contention entre clés, mais deux créations de clés distinctes pourraient lire simultanément `count = maxBuckets - 1` puis dépasser la borne. Les seuls verrous de lignes ne protègent pas non plus une clé encore absente. Une évolution pourrait verrouiller les mises à jour par bucket tout en sérialisant les créations et la réservation de capacité ; elle exigerait un ordre de verrous cohérent avec expiration/nettoyage et des tests multi-connexion sur création, renouvellement et suppression. Cette complexité n'est pas introduite sans mesure. Le verrou global reste inchangé : les tests utilisent des transactions indépendantes concurrentes, sur une même clé (budget exact) et sur 48 clés distinctes (capacité 20 exacte). Aucun retry automatique ni changement des règles métier d'idempotence ; chaque nouvelle tentative auth consomme normalement une unité. Toutes les instances doivent partager la clé HMAC et les mêmes budgets/capacité. Mesurer latence p95/p99 d'admission, attente du verrou, débit, saturation du pool et du stockage avant montée en charge.

## 7. Proxy et Cloudflare

`server.forward-headers-strategy=none`. Un backend derrière proxy voit l'IP de ce proxy : activer les petits budgets réseau par défaut créerait un blocage partagé entre utilisateurs. C'est pourquoi `groupia.security.auth-rate-limit.network-enabled=false` en prod ; ne pas désactiver le limiteur global pour résoudre ce problème. **Avant production**, configurer des limites edge par IP réelle sur login/register/tracking/checkout, un plafond de taille JSON (64 KiB pour auth), des timeouts et une limite de connexions. N'exposer que le proxy/tunnel autorisé à l'origine. Le vrai rate limiting par IP cliente doit être appliqué à l'edge Cloudflare/reverse proxy, avec des règles distinctes pour login/register/tracking/checkout. Le quota sujet reste la défense distribuée applicative.

Ne pas simplement activer la confiance de tous les headers. Une future réactivation du quota réseau backend derrière proxy exige d'abord une configuration explicite de trusted proxies (plages réseau autorisées, origine inaccessible directement, suppression des headers entrants et tests anti-usurpation), puis l'activation volontaire de `groupia.security.auth-rate-limit.network-enabled=true`. Ce drapeau ne configure pas la confiance proxy et ne rend jamais X-Forwarded-For fiable à lui seul. Aucun support implicite des headers clients n'est ajouté dans cette tranche ; `none` est conservé. Le proxy TLS doit émettre HSTS sur ses réponses publiques ; ne pas dépendre du backend HTTP pour cela. Pas de logs de query strings/token sur les chemins `/s/**`, tracking ou OTP.

## 8. CI et qualité

`.github/workflows/ci.yml` : PR vers main, push main, lancement manuel ; permissions contents:read, annulation des exécutions obsolètes, timeouts, actions officielles épinglées par SHA. Contrôles sécurité avant les jobs applicatifs. Backend `./mvnw verify` inclut Spring Modulith, migrations et PostgreSQL Testcontainers sur le Docker du runner. Frontend npm ci/lint/audit/test/build/Playwright avec Chromium et dépendances. Tests fournisseurs désactivés au niveau Surefire et configurations de tests, doubles locaux seulement.

Spotless 3.7.0 + google-java-format 1.28.0 AOSP, ratchet sur `873605a27d1b34e5b0dd4a373db5dcfe3270da53` : seuls les fichiers Java modifiés depuis la base doivent être formatés. Exécuter `./mvnw spotless:apply`, puis verify. Aucun reformatage global des fichiers historiques. SpotBugs évalué et différé : la calibration des règles sur JPA/Spring demanderait une baseline distincte ; ne pas ajouter un contrôle bruyant non validé. Tests de frontières, sécurité et compilation restent bloquants.

TypeScript strict et strictTemplates sont actifs. ESLint couvre TS/templates/accessibilité, hors client généré. La règle null permet `!= null` pour le test intentionnel null **ou** undefined. Pas de désactivation générale de typage. Clients OpenAPI uniquement régénérés.

Dependabot hebdomadaire Maven/npm/actions. `npm audit --audit-level=high` bloque high/critical, y compris outils de build ; pas de `--force`. Toute future exception nécessite examen, échéance et correctif suivi. Maven est suivi par Dependabot ; un scanner CVE transitive JVM distinct reste une amélioration. CodeQL n'est pas requis pour ce dépôt privé ; l'activer plus tard selon les droits/licences disponibles. Ne pas publier d'artefacts contenant des secrets ; les rapports de tests CI ne contiennent que des données synthétiques et expirent après 3 jours.

## 9. Immutabilité des migrations

`tools/migrations-sha256.json` contient exactement les SHA-256 des **blobs Git** 001–022 du commit initial. `python tools/check_migrations.py` compare aussi le manifeste à cette base et les blobs HEAD byte-for-byte, empêchant une modification du manifeste d'autoriser silencieusement un changement. Le contenu de travail est vérifié selon la conversion CRLF→LF préexistante de Git sous Windows ; aucune migration n'est réécrite. Tous les autres octets restent contrôlés. Tests du garde-fou : altération, fichier manquant, manifeste falsifié et cas normal. 023 n'est pas gelée par ce manifeste tant qu'elle n'est pas mergée. Aucune commande clearCheckSums/reset ni modification de 001–022.

Le javadoc Spring Modulith racine était une sortie reproductible, sans consommateur runtime/build ; désindexé et ignoré. La compilation écrit déjà celui-ci sous `Backend/target/generated-spring-modulith`.

## 10. Monitoring attendu

Métriques sans identifiants, téléphone, payload, token ni cardinalité par cliente :

| Nom Micrometer | Signal / action opérateur |
| --- | --- |
| groupia.events.failed | Publications Modulith FAILED ; examiner le listener et resoumettre via mécanisme existant |
| groupia.events.exhausted | FAILED avec au moins 5 essais ; intervention requise |
| groupia.payments.review | Paiements reconciliation_required |
| groupia.webhooks.review | Receipts REVIEW_REQUIRED |
| groupia.webhooks.exhausted | Receipts REVIEW_REQUIRED après au moins 5 essais |
| groupia.provider.failures, provider=bictorys | Compteur des erreurs HTTP/transport Bictorys ; pas de corps fournisseur |
| groupia.provider.failures, provider=whatsapp | Échecs de soumission à Meta (pas les accusés de livraison finaux) |
| groupia.payouts.review / pending | Revues et décaissements en cours |
| http.server.requests, status=5xx | Erreurs HTTP du serveur (Micrometer Boot) |

Gauges DB dans chaque module propriétaire, jamais de lecture croisée. Collecter à une cadence raisonnable (ex. 60 s), alerter sur valeurs persistantes/non nulles de review/exhaustion et augmentation des compteurs ; définir les seuils normaux après mesure. Les compteurs providers sont en mémoire et repartent à zéro au redémarrage, donc exploiter un taux, pas une somme supposée durable. La réception des callbacks de livraison WhatsApp n'est pas implémentée ici. Le health PostgreSQL est fourni par Boot mais ses détails restent privés.

Logs : erreurs métier par code/ID technique ; dumps Hibernate SQL/bind/constraint désactivés pour éviter de réafficher numéros/valeurs des contraintes. Aucune exception fournisseur brute à journaliser. Ne pas activer DEBUG HTTP/SQL en prod. Le test d'inscription répétée vérifie l'absence de téléphone/mot de passe/secret dans les logs capturés.

## 11. Backup PostgreSQL et restauration

**Aucune restauration de backup n'a été effectuée dans cette tranche.** Avant mise en production : définir RPO/RTO, sauvegardes chiffrées, rétention hors site et accès restreint. Utiliser `pg_dump --format=custom` avec un compte de sauvegarde, sans mot de passe en argument (coffre / PGPASSFILE protégé). Sauvegarder également les rôles nécessaires avec une procédure contrôlée.

Tester `pg_restore --exit-on-error` dans une base **neuve isolée**, avec tous les adapters et schedulers désactivés. Contrôler volumes de lignes, totaux financiers, liens logiques, état Liquibase et démarrage en validate ; ne jamais clear les checksums. Vérifier les fichiers media externes et la disponibilité des clés nécessaires. Consigner temps de restauration et résultats. Pour un faible RPO, évaluer base backups + archivage WAL/PITR et tester la procédure avant de la déclarer prête. Ne jamais restaurer un dump de test par-dessus production.

## 12. Checklist pré-production

- [ ] Rotations et révocations terminées, prouvées dans le coffre ; aucun ancien secret encore accepté.
- [ ] CI exécutée sur GitHub, branche protégée, checks requis et reviewer configurés manuellement.
- [ ] `prod` seul, variables réelles, DB privée et compte applicatif à privilèges restreints.
- [ ] HTTPS/CSP/nonce/Trusted Types vérifiés sur l'origine réelle ; cookies Secure et CSRF testés.
- [ ] Swagger fermé ; management inaccessible depuis Internet ; collecte autorisée seulement localement.
- [ ] Quotas par IP cliente à l'edge validés ; quota réseau backend désactivé derrière le proxy partagé ; quota sujet actif. Toute réactivation backend doit suivre la procédure trusted proxies et ses tests.
- [ ] Backup restauré en environnement isolé avec preuve et RPO/RTO.
- [ ] Sandbox fournisseurs et retours/webhooks réels validés ; activation production explicite.
- [ ] Alertes event/payment/webhook/payout/providers/5xx routées à un responsable.
- [ ] Manifest 001–022 et diff propres ; pas de reset, historique réécrit ou force-push.

## 13. Performance et risques restants

Les index paiement/review, webhook retry, expiration des commandes et contraintes uniques financières existent déjà ; ajout uniquement de l'index du limiteur 023. Les listes UI sont généralement paginées ou plafonnées ; certains historiques détaillés (groupage/logistique) peuvent croître sans limite. Pas de réécriture de requêtes sans EXPLAIN/mesure. Le scheduler d'expiration parcourt par lots de 100, mais peut traiter tout le backlog dans une exécution : surveiller sa durée et la contention avant un plafond de cycles séparé. Recovery événements et retries webhooks sont bornés par lots et essais existants.

TrackingAttempts reste une défense locale secondaire, configurable via `groupia.ordering.tracking-rate-limit.per-minute` (60) et `.capacity` (10000, max100000), avec expiration 60 s et refus à saturation. Ce n'est pas un quota distribué ; edge obligatoire. La protection login/register PostgreSQL l'est.

Pas d'optimisation ni charge lancée contre une base réelle ; voir `performance/`. Les limites SQL/HMAC ne remplacent pas une protection DDoS réseau. Le scanner HEAD ne résout pas l'historique compromis. Aucune preuve de déploiement, rotation, restauration ou exécution distante GitHub n'est implicite dans un test local vert.

Sources de configuration : [Angular CSP/Trusted Types](https://angular.dev/best-practices/security), [Spotless Maven](https://github.com/diffplug/spotless/tree/main/plugin-maven), [PostgreSQL 18.6](https://www.postgresql.org/docs/release/18.6/), [Cloudflared releases](https://github.com/cloudflare/cloudflared/releases), [actionlint](https://github.com/rhysd/actionlint/releases).
