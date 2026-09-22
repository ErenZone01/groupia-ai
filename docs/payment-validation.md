# Validation du cinquième vertical slice

Périmètre : noyau financier simulé, aucun provider réel. Java 21, Spring Boot 4.1.1, Spring Modulith 2.1.1, PostgreSQL/Testcontainers ; ni H2 ni dépendance Maven supplémentaire.

## Matrice de couverture

| Suite | Tests |
| --- | ---: |
| CatalogIntegrationTests | 35 |
| GroupiaApplicationTests | 1 |
| GroupingAdmissionTests | 4 |
| GroupingIntegrationTests | 47 |
| IdentityOrganizationIntegrationTests | 34 |
| ModularityTests | 3 |
| CheckoutPolicyTests | 5 |
| OrderingIntegrationTests | 63 |
| PaymentIntegrationTests | 53 |
| ShippingEstimatorTests | 12 |
| Total | 257 |

Les 53 cas financiers couvrent notamment :

- Checkout atomique, rollback complet et rejeu, ventilation/échéance identiques, référence métier immuable.
- Autorisation par jeton/numéro ; lecture financière par permission et organisation.
- Initiation idempotente, nouvelles clés après FAILED/CANCELLED/EXPIRED certain, timeout et reprise sans second débit.
- Assertion explicite d'absence de transaction dans tous les appels du provider simulé ; expiration et lecture possibles pendant un appel bloqué.
- Authenticité invalide, doublons avec/sans eventId, référence inconnue, incohérences montant/devise/transaction/cible/provider.
- Vérification du succès immédiat et des webhooks ; retard, désordre et absence de régression de SUCCEEDED.
- Première affectation et confirmation ; succès tardif et double succès conservés sans second règlement.
- Consumer idempotent ; commandes CANCELLED/EXPIRED/COMPLETED jamais réactivées ; panne du consumer puis resoumission sans perdre le règlement.
- Panne d'écriture du registre : rollback financier mais réception durable, reprise ultérieure ; plafond de cinq essais pour erreurs provider et erreurs transactionnelles.
- Expiration idempotente, workers concurrents et pages suivantes traitées malgré une confirmation bloquée.
- Appels concurrents avec même clé ou clés différentes, doublons webhook simultanés et deux succès réels concurrents.
- Contraintes monétaires, d'appartenance, d'identité prestataire et de conservation des références PostgreSQL.

Le test ApplicationModules.of(GroupiaApplication.class).verify() demeure dans la suite normale, inchangé et sans exclusions. Les anciennes suites conservent leurs assertions ; leurs fixtures incluent les nouvelles tables pour respecter les FK. Le simulateur HMAC, ses scénarios et son horloge mutable se trouvent uniquement dans src/test.

Voir [ADR-0010](../adr/0010-financial-core-and-settlement.md), [API](payment-api.md) et [reprise](payment-operations.md). Cette validation ne prétend pas couvrir un contrat de provider réel, un remboursement, un transport réel ou une configuration externe de supervision.

## Commandes exécutées le 20 septembre 2026

- `.\mvnw.cmd test` : BUILD SUCCESS, 257 tests, zéro échec/erreur/ignoré ; durée 2 min 19 s.
- `.\mvnw.cmd verify` : BUILD SUCCESS, mêmes 257 tests ; durée 2 min 25 s ; JAR exécutable produit.
- `git diff --check` : aucune erreur.

Logs locaux : target/payment-test.log et target/payment-verify.log. Artefact local : target/groupia-ai-0.0.1-SNAPSHOT.jar. Les fichiers target sont générés, hors sources versionnées. Aucune intégration provider réelle n'a été exécutée ; elle est explicitement hors périmètre.

## Extension Bictorys sandbox — 22 septembre 2026

Résultats réellement exécutés :

- `.\mvnw.cmd test` : BUILD SUCCESS, 324 tests, 0 échec, 0 erreur, 0 ignoré ; 3 min 19 s.
- `.\mvnw.cmd verify` : BUILD SUCCESS, mêmes 324 tests ; 4 min 48 s ; JAR exécutable produit.
- `git diff --check` : aucune erreur.

Les 260 cas antérieurs (dont les 3 tests d'upgrade de migration ajoutés après la
tranche financière initiale) sont conservés. Ajouts : 48 cas de contrat Bictorys,
16 cas d'intégration Bictorys/PostgreSQL. Les 3 tests d'architecture restent verts,
sans modification ni exclusion de ModularityTests.

Couverture ajoutée : configuration désactivée/active, sandbox obligatoire,
requête hosted checkout, contrats 201/202, charge distincte de transaction,
liens de checkout privés, merchantReference et paymentReference, statuts,
secret absent/incorrect/correct, racine/data, montant/devise/référence incohérents,
doublon webhook, succès tardif/double, reversal avant/après succès, timeout,
4xx/5xx/non-JSON/coupure, statut seul complété par détails, preuves incomplètes,
course ACK/webhook et conservation de l'alerte reversal après panne réseau.
Un décorateur de test vérifie explicitement l'absence de transaction dans les
quatre opérations de l'adaptateur réel. Le serveur HTTP est toujours local.

Logs locaux : target/bictorys-test.log et target/bictorys-verify.log.
Aucun paiement distant effectué ; la configuration dashboard/tunnel, la livraison
externe du webhook et le contrat réellement servi au compte sandbox restent à
valider par le [parcours manuel](bictorys-sandbox.md). Aucune dépendance Maven ajoutée.
