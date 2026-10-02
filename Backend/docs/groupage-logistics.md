# Logistique V1 : dossier externe et timeline flexible

## Provenance et limites

V1 : **prestataire externe → information → gérante → Groupia**.
Groupia enregistre une déclaration, sans prétendre avoir mesuré, reçu ou vérifié directement les marchandises.
V2 future : **prestataire inscrit → information directe → Groupia**. Une identité de prestataire pourra être rattachée au dossier sans remplacer les snapshots historiques. Aucun compte ni forwarderAccountId n'est créé en V1.

**Une donnée externe inconnue != zéro != maintenant.** Le dossier peut être vide. Le nom/contact du prestataire, la référence, les notes, les dates réelles et l'ETA sont facultatifs. L'appel explicite à l'action de départ/arrivée constitue la confirmation du fait ; aucune case technique supplémentaire n'est nécessaire au contrat HTTP existant.

## Modèle et frontière privée

shipping porte un Shipment unique par boutique/groupage, créé à la première saisie dès SUPPLIER_ORDERED (pas de création par simple GET).
TransportMode vient des conditions du groupage. Les textes providerName (160), providerContact (300), trackingReference (160), note (2000) sont des snapshots locaux au dossier.
departureConfirmedAt et arrivalConfirmedAt sont les instants serveur ; departedAt et arrivedAt sont les instants réellement communiqués, éventuellement null.
expectedArrivalAt est la valeur courante de l'estimation, révisable ou effaçable. version protège chaque écriture du dossier.

shipment_facts est un journal append-only privé : id, organizationId, shipmentId, shipmentVersion, type, occurredAt nullable, recordedAt serveur, source, actorId, note et snapshots utiles.
Pour les révisions d'ETA, il conserve ancienne et nouvelle valeur. Pour les éditions du dossier, il conserve prestataire/contact/référence/note. recordedAt tient également lieu de createdAt.
L'UI présente l'ordre des révisions de saisie ; la date réelle communiquée apparaît séparément. Une déclaration tardive ne réordonne pas silencieusement l'historique.

Sources : FORWARDER, SUPPLIER, MANAGER, DOCUMENT, OTHER, UNKNOWN. La source omise devient UNKNOWN. actorId vient exclusivement de l'autorisation authentifiée ; le body n'a aucun champ d'auteur, et les champs inconnus sont rejetés.

Faits de transitions : DEPARTURE_CONFIRMED et ARRIVAL_CONFIRMED, uniquement via leurs actions dédiées.
Faits automatiques d'édition : DOSSIER_UPDATED et ETA_UPDATED.
Faits facultatifs : FORWARDER_RECEIVED, CUSTOMS_STARTED, CUSTOMS_COMPLETED, AVAILABLE_AT_FORWARDER, COLLECTED_BY_MANAGER, INFORMATION.
Ils ne modifient pas le statut du groupage. INFORMATION exige une note pour éviter une entrée vide.

Les corrections sont de nouvelles informations textuelles ou de nouvelles révisions du dossier/de l'ETA. Les premières confirmations et dates réelles restent immuables ; corriger rétroactivement une date de transition dans la projection nécessitera un workflow explicite ultérieur. Rien n'est effacé.

## API privée

Préfixe : `/api/organizations/{organizationId}/grouping-campaigns/{campaignId}`.
Toutes les mutations exigent la **version courante du groupage**, MANAGE_GROUPINGS et l'autorisation dans la boutique ; GET exige VIEW_GROUPINGS.
Réponse 200 no-store : CampaignShipmentView avec campaign (état/version serveur) et shipment (dossier/timeline privée).

| Méthode / suffixe | Corps |
|---|---|
| GET /shipment | Lecture ; shipment null si aucune saisie |
| PUT /shipment | version ; providerName/providerContact/trackingReference/note/source facultatifs |
| PUT /shipment/eta | version ; expectedArrivalAt/source/note facultatifs |
| POST /shipment/facts | version, type ; occurredAt/source/note facultatifs (note requise pour INFORMATION) |
| POST /ship | version ; departedAt/expectedArrivalAt/trackingReference/note/source facultatifs |
| POST /arrive | version ; arrivedAt/source/note facultatifs |

