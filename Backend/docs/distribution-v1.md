# Distribution V1 — préparation et retrait

## Règles métier

L'arrivée du groupage ne rend aucune commande physiquement prête. Une commande CONFIRMED / NOT_READY peut devenir READY_FOR_PICKUP si son groupage est ARRIVED ou DISTRIBUTING, son transport final existe, sa position est SETTLED ou CREDIT_DUE et aucune tentative transport n'est en cours ou incertaine. L'équipe doit cocher explicitement : « Je confirme que cette commande est complète et physiquement disponible pour retrait. » Aucune dérogation financière n'est prévue.

Le premier READY fait passer le groupage ARRIVED → DISTRIBUTING dans la même transaction. Le groupage reste DISTRIBUTING après une révocation ou le dernier retrait. Les autres commandes NOT_READY peuvent toujours être mesurées, finalisées, corrigées et réglées progressivement.

READY → NOT_READY exige un motif non vide. `readyForPickupAt` représente la disponibilité **courante** : il est effacé lors de la révocation et renouvelé lors d'une nouvelle préparation. Chaque ancien cycle reste dans le journal immuable.

Le retrait revalide état, version, organisation et finance. READY → PICKED_UP est définitif en V1. `pickedUpAt` vient de l'horloge serveur. Avec SETTLED, la commande devient COMPLETED et reçoit `completedAt`. Avec CREDIT_DUE, la cliente peut retirer sa commande, mais OrderStatus reste CONFIRMED : la boutique doit encore régulariser le crédit. Aucun remboursement n'est déclenché.

Mesures et finalisations/corrections sont verrouillées lorsque le fulfillment n'est plus NOT_READY (`ORDER_FULFILLMENT_LOCKED`). Les historiques restent lisibles, y compris après COMPLETED. Avant le retrait, il faut révoquer la disponibilité avec motif pour corriger le transport. Après le retrait, l'ajustement exceptionnel est hors périmètre.

## Architecture et autorisation

- `ordering` possède Order, les transitions et `order_fulfillment_events`. `OrderFulfillment` coordonne l'opération ; aucun repository d'un autre module n'est utilisé.
- `grouping::api/CampaignDistribution` expose un verrou et la transition vers DISTRIBUTING, avec la permission dédiée. Le contrat fournisseur historique reste réservé à MANAGE_GROUPINGS.
- `organization::api/MANAGE_FULFILLMENT` est accordée à OWNER, MANAGER et OPERATOR. VIEWER lit seulement. Organisation ACTIVE, utilisateur actif et membership actif sont obligatoires. OPERATOR ne reçoit pas MANAGE_GROUPINGS.
- `OrganizationAccess.hasPermission` permet de dériver les capacités d'affichage sans utiliser une exception transactionnelle comme branche normale pour VIEWER. Chaque mutation vérifie à nouveau la permission obligatoire.
- `ShippingFinance` lit la position financière existante ; aucune duplication de solde. Le moteur de paiement, le webhook et la réconciliation restent les mêmes.
- Le consommateur durable de PaymentRequestSettled accepte un règlement SHIPPING_BALANCE déjà alloué même si Order a depuis été COMPLETED, sans reconfirmation ni événement supplémentaire.

### Transaction et concurrence

Autorisation → verrou groupage → verrou commande → contrôle `@Version` → verrous des obligations transport dans l'ordre existant → revalidation finance → transition commande/groupage → journal → commit. Aucune requête réseau. La révocation n'effectue aucune mutation financière et n'exige pas que la dette soit réglée.

Le verrou de commande est rafraîchi avant de comparer la version. Une version périmée retourne 409. Le journal, la projection Order et la transition groupage partagent la même transaction : un échec de journalisation annule le tout. Deux clics concurrents ne créent qu'un événement ; le second reçoit 409. Une contrainte unique supplémentaire interdit deux événements PICKED_UP pour la même commande.

Le journal n'est pas un flux d'événements asynchrones : c'est un historique opérationnel local append-only. Une séquence technique d'insertion garantit l'ordre des cycles d'une commande, même si deux dates serveur sont identiques. Aucun événement OrderCommerciallyAccepted ni notification WhatsApp n'est émis par la distribution. Aucun event sourcing n'est introduit.

## Migration 017

`017-distribution.sql` ajoute `orders.ready_for_pickup_at` nullable, la table d'événements (UUID, scope boutique/groupage/commande, type, acteur, date serveur, motif), les index de lecture et l'unicité du retrait. Une FK vers Order et un trigger contrôlent le scope ; le trigger refuse UPDATE et DELETE des événements. Le motif de révocation est contraint en base. `018-distribution-sequence.sql` ajoute la séquence technique et adapte les index.

Les migrations 001–016 restent intactes. Aucun backfill de disponibilité, aucun changement de picked_up_at historique, aucun clearCheckSums ou reset. Les anciennes commandes ont ready_for_pickup_at = null. Les tests d'upgrade comparent toutes les colonnes historiques et les checksums en excluant seulement la nouvelle colonne nullable / les nouveaux changesets.

