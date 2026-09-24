# ADR-0013 — Documentation HTTP et parcours Postman local
Date : 22 septembre 2026. Complète les contrats HTTP existants sans changement métier.

## OpenAPI et sécurité
Springdoc 3.1.0 était déjà présent avec Spring Boot 4.1.1. Aucune dépendance ni
version modifiée. Sortie OPENAPI_3_0 explicite (spec 3.0.1) : elle évite les
avertissements de conversion JsonSchema/type numérique observés dans cette stack
en 3.1 et permet l'import portable dans Postman. La spec locale est validée avec
un validateur OpenAPI, pas seulement parsée comme JSON.

Seuls les GET de documentation /v3/api-docs, /v3/api-docs.yaml,
/v3/api-docs/swagger-config, /swagger-ui.html et /swagger-ui/** sont ouverts.
Les règles JWT, permissions, isolation, suivi privé, webhook et Actuator restent
inchangées. Cette décision précise l'ancienne documentation qui indiquait Swagger
protégé. La spec expose les contrats, aucune donnée métier ni valeur de secret.

OpenApiConfiguration est technique dans shared.infrastructure.openapi, sans
import de types métier. Les schémas DTO sont générés depuis les controllers.
Le seul cas Object (checkout) reçoit explicitement les schémas locaux OrderReceipt
et OrderReplay, sans rendre publics les composants internes des modules.
Le customizer documente les authentifications, headers et erreurs ProblemDetail.
GroupiaProblem est ajouté au stade de personnalisation, afin que Springdoc ne
supprime pas un schéma encore non référencé avant l'ajout des réponses.

Les tests comparent chaque opération métier enregistrée par Spring MVC avec
OpenAPI, résolvent les références de schémas, contrôlent les exigences de sécurité
et vérifient que la collection ne contient que des routes existantes.

## Parcours Postman
Neuf dossiers ordonnés, sélection par variable runPhase : A crée les données et
initie Bictorys ; B vérifie après paiement manuel ; N exécute les négatifs.
Tous les appels partent vers Groupia local. Aucun secret provider dans Postman,
aucun webhook simulé, aucune donnée insérée en SQL.

Le public n'obtient pas de nouveaux champs : orderId et paymentRequestId sont
retrouvés par les lectures organisateur autorisées ; providerTransactionId vient
du détail financier et peut être absent après une réponse Bictorys 202.
Les clés checkout/paiement sont distinctes et conservées, avec le payload initial.
Une nouvelle phase A exige un choix explicite newScenario si un checkout existe.
Les tests négatifs utilisent une campagne DRAFT indépendante.

La phase B renouvelle le JWT, limite le suivi à douze lectures par exécution et
échoue si la confirmation manque. Elle n'appelle pas de réconciliation provider.
L'utilisateur conserve les variables du Runner entre phases. La rétention et
l'export de ces variables restent locaux : ils contiennent les capacités d'accès.

Newman et le validateur de spec ne sont que des outils de vérification ponctuelle,
sans dépendance npm ajoutée au backend ni nécessité pour les tests Maven.
Voir [guide Postman](../postman/README.md) et [validation](../postman/VALIDATION.md).

