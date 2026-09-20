# API — identity et organization, première tranche

État : implémentation limitée à inscription, connexion, création et consultation des organisations. Les autres modules métier restent au stade documentaire.

## Configuration et lancement

Java 21, Docker et le wrapper Maven du projet sont nécessaires. Définir GROUPIA_JWT_SECRET_BASE64 avec une clé aléatoire d'au moins 32 octets encodée en Base64, fournie par l'environnement/gestionnaire de secrets. Aucune valeur de repli n'existe : le démarrage échoue si la configuration manque ou est invalide.

Options : GROUPIA_JWT_ISSUER (groupia), GROUPIA_JWT_AUDIENCE (groupia-api), GROUPIA_JWT_ACCESS_TOKEN_TTL (15m, entre 1m et 60m). Ne jamais consigner la clé ni les tokens dans les journaux.

Exemple de préparation locale dans PowerShell, sans afficher la clé :

```powershell
$jwtBytes = [byte[]]::new(32)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($jwtBytes)
$env:GROUPIA_JWT_SECRET_BASE64 = [Convert]::ToBase64String($jwtBytes)
docker compose up -d postgres
.\mvnw.cmd spring-boot:run
```

Le support Compose fournit la connexion PostgreSQL. Conserver la même clé entre redémarrages si les tokens émis doivent rester valides. Les tests utilisent leur propre PostgreSQL et une clé éphémère générée au lancement ; ils ne demandent pas cette variable.

## Endpoints

| Méthode | URL | Authentification | Succès |
| --- | --- | --- | --- |
| POST | /api/auth/register | publique | 201 UserAccountView |
| POST | /api/auth/login | publique | 200 accessToken/tokenType/expiresIn |
| POST | /api/organizations | Bearer JWT + compte actif | 201 OrganizationView, Location |
| GET | /api/organizations?page=0&size=20 | Bearer JWT + compte actif | 200 tableau des organisations autorisées |
| GET | /api/organizations/{organizationId} | Bearer JWT + permission VIEW_ORGANIZATION | 200 OrganizationView |

Les objets inconnus dans les corps JSON sont rejetés. Il n'existe aucun endpoint de catalogue, commande, paiement, transport, gestion de membres ou administration.

## Inscription et connexion

Corps d'inscription :

```json
{
  "phoneNumber": "77 123 45 67",
  "password": "Une phrase longue et personnelle",
  "email": "organisateur@example.test"
}
```

Email est facultatif : omettre la propriété ou fournir null, pas une chaîne vide. Si présent, email doit être valide et au plus 254 caractères. Téléphone : au plus 40 caractères en entrée, normalisé côté serveur, région nationale SN ; international avec préfixe accepté. Aucun OTP dans ce slice.

Le mot de passe comporte au moins 15 caractères Unicode et au plus 72 octets UTF-8 ; un mot de passe multioctet peut donc atteindre la limite avant 72 caractères. Il est hashé en BCrypt coût 12, jamais retourné.

Réponse d'inscription : id UUID, phoneNumber E.164, email éventuel, status ACTIVE et createdAt ISO-8601. Aucun token automatiquement émis ; utiliser login.

Corps de connexion : phoneNumber et password uniquement. Le téléphone peut avoir une mise en forme différente mais normalisable au même numéro. Mauvais mot de passe, compte inconnu ou désactivé : même réponse INVALID_CREDENTIALS.

Réponse de connexion :

