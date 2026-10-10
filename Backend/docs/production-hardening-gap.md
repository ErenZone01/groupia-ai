# Analyse initiale — Production Hardening V1

Base inspectée : `873605a27d1b34e5b0dd4a373db5dcfe3270da53`, branche
`production-hardening-v1`. Dépôt propre ; main et origin/main sur cette base.
Avant modification : les trois ModularityTests passent, dont ApplicationModules.verify().

| Surface | Constat avant modification | Action retenue |
| --- | --- | --- |
| Architecture | 15 modules fermés, dépendances ::api, événements persistants | Conserver, tests normaux obligatoires |
| Auth | BCrypt 12 et JWT validés ; login/register sans quotas partagés ; inscription 409 révèle un compte | Compteurs PostgreSQL identity indépendants, HMAC, budgets réseau + sujet, inscription publique 202 uniforme |
| Réseau | TrackingAttempts ignore les headers Forwarded mais état local | Garder une défense locale bornée, rendre ses seuils configurables ; proxy/edge obligatoire |
| HTTP | Headers Spring de base, chaîne customeraccess séparée avec CSRF ; pas de CSP production | Politique commune API et serveur frontend avec nonce + Trusted Types ; préserver les deux authentifications |
| Diagnostics | Swagger public, absence de profil prod explicite | Désactiver Swagger/docs en prod, limiter Actuator et isoler sa lecture de métriques |
| Secrets | Ancien .env dans l'historique, aucun ignore racine ; premier scan HEAD de motifs sans résultat | Ignore racine, Gitleaks redacted, checklist de rotations externes ; aucune réécriture d'historique |
| Artefacts | build/generated-spring-modulith/javadoc.json suivi ; Maven régénère target/generated-spring-modulith/javadoc.json | Retirer l'ancien artefact suivi, conserver le processeur Maven |
| Frontend | strict/strictTemplates absents ; pas de lint | Évaluer les erreurs réelles, activer proprement ce qui est faisable ; ESLint Angular |
| Qualité Java | Aucun contrôle automatique de format | Spotless progressif depuis la base, sans formatage global arbitraire ; évaluer SpotBugs séparément |
| CI / supply chain | Aucune CI, aucun Dependabot ; images latest | CI least privilege, actions SHA, Java 21/Node 24, tests Docker, scan secrets/dépendances, images versionnées |
| Migrations | 001–022 déjà déployées, sans garde byte-for-byte | Manifeste SHA-256 et comparaison à la base ; 023 pour les seuls compteurs auth |
| Observabilité | Logs applicatifs généralement limités à IDs/codes ; pas de métriques métier dédiées | Métriques sans dimensions personnelles, erreur SQL détaillée coupée en prod |
| Performance | Index expiration/retry/review présents ; historiques parfois non paginés ; expiration par lots mais boucle sans plafond global | Documenter le backlog, évaluer un plafond séparé, préparer charge isolée sans réécriture des queries |

Contrats et décisions relus : AGENTS.md, architecture/module-dependencies,
ADR identité, paiements, customeraccess, settlement et abonnement ; filtres,
normalisation téléphone, services/repositories auth, reprises/expiration,
tests PostgreSQL et migration, génération OpenAPI et serveur E2E production.

Le changement 202 à l'inscription supprime l'oracle public de présence d'un
numéro. Il ne connecte pas un utilisateur et ne remplace pas son mot de passe.
Le client Angular doit continuer par une connexion explicite avec les identifiants
saisis. Les fixtures/tests qui lisaient l'ID de création via HTTP devront lire
leur propre base isolée. Aucun secret externe ne sera renouvelé par cette branche.