### Correction du checksum 017 (5 octobre 2026)

Une version initiale de 017 avait déjà été exécutée lors d'un démarrage local avant l'ajout de `recording_sequence`. Le démarrage suivant échouait avec le checksum enregistré `9:af50ee455b776733e7a54bcd9d512e3d`, contre `9:c94ed11d0a9ca499454b514b08148d07` dans le fichier enrichi. Les tests précédents couvraient les bases fraîches et l'upgrade depuis 016, mais pas cette version intermédiaire de 017.

Le SQL initial de 017 est restauré et son checksum reproduit exactement. Seul le second checksum connu est accepté explicitement pour les bases ayant déjà la séquence. Toute autre modification reste bloquante ; aucun checksum enregistré n'est réécrit et aucun `clearCheckSums` n'est utilisé.

018 fait converger les deux schémas dans une transaction avec verrou exclusif du journal. Si la séquence manque, elle ajoute cette seule métadonnée, ordonnée selon l'ancien tri `recorded_at, id`. L'ordre réel entre deux anciens événements à date identique ne peut pas être reconstruit : le tri UUID historique est conservé. La séquence des bases déjà équipées reste intacte. L'identité SQL reprend après la dernière valeur. Les champs historiques des événements et les commandes ne changent pas.

Le trigger append-only est suspendu uniquement pendant le remplissage de cette nouvelle métadonnée, sous verrou et dans la même transaction, puis réactivé avant commit. Toute erreur annule l'ensemble. Les nouveaux tests vérifient les deux checksums exacts, le refus d'une variante inconnue, la conservation des données et checksums, l'ordre, les insertions suivantes, l'immuabilité et la réexécution sans effet supplémentaire.

Après mise à jour du code, relancer normalement le backend : Liquibase applique 018. Aucune commande SQL manuelle, suppression de données ou remise à zéro du journal Liquibase n'est nécessaire.

## Contrat HTTP privé

Préfixe : `/api/organizations/{organizationId}/grouping-campaigns/{campaignId}`.

| Méthode | Chemin | Corps |
|---|---|---|
| GET | `/fulfillment` | Aucun |
| POST | `/orders/{orderId}/ready-for-pickup` | `{ "version": 3, "confirmed": true }` |
| POST | `/orders/{orderId}/revoke-readiness` | `{ "version": 4, "reason": "Article manquant" }` |
| POST | `/orders/{orderId}/picked-up` | `{ "version": 5, "confirmed": true }` |

Le résultat est un FulfillmentBatch sans cache : état/version du groupage, droit d'agir, compteurs, commandes et historique opérationnel privé. Le tableau concerne les commandes CONFIRMED et COMPLETED. Les commandes impayées, expirées ou annulées ne sont pas à distribuer.

Les compteurs sont calculés serveur : total, NOT_READY, dettes ou tentatives bloquantes, READY, PICKED_UP, crédits et commandes financièrement éligibles à la préparation. « À préparer » inclut les commandes qui nécessitent encore une finalisation ou un règlement ; ces catégories peuvent se recouper. L'éligibilité ne vaut jamais attestation physique.

Les capacités `canMarkReady`, `canRevoke` et `canPickUp` sont des indications pour l'UI ; elles ne remplacent pas les contrôles transactionnels. Identité de l'acteur, état financier, statut cible et timestamps ne sont pas acceptés comme autorité depuis le navigateur.

## Interfaces

Dans le détail du groupage, l'onglet « Distribution et retraits » apparaît à partir d'ARRIVED. Il propose compteurs, recherche par numéro ou nom, filtres, actions individuelles, attestation explicite et motif obligatoire de révocation. L'historique reste consultable. Les doubles clics sont bloqués pendant la requête ; après 409, les données sont rechargées et une nouvelle confirmation est nécessaire. La permission renvoyée par le backend pilote le mode lecture seule.

Le suivi cliente sécurisé existant expose seulement les nouveaux timestamps et états physiques, sans acteur, motif ou journal interne. NOT_READY : En préparation ; READY : Prête à être récupérée ; PICKED_UP : Commande récupérée. La timeline distingue préparation et remise. Un crédit est affiché séparément ; la dette conserve le parcours de paiement existant. Aucune mutation cliente de fulfillment n'est ajoutée. Le client Angular est régénéré depuis OpenAPI.

## Recette manuelle complète

