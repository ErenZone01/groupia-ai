# Accès client sans compte — fondation V1

## Périmètre et architecture

Cette tranche ajoute des capacités d'accès, sans créer de UserAccount cliente ni changer les règles de paiement, les états commerciaux ou les transitions logistiques. Le suivi historique par trackingToken et son API restent disponibles. Le logout et la rotation des nouveaux liens ne révoquent pas cet ancien mécanisme, qui conserve son propre cycle de vie.

- customeraccess : liens, OTP, sessions, autorisation cliente et limites PostgreSQL.
- ordering::api.CustomerOrderAccess : seule source de lecture des commandes et des téléphones Customer. Aucune utilisation d'un repository ordering par customeraccess.
- identity::api.PhoneNumbers : exposition de la normalisation libphonenumber déjà présente (pas de nouvelle dépendance).
- notification::api : DeliveryGateway, NotificationContentSource et événements sans secrets. Le contenu d'un lien est résolu par une implémentation customeraccess de cette interface ; notification n'importe aucun interne métier.
- grouping et shipping : lectures groupées de progression. La pagination utilise une requête de liste, un count, une lecture groupée des campagnes et une lecture groupée des shipments, sans requête par commande.

Les interfaces publiques sont nommées api. La vérification complète ApplicationModules.of(GroupiaApplication.class).verify() inclut customeraccess, sans exclusion.

## Autorisation et téléphone

ORDER_ACCESS autorise exclusivement l'orderId + organizationId porté par le lien. Il interdit liste globale, renvoi et révocation de liens. L'orderNumber de l'URL est toujours contrôlé par le serveur.

PHONE_VERIFIED autorise les commandes dont Customer.phone_e164 égale exactement le téléphone vérifié. Plusieurs Customer historiques, y compris dans plusieurs boutiques, peuvent correspondre à ce numéro. La jointure orders/customers vérifie aussi organization_id. Aucune commande d'un autre téléphone n'est retournée.

Le normaliseur existant utilise libphonenumber, vérifie la validité du numéro et produit E.164. Les numéros sans indicatif sont interprétés au Sénégal ; les autres pays nécessitent leur indicatif. Aucune suppression naïve des caractères, aucune fusion, aucune contrainte UNIQUE téléphone, aucun backfill des anciennes commandes.

La preuve OTP établit la possession actuelle du téléphone, pas une identité civile. Le modèle implique que le titulaire actuel peut accéder à l'historique de ce numéro ; changement de numéro, recyclage opérateur et récupération après perte de SIM nécessiteront une politique métier ultérieure.

## Persistance : migration additive 012

- order_access_links : UUID aléatoire, organization_id/order_id, generation, created_at, revoked_at, expires_at, last_used_at. FK composite vers orders ; unicité (order_id,generation) et un seul lien non révoqué par commande.
- otp_challenges : UUID, phone_normalized, purpose fixé à RECOVER_ORDERS, code_hash, dates, attempt_count, consumed_at, revoked_at, resend_after. Index téléphone/date et expiration.
- customer_access_sessions : UUID, token_hash unique, scope, link_id OU téléphone, dates. CHECK imposant exactement le contenu autorisé par le scope.
- customer_access_limits : sujet HMAC, début de fenêtre et compteur. Aucun IP brut dans cette table.
- Index non unique customers(phone_e164,organization_id,id) et orders(customer_id,organization_id,placed_at DESC,id).

Aucun ancien changeset n'est modifié ; seul le master inclut 012. Aucune donnée financière historique n'est modifiée. Les changements dans les anciens tests concernent leur TRUNCATE de nettoyage, pour tenir compte des nouvelles FK.

Les dates expirées sont appliquées à chaque lecture même avant purge. Les index d'expiration permettent une purge d'exploitation ultérieure des sessions/challenges expirés et des compteurs anciens. Aucune suppression automatique des liens ou des publications d'événements n'est introduite : ces derniers restent nécessaires à la révocation et à la reprise. Définir une rétention opérationnelle avant déploiement à volume important.

