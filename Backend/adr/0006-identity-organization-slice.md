# ADR-0006 — Première tranche identity / organization

- Statut : accepté pour l'implémentation de la tranche demandée
- Date : 2026-09-18
- Portée : inscription, connexion et accès aux organisations
- Complète : ADR-0003 et graphe des modules
- Contrat HTTP : [identity-organization-api.md](../docs/identity-organization-api.md)

## Exigences fournies

Connexion par téléphone/mot de passe, email facultatif, normalisation E.164, BCrypt uniquement, JWT configuré hors code. Création d'une organisation et de son premier OWNER ACTIVE dans une transaction. Consultation limitée aux adhésions actives et permissions requises. PostgreSQL/Liquibase, aucune relation JPA intermodule.

Les fonctions OTP, récupération du mot de passe, MFA, social login, refresh tokens, gestion de membres et administration restent hors de cette tranche.

## Décisions d'implémentation

1. libphonenumber 9.0.39 valide les numéros et les convertit en E.164. SN est la région par défaut pour les numéros nationaux ; les numéros internationaux valides sont acceptés. Extensions et numéros contenant des lettres sont rejetés. Le numéro est unique après normalisation. Sa possession n'est pas vérifiée par OTP dans cette tranche.
2. BCrypt avec facteur de coût 12. Mot de passe d'inscription : au moins 15 points de code, au plus 72 octets UTF-8, non blanc, sans caractère NUL. Aucun trim ou changement du mot de passe ; aucune règle artificielle de composition. La limite en octets évite la troncature BCrypt.
3. Comptes ACTIVE/DISABLED ; organisations ACTIVE/ARCHIVED ; membres ACTIVE/REVOKED. Aucun endpoint de changement de statut n'est ajouté.
4. JWT HS256 vérifié par Spring Security Resource Server : clé Base64 externe d'au moins 256 bits, issuer, audience, subject UUID canonique, expiration obligatoire, tolérance d'horloge 30 secondes. Durée par défaut 15 minutes, configurable entre 1 et 60 minutes. Aucune clé par défaut ou clé persistée dans le dépôt.
5. JWT minimal : sub, iss, aud, iat, exp, jti ; aucun rôle d'organisation, téléphone ou email. Une vérification du compte encore actif précède chaque opération organization via identity::api.CurrentUser. Les permissions ne proviennent pas du JWT.
6. Authentification sans session ni cookie : Bearer dans Authorization, pas de formulaire ni Basic ; CSRF désactivé pour ce protocole. Les endpoints autres que les deux POST auth exigent un JWT. Les erreurs du filtre de sécurité utilisent la même convention que les erreurs HTTP métier.
7. Nom d'organisation non blanc, au plus 160 caractères, espaces externes supprimés ; XOF/SN/Africa-Dakar fixés par le serveur (timezone exacte Africa/Dakar). Le corps de création ne choisit ni propriétaire ni rôle. Le créateur est obtenu depuis le contexte authentifié.
8. OrganizationAuthorization centralise utilisateur actif, membership ACTIVE et permission explicite. VIEW_ORGANIZATION est accordée aux quatre rôles ; MANAGE_ORGANIZATION et MANAGE_MEMBERS à OWNER. Aucune nouvelle fonction de gestion n'est exposée.
9. Les non-membres/revoked reçoivent 403 ORGANIZATION_ACCESS_DENIED, y compris pour un UUID inconnu : aucun test d'existence avant l'autorisation. Un membre autorisé qui consulte une organisation archivée reçoit 404 ORGANIZATION_NOT_FOUND. La liste n'inclut pas les organisations archivées.
10. Liste paginée par page (0 par défaut) et size (20 par défaut, 100 maximum), ordonnée par date décroissante puis UUID ; réponse tableau.
11. Les corps JSON comportant des propriétés inconnues sont rejetés, notamment toute tentative de choisir userAccountId, role ou countryCode.
12. Un format ProblemDetail enrichi d'un code stable est commun aux deux modules. shared accueille exclusivement ce support technique et une horloge injectable, sans données métier ni dépendance métier. identity → shared::api et organization → shared::api complètent le graphe. Ce contenu concret remplace la réserve « shared vide » ; il ne sert pas à contourner un cycle.
13. Repositories métier limités aux opérations nécessaires ; adaptateurs JPA internes. Organization et OrganizationMember sont enregistrés/flushés avant publication de OrganizationCreated, dans une seule transaction.
14. UserAccountCreated et OrganizationCreated sont publiés avec eventId, occurredAt et identifiants minimaux. Aucun consommateur intermodule, notification ni audit métier n'est créé dans cette tranche. Le registre Modulith existant prendra en charge les futurs consommateurs transactionnels ; on ne prétend pas conserver une publication pour un consommateur qui n'existe pas.
15. @Version couvre UserAccount, Organization et OrganizationMember. Contraintes de téléphone/membership et FK SQL sans cascade complètent les transactions. Hibernate valide le schéma ; Open EntityManager in View est désactivé.

## Conséquences et limites

Le bootstrap du premier OWNER ne demande pas une adhésion qui n'existe pas encore : un compte actif peut créer une organisation et obtient son premier membership dans la même transaction. Les accès à une organisation existante exigent ensuite la permission.

Aucun parcours de retrait/changement de rôle du dernier OWNER n'est exposé. Une future gestion des membres devra coordonner l'invariant du dernier propriétaire ; ce slice ne fournit pas de CRUD permettant de le violer.

La désactivation d'un compte et la révocation d'une adhésion sont relues lors de l'accès organization, même avec un JWT non expiré. La rotation de clé ou l'expiration impose une nouvelle connexion ; aucun refresh token ou mécanisme de logout global n'est construit.

Le support HTTP de shared est technique et n'expose ni entités ni erreurs SQL au client. Le graphe reste acyclique et est vérifié sans filtre. Les futures permissions de catalogue/groupage ne sont pas inventées.

## Validation

Tests PostgreSQL via Testcontainers : inscriptions concurrentes, normalisation et unicité, hash BCrypt, JWT réel signé/vérifié et claims invalides, accès sans token, comptes désactivés, adhésions révoquées, séparation des organisations, permissions par rôle, contraintes SQL et rollback réel si l'insertion du OWNER échoue. Le test de modularité reste inchangé.

Une clé de test aléatoire est injectée uniquement dans le contexte Testcontainers. Les tests n'ont ni secret de production, ni accès à la base locale Compose.

Sources techniques : [Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [libphonenumber](https://github.com/google/libphonenumber).