PUT /shipment remplace les textes courants : omission ou blanc les efface, mais garde les snapshots précédents dans le journal.
PUT /shipment/eta avec date absente retire l'estimation. Au départ, l'ETA/référence/note absente conserve celle du dossier déjà renseigné.
Les trois opérations d'enrichissement sont disponibles en SUPPLIER_ORDERED, IN_TRANSIT et ARRIVED, sans effet sur l'état principal.

Exemple minimal de confirmation du départ :
```json
{"version":7}
```
Les dates réellement inconnues restent null. Une date réelle future est refusée ; arrivée réelle et ETA ne peuvent pas précéder un départ connu. Une estimation passée reste acceptable, car elle peut avoir été communiquée tardivement.
Les instants sont UTC à la microseconde ; l'interface utilise le fuseau de l'appareil. Aucun instant métier n'est prérempli avec maintenant.

## Atomicité, concurrence, idempotence

CampaignLogistics reste le coordinateur. Autorisation → verrou scoped du groupage → contrôle de version → mutation shipping via son API publique → sauvegarde du groupage → commit.
Le départ réalise atomiquement SUPPLIER_ORDERED → IN_TRANSIT, l'arrivée IN_TRANSIT → ARRIVED. Le journal GroupingChange de ces transitions est conservé.
L'enrichissement touche la version du groupage sans changer son état. Le CAS de Shipment exige également sa révision précédente.
Une contrainte unique protège le dossier boutique/groupage ; une autre protège le fait de chaque révision ; deux index partiels interdisent deux départs/deux arrivées.

Rejeu sûr par refus : une même commande avec la même version retourne 409 au second appel et n'ajoute aucune entrée. Une nouvelle version ne permet pas une deuxième transition déjà réalisée. Les doublons concurrents ont un seul gagnant.
Le frontend n'effectue aucun retry automatique de mutation. Après erreur ou réponse perdue : GET explicite, puis nouvelle action si toujours disponible.
Une erreur de persistance annule dossier, fait et groupage dans la même transaction. Aucun nouvel événement aval, appel WhatsApp ou paiement n'est ajouté ; une panne notification ne participe pas à cette transaction.

Les API publiques et le graphe existant restent inchangés : grouping dépend de shipping::api ; aucun accès aux repositories d'un autre module et aucune relation JPA inter-module.

## Suivi client

GroupingProgress conserve exclusivement status, departedAt, expectedArrivalAt, arrivedAt. Les accès existants (jeton et customeraccess) restent responsables de l'autorisation.
Le statut se suffit à lui-même si la date réelle est inconnue. L'instant de saisie n'est jamais substitué à une date réelle dans le suivi client.
Aucun téléphone/contact de prestataire, référence, note, acteur, timeline administrative ou document n'est exposé.
ARRIVED signifie arrivée à destination, pas dédouanement achevé, possession par la gérante, transport réglé ou READY_FOR_PICKUP.
Aucun Order, SupplierOrder, Payment ou PaymentRequest n'est modifié par cette tranche.

## Migration additive 013

013-logistics-dossier.sql ajoute les colonnes et la table du journal ; elle assouplit la nullabilité de departed_at et remplace le guard de 011 sans modifier les anciens changesets.
Pour les lignes historiques : departureConfirmedAt = createdAt ; arrivalConfirmedAt = updatedAt si une arrivée existait. Ces instants reflètent les écritures historiques connues.
Toutes les anciennes dates réelles, références, notes et estimations restent intactes.
Les faits importés portent legacy=true et source=UNKNOWN ; l'auteur est repris du journal GroupingChange quand disponible, sinon reste null (exception réservée à l'import historique).
L'ancien code pouvait remplacer une arrivée inconnue par maintenant : cette distinction est irrécupérable et n'est pas inventée par la migration. L'interface signale la précision historique non garantie.
Les guards PostgreSQL interdisent modification/suppression des faits, suppression du dossier et réécriture des confirmations.
Un test Testcontainers migre une base 012 contenant un départ et une arrivée, vérifie les données/checksums conservés et la réexécution sans doublon. Aucun reset de base métier.