## Magic link et stratégie cryptographique

Format : v1.<UUID v4 du lien>.<signature base64url sans padding>.

Signature : HMAC-SHA256(secret magic, UTF-8("groupia-order-link:" + "v1." + UUID)). Le serveur valide format, signature avec MessageDigest.isEqual, existence et validité DB, puis rattachement à la commande. Le secret contient au moins 256 bits aléatoires. Primitives Java standard uniquement.

La DB ne contient aucun bearer en clair. Un vol de la seule DB ne suffit pas à reconstruire les signatures ; le serveur peut les reconstruire pour un retry de notification. Le lien est néanmoins une capacité transférable : toute personne qui le possède peut lire cette seule commande jusqu'à révocation/expiration. Il n'autorise jamais les autres commandes du téléphone.

Le renvoi par PHONE_VERIFIED crée une nouvelle génération et révoque tous les liens précédents. Les sessions ORDER_ACCESS issues d'un ancien lien sont immédiatement refusées lors de leur prochaine lecture. Les doubles événements de confirmation ne recréent pas un lien révoqué.

Une rotation du secret magic invalide les URL déjà distribuées. Elle n'invalide pas à elle seule les sessions déjà échangées : révoquer aussi les liens/sessions si une invalidation globale est nécessaire. Pas de gestion multi-clés dans cette fondation.

