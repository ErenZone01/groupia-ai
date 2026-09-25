# Fichiers de la première tranche identity/organization

Inventaire exact des fichiers créés/modifiés par cette tranche au 18 septembre 2026. Le dépôt était propre au début de la tranche. Aucun fichier de catalogue, groupage, ordering, payment, shipping, media, notification ou audit métier n'est modifié.

## Code identity

- Créé : [src/main/java/sn/groupia/identity/api/CurrentUser.java](../src/main/java/sn/groupia/identity/api/CurrentUser.java).
- Créé : [src/main/java/sn/groupia/identity/api/dto/UserAccountView.java](../src/main/java/sn/groupia/identity/api/dto/UserAccountView.java).
- Créé : [src/main/java/sn/groupia/identity/api/event/UserAccountCreated.java](../src/main/java/sn/groupia/identity/api/event/UserAccountCreated.java).
- Créé : [src/main/java/sn/groupia/identity/application/service/AccessTokens.java](../src/main/java/sn/groupia/identity/application/service/AccessTokens.java).
- Créé : [src/main/java/sn/groupia/identity/application/service/AuthenticatedUser.java](../src/main/java/sn/groupia/identity/application/service/AuthenticatedUser.java).
- Créé : [src/main/java/sn/groupia/identity/application/service/AuthenticationService.java](../src/main/java/sn/groupia/identity/application/service/AuthenticationService.java).
- Créé : [src/main/java/sn/groupia/identity/application/service/PasswordPolicy.java](../src/main/java/sn/groupia/identity/application/service/PasswordPolicy.java).
- Créé : [src/main/java/sn/groupia/identity/application/service/PhoneNumberNormalizer.java](../src/main/java/sn/groupia/identity/application/service/PhoneNumberNormalizer.java).
- Créé : [src/main/java/sn/groupia/identity/domain/model/UserAccount.java](../src/main/java/sn/groupia/identity/domain/model/UserAccount.java).
- Créé : [src/main/java/sn/groupia/identity/domain/model/UserAccountStatus.java](../src/main/java/sn/groupia/identity/domain/model/UserAccountStatus.java).
- Créé : [src/main/java/sn/groupia/identity/domain/repository/UserAccounts.java](../src/main/java/sn/groupia/identity/domain/repository/UserAccounts.java).
- Créé : [src/main/java/sn/groupia/identity/infrastructure/persistence/JpaUserAccountRepository.java](../src/main/java/sn/groupia/identity/infrastructure/persistence/JpaUserAccountRepository.java).
- Créé : [src/main/java/sn/groupia/identity/infrastructure/persistence/UserAccountPersistence.java](../src/main/java/sn/groupia/identity/infrastructure/persistence/UserAccountPersistence.java).
- Créé : [src/main/java/sn/groupia/identity/infrastructure/security/JwtAccessTokens.java](../src/main/java/sn/groupia/identity/infrastructure/security/JwtAccessTokens.java).
- Créé : [src/main/java/sn/groupia/identity/infrastructure/security/JwtSettings.java](../src/main/java/sn/groupia/identity/infrastructure/security/JwtSettings.java).
- Créé : [src/main/java/sn/groupia/identity/infrastructure/security/SecurityConfiguration.java](../src/main/java/sn/groupia/identity/infrastructure/security/SecurityConfiguration.java).
- Créé : [src/main/java/sn/groupia/identity/infrastructure/web/AuthController.java](../src/main/java/sn/groupia/identity/infrastructure/web/AuthController.java).
- Modifié : [src/main/java/sn/groupia/identity/package-info.java](../src/main/java/sn/groupia/identity/package-info.java).

## Code organization