## Recette manuelle

1. Démarrer les dépendances avec `docker compose up -d` depuis Backend, puis `.\mvnw.cmd spring-boot:run` avec votre configuration habituelle. Lancer le frontend avec `npm start` (ou votre configuration HTTPS existante).
2. Ouvrir un groupage dont la commande fournisseur est déjà figée ; onglet Aperçu, section **Logistique**.
3. **Renseigner le prestataire** : enregistrer tous les champs vides. Le dossier existe, le groupage reste commandé au fournisseur.
4. Compléter un nom/contact/référence/note privés et la source ; consulter l'historique.
5. **Modifier l’arrivée estimée** : saisir une estimation, puis une autre ; constater les deux valeurs dans la timeline. Effacer l'estimation conserve également l'historique.
6. **Ajouter une information** : choisir réception par le prestataire et laisser la date vide. Le statut principal reste identique.
7. **Marquer comme en transport** : laisser départ/ETA/référence vides, confirmer. Le statut devient En transport, le départ est confirmé, aucune date réelle n'est inventée.
8. Actualiser le suivi client autorisé : voir En transport sans date de départ et sans données privées.
9. Côté gérante, confirmer l'arrivée avec date vide. Le statut devient Marchandise arrivée ; aucun retrait, solde de transport ou nouveau paiement n'est créé.
10. Ajouter une information de formalités puis une correction textuelle : les faits précédents restent visibles.
11. Répéter avec des dates connues ; essayer une date réelle future, un double clic et une version périmée. Vérifier le refus et la relecture sans répétition automatique.
12. Tester sur 360 px et desktop, thèmes clair/sombre, clavier ; changer de boutique pour vérifier l'effacement des champs privés.

## Reporté à ShippingAssessment

Mesures poids/CBM, poids volumétrique, calcul/facture de transport, répartition des coûts, solde SHIPPING_BALANCE et crédit, disponibilité/retrait, documents et comptes prestataires restent hors périmètre.
Les futures mesures pourront utiliser la même distinction source/acteur/instant communiqué/instant enregistré. Aucun modèle de mesure ou calcul vide n'est ajouté prématurément.

## Configuration des tests

Surefire désactive customeraccess par défaut, comme les providers réels, pour éviter que le .env local ajoute des consommateurs et des notifications aux tests des autres tranches. CustomerAccessIntegrationTests active explicitement le module et ses secrets de test ; ses assertions et ses scénarios restent exécutés. Aucun réglage de production ni contrat WhatsApp ne change. Les fixtures de nettoyage Testcontainers incluent désormais shipment_facts pour respecter sa FK.

## Validation du 2 octobre 2026

- mvnw.cmd test : 553 tests, 0 échec, 0 erreur, 0 ignoré ; BUILD SUCCESS.
- mvnw.cmd verify : 553 tests, 0 échec, 0 erreur, 0 ignoré ; BUILD SUCCESS et JAR généré.
- ModularityTests : 3 tests verts, dont ApplicationModules.of(GroupiaApplication.class).verify(), sans exclusion.
- npm test : 197 tests, 25 fichiers verts.
- npm run build : production réussie, budgets respectés (initial 302,29 kB).
- npm run test:e2e : 92 scénarios verts, mobile 360 px, desktop, clair/sombre et suivi client.
- Client Angular régénéré depuis l’OpenAPI réel exporté par les tests Spring ; aucun DTO écrit manuellement.
- git diff --check : réussi. Aucune dépendance ajoutée.

Ajouts : 13 cas d’intégration logistique (40 au total), 1 test de migration historique, 10 tests unitaires Angular et 1 scénario navigateur complet. Les tests existants imposant une date obligatoire ou une arrivée par défaut ont été adaptés au nouveau contrat explicite ; aucune assertion financière ou de modularité n’a été supprimée.