Tant que la commande est active : expires_at peut rester NULL. À chaque échange/lecture, une date terminale connue (picked_up_at, completed_at, cancelled_at ou échéance d'une commande EXPIRED) déclenche la limite d'archive configurable, 90 jours par défaut. Aucune nouvelle transition terminale n'est implémentée.

## Sessions et sécurité HTTP

Valeur : 32 octets SecureRandom, base64url sans padding. Seul SHA-256 de la valeur est stocké. Durée absolue par défaut : 7 jours, configurable entre 60 secondes et 30 jours. Pas de renouvellement silencieux. Un échange ou une vérification OTP révoque la session présentée avant d'en créer une nouvelle.

Cookie production : __Host-groupia_customer ; HttpOnly ; Secure ; SameSite=Lax ; Path=/ ; pas de Domain. En profil local explicite : groupia_customer, Secure=false. Jamais de session dans localStorage, sessionStorage, URL ou réponse JSON.

Chaîne Spring Security dédiée à /api/public/customer-access/** : stateless, autorisation métier par cookie opaque, CSRF obligatoire sur tous les POST, y compris OTP/exchange/logout. GET /csrf fournit un jeton anti-CSRF et pose son cookie HttpOnly (__Host-groupia_csrf en production). Le frontend transmet la valeur reçue dans X-XSRF-TOKEN seulement vers ces endpoints et sur la même origine. Aucun bearer gérante envoyé sur ces routes.

Aucun CORS permissif ajouté. Déployer UI et API sous la même origine via /api. Le profil local utilise le proxy Angular. Les IP sont request.getRemoteAddr() ; X-Forwarded-For n'est pas accepté directement. Configurer explicitement les proxies de confiance au déploiement, sinon le quota IP sera partagé par les clientes derrière un proxy.

Cache-Control no-store via Spring Security ; Referrer-Policy no-referrer sur ces API et meta no-referrer avant les scripts HTML. /s/:token ne montre aucune commande avant validation, remplace immédiatement l'entrée courante par /s, puis remplace vers /mes-commandes/:orderNumber. En cas d'échec réseau, le token reste uniquement en mémoire jusqu'au retry/navigation. Un rechargement de /s revient vers /suivi.

Le serveur d'hébergement doit servir index.html pour /s/* même si le token contient des points. Le serveur de recette production fourni le fait et envoie no-referrer/no-store. En production, masquer /s/* dans les access logs du proxy/CDN, ne pas capturer corps/headers de customer-access dans les outils de tracing, et ne pas activer de logs HTTP détaillés contenant cookies, OTP ou liens. Le serveur d'origine reçoit nécessairement l'URL initiale.

## OTP et limites

Code : six chiffres via SecureRandom. Stockage : HMAC-SHA256(pepper distinct, UTF-8("otp-v1:" + challengeId + ":" + canonicalPhone + ":" + code)). Un simple hash serait vulnérable à une recherche exhaustive hors ligne ; la clé serveur empêche ce calcul avec la seule DB.

| Paramètre groupia.customer-access | Défaut |
| --- | --- |
| session-seconds | 604800 |
| otp-seconds | 300 |
| resend-seconds | 60 |
| max-otp-attempts | 5 |
| phone-requests-per-hour | 5 |
| ip-requests-per-hour | 30 |
| ip-verifications-per-quarter-hour | 100 |
| archive-days | 90 |

La limite vérification IP s'applique aussi aux échanges magic dans une fenêtre distincte. Les renvois de liens utilisent le quota téléphone horaire dans une autre fenêtre.

Limites en fenêtres fixes PostgreSQL, persistées en transaction indépendante, y compris si la demande échoue ensuite. Verrou consultatif par téléphone pour les demandes concurrentes, SELECT FOR UPDATE sur le challenge pour la consommation. Les essais incorrects sont commités, le dernier OTP remplace les précédents, un code consommé ne peut plus servir.

La demande ne cherche pas si des commandes existent et répond toujours, pour un numéro valide autorisé par les quotas :
« Si ce numéro peut recevoir un code, celui-ci a été envoyé. »
Le challenge est créé aussi pour un numéro sans commande ; après vérification sa liste est vide. Les erreurs de format/limite ne révèlent pas l'existence de commandes.

## Endpoints

Préfixe /api/public/customer-access :

| Méthode | Chemin | Accès |
| --- | --- | --- |
| GET | /csrf | Bootstrap CSRF |
| POST | /otp/request | CSRF, téléphone |
| POST | /otp/verify | CSRF, challengeId + code ; pose PHONE_VERIFIED |
| POST | /magic/exchange | CSRF, token ; pose ORDER_ACCESS |
| GET | /session | Session valide ; retourne scope/numéro autorisé/expiration |
| GET | /orders?page=0&size=20 | PHONE_VERIFIED ; taille maximale 50 |
| GET | /orders/{orderNumber} | Session et commande autorisée |
| POST | /orders/{orderNumber}/resend-link | PHONE_VERIFIED et commande confirmée possédée |
| POST | /orders/{orderNumber}/revoke-link | PHONE_VERIFIED et commande possédée |
| POST | /logout | CSRF ; révoque et supprime le cookie |

DTO de liste : numéro, date, état commercial, fulfillmentStatus, montant initial, devise, titre/progression publique du groupage. Détail : réutilise OrderTrackingView et la timeline existante. Aucun PaymentRequest, référence provider, note shipment privée ou contact cliente dans ces réponses.

OpenAPI documente les schémas CustomerSession et CustomerCsrf. Le client Angular est généré, sans duplication manuelle des DTO.

## Événements, livraison et reprise

OrderCommerciallyAccepted existe déjà et n'est publié que lors de la vraie confirmation backend. Le nouveau listener Modulith crée le lien après commit de la transaction métier, vérifie encore l'état confirmé et sérialise les créations par verrou commande. Aucun retour navigateur de paiement ne crée d'accès.

La création publie LinkNotificationRequested(eventId,occurredAt,linkId,type). Le registre persistant Modulith existant rend cet événement récupérable. Aucun téléphone, OTP ou URL bearer dans les publications. notification reconstruit le contenu via NotificationContentSource au moment de la livraison et ignore un lien révoqué/expiré. Une désactivation temporaire de la fonction fait échouer la livraison pour conserver sa possibilité de reprise.

Pas de seconde outbox : les statuts/completion_attempts du registre Modulith couvrent la reprise. PublicationRecovery réessaie les échecs d'au moins une minute, lots de 100, maximum 20 en vol, moins de 5 tentatives ; les échecs épuisés sont signalés et restent investigables. Le message d'exception de l'adapter est remplacé par NOTIFICATION_DELIVERY_UNAVAILABLE sans cause sensible. L'eventId est la clé d'idempotence transmise à DeliveryGateway.

L'OTP est livré après commit du challenge, hors transaction, sans enregistrer le code brut dans une queue. Un échec retourne la même réponse publique et un WARN limité au code OTP_DELIVERY_UNAVAILABLE et au challengeId. La cliente redemande un nouveau code après cooldown ; un redémarrage entre commit et livraison peut donc perdre cet envoi. Les notifications de liens, elles, utilisent la reprise persistante. Ce choix évite de conserver des OTP réversibles pour une durée de vie de cinq minutes.

Types préparés : ORDER_CONFIRMED, SUPPLIER_ORDERED, IN_TRANSIT, ARRIVED. Seul ORDER_CONFIRMED est câblé à une livraison dans cette tranche.

Adapters :
- FakeDelivery dans les tests : boîte mémoire, contrôle d'échecs.
- LocalDeliveryGateway : seulement profil customer-access-local, jamais avec prod/production. Boîte mémoire bornée à 100 entrées / 15 minutes, aucun log du contenu.
- Sans profil local ni futur provider : UnavailableDeliveryGateway échoue explicitement. Aucun faux succès de livraison en production.

## Recette locale sans WhatsApp

1. Générer deux secrets aléatoires indépendants de 32 octets (Base64), les fournir via GROUPIA_CUSTOMER_MAGIC_KEY_BASE64 et GROUPIA_CUSTOMER_OTP_PEPPER_BASE64. Ne pas les commiter. Configurer GROUPIA_PUBLIC_BASE_URL=http://localhost:4200.
2. Démarrer le backend avec le profil customer-access-local et les dépendances Docker habituelles du projet. Ce profil active l'accès et les cookies HTTP locaux. Sans profil local, l'activation explicite est GROUPIA_CUSTOMER_ACCESS_ENABLED=true avec HTTPS/Secure et une livraison réellement configurée.
3. Démarrer Angular (npm start) et utiliser la même origine/nom d'hôte tout au long de la recette.
4. Créer puis payer une commande sandbox via le parcours existant. Attendre CONFIRMED backend.
5. Lire GET http://localhost:8080/api/local/customer-access/inbox avec un JWT gérante valide, depuis la boucle locale. Ce endpoint est absent hors profil local et caché de la documentation publique. Ne pas copier son contenu dans les logs.
6. Ouvrir le lien ORDER_CONFIRMED : redirection propre, une seule commande visible ; /mes-commandes ne liste rien avec ce scope.
7. Fermer la session ou supprimer son cookie, aller à /suivi, saisir le téléphone de commande.
8. Lire l'OTP dans la même boîte locale, vérifier le code, retrouver les commandes du numéro, y compris plusieurs Customer historiques.
9. Renvoyer un lien depuis la commande : l'ancien lien et les sessions issues de celui-ci sont refusés.
10. Vérifier que /public/order et le trackingToken du checkout fonctionnent toujours.

Les secrets restent obligatoires même en local. Le profil local ne doit pas être exposé par tunnel public ; la boîte locale est destinée exclusivement à la recette sur la machine.

## Branchement WhatsApp ultérieur

Implémenter DeliveryGateway dans notification/infrastructure, sélectionner l'adapter par configuration, puis ajouter la configuration serveur des credentials et templates approuvés pour OTP/confirmation. Prévoir opt-in client, timeouts courts, traduction des erreurs en codes sûrs, métriques sans données personnelles et idempotence durable par deliveryId. Si le provider n'offre pas l'idempotence d'envoi, ajouter le stockage de livraison/accusé adapté avant d'affirmer une garantie d'envoi unique.

Le registre Modulith garantit la reprise au moins une fois, pas l'exactement-une-fois externe. Une réponse provider perdue peut imposer une réconciliation de livraison. Une future politique de retry OTP doit rester compatible avec son expiry et l'absence de code brut persistant. Les événements logistiques vers notification restent à raccorder dans une tranche ultérieure, sans appels WhatsApp depuis ordering/shipping.

## Vérification

- Tests PostgreSQL/Testcontainers : scopes, faux liens/signatures, révocation/expiration, archive, normalisation multi-Customer/multi-boutique, pagination, absence de fuite, OTP invalide/expiré/consommé/concurrent, limites, CSRF, flags cookies, reprise notification et logs sûrs.
- ApplicationModules.verify() reste dans la suite normale.
- Angular : transport/CSRF et absence de stockage navigateur, messages d'erreur sûrs.
- Playwright : OTP complet, erreurs/retry, accès magic, URL nettoyée, limites de scope, session perdue, autre commande, dark/360px/clavier et ouverture directe depuis le build production.
- Commandes : Backend/mvnw.cmd test ; Backend/mvnw.cmd verify ; Frontend/npm run api:generate ; npm test ; npm run build ; npm run test:e2e.

Aucune dépendance ajoutée. Les résultats chiffrés de la dernière exécution sont rapportés dans le compte rendu de tâche.

### Commandes locales (PowerShell)

Dans Backend, avec la configuration sandbox habituelle déjà disponible :

```powershell
docker compose up -d
$customerMagicBytes = New-Object byte[] 32
$customerOtpBytes = New-Object byte[] 32
$customerRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$customerRng.GetBytes($customerMagicBytes)
$customerRng.GetBytes($customerOtpBytes)
$env:GROUPIA_CUSTOMER_MAGIC_KEY_BASE64 = [Convert]::ToBase64String($customerMagicBytes)
$env:GROUPIA_CUSTOMER_OTP_PEPPER_BASE64 = [Convert]::ToBase64String($customerOtpBytes)
$env:GROUPIA_PUBLIC_BASE_URL = 'http://localhost:4200'
$customerRng.Dispose()
.\mvnw.cmd '-Dspring-boot.run.profiles=customer-access-local' spring-boot:run
```

Ces variables ne sont pas imprimées. Pour conserver la validité des liens entre redémarrages, enregistrer les deux valeurs dans le gestionnaire de secrets local habituel (fichier local ignoré ou environnement), sans les régénérer à chaque lancement. Les régénérer volontairement change la clé et invalide les anciens liens non échangés.

Dans Frontend, un autre terminal : `npm start`. La lecture de la boîte locale se fait dans Postman ou un client HTTP avec `Authorization: Bearer <JWT gérante>`, jamais dans une URL.

### Résultats exécutés le 29 septembre 2026

| Commande | Résultat |
| --- | --- |
| Backend : .\\mvnw.cmd test | 474 tests, 0 échec, 0 erreur, 0 ignoré |
| Backend : .\\mvnw.cmd verify | 474 tests verts ; JAR Spring Boot produit |
| Spring Modulith | Vérification complète verte, incluse dans les deux commandes Maven |
| Frontend : npm run api:generate | Client régénéré depuis le document produit par /v3/api-docs dans le test Spring ; servi sur une URL locale temporaire pour le générateur |
| Frontend : npm test | 186 tests verts |
| Frontend : npm run build | Build production vert |
| Frontend : npm run test:e2e | 89 tests verts, API simulée côté navigateur |

Ajouts : 16 tests d'intégration backend, 15 tests unitaires Angular et 10 tests navigateur. L'ancien test de /suivi vérifie désormais la récupération et conserve un contrôle distinct de /public/order. La recette WhatsApp réelle n'est pas exécutée : aucun adapter Meta n'est inclus.

Rapports locaux : Backend/target/customer-access-full-test.log, Backend/target/customer-access-verify.log, Frontend/artifacts/customer-access-validation/ et Frontend/playwright-report/.