```json
{
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

Les réponses auth portent Cache-Control: no-store. Transmettre le token dans Authorization: Bearer <JWT>, jamais comme identifiant d'organisation. JWT HS256 : signature, issuer, audience, expiration et subject UUID sont validés. Aucun refresh token ; se reconnecter après expiration.

## Organisations

Création : corps contenant uniquement name non blanc, de 160 caractères au maximum. Le serveur supprime les espaces externes et attribue le créateur depuis l'authentification.

Réponse : id, name, status ACTIVE, defaultCurrency XOF, countryCode SN, timezone Africa/Dakar, createdAt et updatedAt. Aucun champ de membership n'est pilotable par le navigateur.

Organization et son OrganizationMember OWNER/ACTIVE sont créés atomiquement. Un échec d'insertion du propriétaire annule aussi l'organisation.

Liste : page commence à zéro, borne maximum 1 000 000 ; size entre 1 et 100. Tri createdAt décroissant puis id croissant. Une page peut être vide. Le filtrage utilise l'utilisateur courant, une adhésion ACTIVE, un rôle autorisé à VIEW_ORGANIZATION et une organisation ACTIVE.

Lecture : utilisateur encore actif, membership dans l'organisation, statut ACTIVE et permission sont contrôlés centralement. Les quatre rôles lisent l'organisation ; seules les permissions de cette tranche sont implémentées. Les futurs modules étendront leurs propres permissions sans tests de rôles dans les controllers.

## Erreurs

Content-Type applicatif ProblemDetail (application/problem+json), avec status, title, detail, instance selon le contexte, et code stable. Les erreurs de validation peuvent ajouter errors, une liste de field/message ; jamais la valeur rejetée d'un mot de passe. Ne pas dépendre du texte traduit pour piloter le frontend.

| HTTP | code | Situation |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | champs invalides |
| 400 | INVALID_REQUEST | JSON incorrect/propriété inconnue, UUID ou paramètres invalides |
| 400 | INVALID_PHONE_NUMBER | numéro invalide à l'inscription |
| 400 | INVALID_PASSWORD | politique de mot de passe non respectée |
| 401 | INVALID_CREDENTIALS | connexion refusée |
| 401 | AUTHENTICATION_REQUIRED | token absent/invalide, compte inactif sur organization |
| 403 | ORGANIZATION_ACCESS_DENIED | pas de membership actif ou permission absente |
| 403 | ACCESS_DENIED | refus par le filtre de sécurité |
| 404 | ORGANIZATION_NOT_FOUND | membre autorisé mais organisation indisponible/archivée |
| 404 | RESOURCE_NOT_FOUND | route absente après authentification |
| 409 | PHONE_ALREADY_REGISTERED | téléphone normalisé déjà inscrit |
| 405 / 415 | METHOD_NOT_ALLOWED / UNSUPPORTED_MEDIA_TYPE | protocole HTTP invalide |
| 500 | INTERNAL_ERROR | échec inattendu, sans détail technique ni stack trace |

Pour un non-membre, UUID inconnu et organisation existante inaccessible répondent tous deux 403 ; aucun test d'existence préalable ne révèle la différence. Les autres routes restent protégées par défaut, notamment la documentation OpenAPI et Actuator.

## Tests et décisions

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
```

Les tests utilisent PostgreSQL/Testcontainers, aucune base H2 et aucun skip conditionnel faute de Docker. Le test d'atomicité force une contrainte PostgreSQL à rejeter le OWNER et vérifie le rollback de l'organisation ; les tests de concurrence vérifient un unique compte et un conflit 409.

Les événements UserAccountCreated et OrganizationCreated sont émis avec les identifiants minimaux. Aucun consommateur métier intermodule n'est encore ajouté ; aucune promesse d'envoi de notification ou d'audit persistant n'est faite.

Les choix de sécurité, permissions, pagination, erreurs et contenu technique de shared sont expliqués dans [ADR-0006](../adr/0006-identity-organization-slice.md). La [liste des fichiers de cette tranche](identity-organization-files.md) distingue code, tests, migrations et documentation.

## Résultat de vérification du 18 septembre 2026

Les deux commandes demandées ont été exécutées avec succès :

- `.\mvnw.cmd test` : 38 tests, 0 échec, 0 erreur, 0 ignoré.
- `.\mvnw.cmd verify` : 38 tests, 0 échec, 0 erreur, 0 ignoré ; JAR Spring Boot exécutable produit.

Répartition : 34 cas de la tranche identity/organization sur PostgreSQL/Testcontainers, 3 tests d'architecture existants et 1 test de démarrage du contexte existant. Le test `ApplicationModules.of(GroupiaApplication.class).verify()` reste inchangé et s'exécute normalement, sans exclusion de module.

Les journaux locaux sont dans `target/final-slice-test.log` et `target/final-slice-verify.log` (non versionnés). Les erreurs SQL provoquées par les tests de doublon, de FK et de rollback sont attendues.