- Créé : [src/main/java/sn/groupia/organization/api/dto/OrganizationView.java](../src/main/java/sn/groupia/organization/api/dto/OrganizationView.java).
- Créé : [src/main/java/sn/groupia/organization/api/event/OrganizationCreated.java](../src/main/java/sn/groupia/organization/api/event/OrganizationCreated.java).
- Créé : [src/main/java/sn/groupia/organization/api/OrganizationAccess.java](../src/main/java/sn/groupia/organization/api/OrganizationAccess.java).
- Créé : [src/main/java/sn/groupia/organization/api/OrganizationPermission.java](../src/main/java/sn/groupia/organization/api/OrganizationPermission.java).
- Créé : [src/main/java/sn/groupia/organization/application/service/OrganizationAuthorization.java](../src/main/java/sn/groupia/organization/application/service/OrganizationAuthorization.java).
- Créé : [src/main/java/sn/groupia/organization/application/service/OrganizationService.java](../src/main/java/sn/groupia/organization/application/service/OrganizationService.java).
- Créé : [src/main/java/sn/groupia/organization/domain/model/MembershipStatus.java](../src/main/java/sn/groupia/organization/domain/model/MembershipStatus.java).
- Créé : [src/main/java/sn/groupia/organization/domain/model/Organization.java](../src/main/java/sn/groupia/organization/domain/model/Organization.java).
- Créé : [src/main/java/sn/groupia/organization/domain/model/OrganizationMember.java](../src/main/java/sn/groupia/organization/domain/model/OrganizationMember.java).
- Créé : [src/main/java/sn/groupia/organization/domain/model/OrganizationRole.java](../src/main/java/sn/groupia/organization/domain/model/OrganizationRole.java).
- Créé : [src/main/java/sn/groupia/organization/domain/model/OrganizationStatus.java](../src/main/java/sn/groupia/organization/domain/model/OrganizationStatus.java).
- Créé : [src/main/java/sn/groupia/organization/domain/repository/OrganizationMembers.java](../src/main/java/sn/groupia/organization/domain/repository/OrganizationMembers.java).
- Créé : [src/main/java/sn/groupia/organization/domain/repository/Organizations.java](../src/main/java/sn/groupia/organization/domain/repository/Organizations.java).
- Créé : [src/main/java/sn/groupia/organization/infrastructure/persistence/JpaOrganizationMemberRepository.java](../src/main/java/sn/groupia/organization/infrastructure/persistence/JpaOrganizationMemberRepository.java).
- Créé : [src/main/java/sn/groupia/organization/infrastructure/persistence/JpaOrganizationRepository.java](../src/main/java/sn/groupia/organization/infrastructure/persistence/JpaOrganizationRepository.java).
- Créé : [src/main/java/sn/groupia/organization/infrastructure/persistence/OrganizationPersistence.java](../src/main/java/sn/groupia/organization/infrastructure/persistence/OrganizationPersistence.java).
- Créé : [src/main/java/sn/groupia/organization/infrastructure/web/OrganizationController.java](../src/main/java/sn/groupia/organization/infrastructure/web/OrganizationController.java).
- Modifié : [src/main/java/sn/groupia/organization/package-info.java](../src/main/java/sn/groupia/organization/package-info.java).

## Support technique shared

- Créé : [src/main/java/sn/groupia/shared/api/ApiException.java](../src/main/java/sn/groupia/shared/api/ApiException.java).
- Créé : [src/main/java/sn/groupia/shared/api/package-info.java](../src/main/java/sn/groupia/shared/api/package-info.java).
- Créé : [src/main/java/sn/groupia/shared/infrastructure/TimeConfiguration.java](../src/main/java/sn/groupia/shared/infrastructure/TimeConfiguration.java).
- Créé : [src/main/java/sn/groupia/shared/infrastructure/web/ApiExceptionHandler.java](../src/main/java/sn/groupia/shared/infrastructure/web/ApiExceptionHandler.java).
- Modifié : [src/main/java/sn/groupia/shared/package-info.java](../src/main/java/sn/groupia/shared/package-info.java).

## Migrations et configuration

- Modifié : [pom.xml](../pom.xml).
- Modifié : [src/main/resources/application.properties](../src/main/resources/application.properties).
- Créé : [src/main/resources/db/changelog/002-identity.sql](../src/main/resources/db/changelog/002-identity.sql).
- Créé : [src/main/resources/db/changelog/003-organizations.sql](../src/main/resources/db/changelog/003-organizations.sql).
- Modifié : [src/main/resources/db/changelog/db.changelog-master.yaml](../src/main/resources/db/changelog/db.changelog-master.yaml).

## Tests

- Créé : [src/test/java/sn/groupia/IdentityOrganizationIntegrationTests.java](../src/test/java/sn/groupia/IdentityOrganizationIntegrationTests.java).
- Modifié : [src/test/java/sn/groupia/TestcontainersConfiguration.java](../src/test/java/sn/groupia/TestcontainersConfiguration.java).

## Documentation et décisions

- Créé : [adr/0006-identity-organization-slice.md](../adr/0006-identity-organization-slice.md).
- Modifié : [adr/README.md](../adr/README.md).
- Modifié : [docs/architecture.md](../docs/architecture.md).
- Modifié : [docs/business-rules.md](../docs/business-rules.md).
- Modifié : [docs/domain-model.md](../docs/domain-model.md).
- Créé : [docs/identity-organization-api.md](../docs/identity-organization-api.md).
- Créé : [docs/identity-organization-files.md](../docs/identity-organization-files.md).
- Modifié : [docs/module-dependencies.md](../docs/module-dependencies.md).

ModularityTests.java reste inchangé : aucune exclusion, ouverture de module ou désactivation de vérification.