1. Sur un environnement de test, préparer un groupage avec plusieurs commandes confirmées, puis enregistrer le départ et l'arrivée. Avant ARRIVED, vérifier que la préparation n'est pas proposée et que le backend refuse l'action.
2. Ouvrir « Distribution et retraits ». Vérifier compteurs, recherche par numéro/nom et filtres. Sans transport final, aucune action Prête ne doit être disponible.
3. Finaliser une commande avec un solde positif : vérifier « En attente de paiement », bouton de paiement dans son suivi cliente et refus de préparation. Pendant une tentative PENDING/incertaine, la préparation reste refusée.
4. Finaliser une commande à un montant égal au transport payé. Cliquer « Marquer prête » : le bouton de confirmation doit être désactivé avant l'attestation. Confirmer ; vérifier READY, readyForPickupAt serveur, un événement et groupage DISTRIBUTING.
5. Sur une autre commande NOT_READY du même groupage DISTRIBUTING, enregistrer les mesures, finaliser, payer le solde via le parcours existant et attendre son règlement. Elle peut ensuite devenir prête à son tour.
6. Sur une commande prête, tenter une correction transport : refus clair. Retirer la mise à disposition ; un motif vide est refusé. Avec un motif, vérifier NOT_READY, disponibilité courante effacée, événements précédents conservés et groupage toujours DISTRIBUTING. Corriger puis préparer à nouveau.
7. Confirmer la remise complète d'une commande SETTLED. Vérifier PICKED_UP, pickedUpAt, Order COMPLETED et completedAt. Aucun nouveau paiement ou message de confirmation de commande ne doit être créé. Un second retrait et une correction ordinaire sont refusés.
8. Finaliser une autre commande en CREDIT_DUE. Préparer puis remettre la commande : PICKED_UP, Order toujours CONFIRMED, crédit conservé et aucun remboursement automatique. Le crédit ne bloque pas la remise physique.
9. Ouvrir la même commande dans deux sessions équipe : une seule préparation/remise aboutit, la session périmée reçoit 409 et recharge. Vérifier un seul événement par action réussie.
10. Tester OWNER, MANAGER et OPERATOR : préparation/remise autorisées. Vérifier qu'OPERATOR ne peut toujours pas modifier les campagnes. VIEWER voit le tableau sans actions. Une organisation archivée, un membre révoqué et une autre boutique ne peuvent pas agir.
11. Dans le suivi cliente (accès commande et téléphone vérifié), vérifier dates/états sans notes privées ni acteurs, et crédit affiché indépendamment du retrait. Le lien existant reste utilisable selon sa politique d'expiration habituelle.
12. Vérifier à 360 px et desktop, clair/sombre, focus clavier, case d'attestation au clavier et absence de débordement horizontal.
13. Appliquer les migrations à une base isolée fraîche puis à des copies de test en 016 et dans chacune des deux versions connues de 017 : données historiques et checksums conservés, disponibilité ancienne null, événements immuables. Ne pas provoquer volontairement d'erreur SQL dans la base de travail.

## Hors tranche

Clôture définitive du groupage (COMPLETED), remboursements/régularisation des crédits, ajustements financiers exceptionnels après retrait, retrait partiel, préparation en lot, QR/OTP/signature/photo obligatoires et notification WhatsApp « prête » sans template Meta approuvé. Aucun nouvel envoi Meta et aucune dépendance ajoutée.

## Validation exécutée

Backend validé le 5 octobre 2026 ; frontend validé le 4 octobre 2026, sans modification frontend depuis ces exécutions.

| Commande | Résultat |
|---|---|
| `Backend: .\mvnw.cmd test` | BUILD SUCCESS — 639 tests, 0 échec, 0 erreur, 0 ignoré |
| `Backend: .\mvnw.cmd verify` | BUILD SUCCESS — 639 tests, 0 échec, 0 erreur, 0 ignoré ; JAR Spring Boot produit |
| Spring Modulith, inclus dans les deux commandes Maven | 3 tests réussis, dont `ApplicationModules.verify()` |
| Distribution, incluse dans les deux commandes Maven | 21 tests d'intégration, 1 test d'upgrade depuis 016 et 3 tests de compatibilité des checksums 017 réussis |
| `Frontend: npm test` | 241 tests réussis, 28 fichiers |
| `Frontend: npm run build` | Build de production réussi |
| `Frontend: npm run test:e2e` | 119 tests réussis, dont 8 Distribution |
| `git diff --check` | Réussi |

Les tests PostgreSQL utilisent des conteneurs isolés : création fraîche, upgrade depuis 016, préservation des données/checksums et immutabilité du journal sont vérifiés. Aucune migration n'a été appliquée à la base de travail par cette validation ; aucun paiement réel ni envoi Meta n'a été effectué.

Une ancienne fixture de révocation de membre utilisait l'horloge PostgreSQL après une création datée par Java. Un léger décalage entre les deux horloges pouvait produire une révocation antérieure à l'adhésion. La fixture utilise désormais `greatest(current_timestamp, joined_at)` ; la contrainte de production est conservée. Les 34 tests identité/boutique passent dans les deux exécutions Maven finales.

Logs locaux : `Backend/target/distribution-repair-test.log`, `Backend/target/distribution-repair-verify.log` et `Frontend/artifacts/distribution-{unit,build,e2e}.log`.
