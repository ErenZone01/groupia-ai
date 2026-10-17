# Merchant Settlement V1

Implémenté le 6 octobre 2026. Voir [ADR-0020](../adr/0020-merchant-settlement.md).

## Périmètre et architecture

Le module fermé `settlement` possède le ledger, les destinations et les reversements.
Il dépend uniquement de `organization::api`, `payment::api`, `ordering::api`,
`grouping::api` et `shared::api`. Aucun repository intermodule ni association JPA.
Les ports `MerchantOrderFacts` et `MerchantCampaignFacts` exposent uniquement les
faits nécessaires au calcul. Le contrôleur exige un compte et une appartenance
actifs, puis la permission correspondant à l'action.

Flux : paiement client réglé → crédit marchand durable → disponibilité après
clôture → demande de MerchantPayout → réservation → preuve provider serveur →
débit marchand. Aucun versement automatique par commande.

## Ledger et historique

`merchant_ledger_entries` est append-only, protégé contre UPDATE et DELETE par
PostgreSQL. Références métier et couples source/type uniques :

| Type | Sens | Montant | Référence |
| --- | --- | --- | --- |
| ORDER_COLLECTION | CREDIT | goodsAmount + shippingAmount | COLLECTION:{requestId} |
| SHIPPING_BALANCE_COLLECTION | CREDIT | shippingAmount | COLLECTION:{requestId} |
| PLATFORM_FUNDED_CUSTOMER_REFUND | DEBIT | Refund réellement réussi | CUSTOMER_REFUND:{refundId} |
| MERCHANT_PAYOUT | DEBIT | montant du versement réussi | PAYOUT:{payoutId} |

XOF entier exact en BigDecimal/NUMERIC. Les frais Groupia sont conservés comme
métadonnée de présentation, jamais additionnés au crédit marchand. Ainsi 20 000
de produits + 5 000 de transport + 300 de frais donnent un crédit de **25 000**.
Un solde transport réglé de 2 000 donne un crédit supplémentaire de **2 000**.

Les consumers `@ApplicationModuleListener` vérifient les faits financiers source.
La confirmation de commande et le règlement peuvent être livrés dans n'importe
quel ordre : `OrderCommerciallyAccepted` complète l'attribution initiale si
`PaymentRequestSettled` est arrivé avant la confirmation. La même référence de
collecte empêche un double crédit. PENDING ne crédite rien.

Migration **020-merchant-settlement.sql** : crée les trois tables, contraintes,
index et triggers, puis reprend les obligations CLOSED avec paiement alloué
SUCCEEDED et commande CONFIRMED/COMPLETED. Elle utilise `closed_at` et les
décompositions existantes, sans inventer de dates/montants. Les refunds Bictorys
SUCCEEDED utilisent `completed_at`. Les références sont celles des consumers,
avec conflits idempotents. Les anciennes lignes ne sont pas réécrites. Les SQL
001–019 et leurs checksums ne changent pas. Tests : installation fraîche et
upgrade 019 contenant paiements initiaux, transport et deux origines de refund.

## Origine des remboursements

**BICTORYS_PAYOUT SUCCEEDED** sort des fonds Groupia : débit marchand du montant
remboursé. **MANUAL_EXTERNAL** a déjà été financé personnellement par la boutique :
aucun débit marchand, y compris lors du backfill et d'un replay. Le déduire une
seconde fois serait incorrect. Un refund non réussi ne produit aucun débit.

Les frais provider du payout marchand sont supportés par Groupia en V1.

Aucune déduction implicite des frais Bictorys, Wave, Orange Money ou payout.

## Calcul et disponibilité

`collectedBalance = grossMerchantCollections - platformFundedRefunds - merchantPayouts`.
`platformFees` est affiché séparément, hors montant marchand déjà net de ces frais.

Seuls les crédits de commandes CONFIRMED/COMPLETED dans les campagnes CLOSED,
SUPPLIER_ORDERED, IN_TRANSIT, ARRIVED, DISTRIBUTING ou COMPLETED sont éligibles.
OPEN reste visible mais indisponible. Tous les débits réduisent la position
éligible ; ils ne disparaissent pas si la campagne cesse d'être éligible.

`reservedForPayout` totalise CREATED, PENDING et REVIEW_REQUIRED.
`availableForPayout = max(eligibleBalance - reservedForPayout, 0)`.
La disponibilité d'une campagne est plafonnée par la disponibilité globale de la
boutique : une dette d'un ancien groupage doit être absorbée avant un nouveau
versement. Les disponibilités par campagne partagent ce plafond et ne doivent
pas être additionnées côté navigateur. Le serveur fournit le total global.

Un refund après reversement peut créer un solde négatif. L'UI affiche « Solde à
compenser ». Aucun prélèvement automatique sur le compte de la gérante. Les
futurs crédits compensent ce négatif. La lecture globale utilise un snapshot
REPEATABLE_READ ; les mutations et réservations prennent un verrou par boutique.

## Destination et permissions

Une destination ACTIVE par boutique : WAVE ou ORANGE_MONEY, numéro Sénégal
normalisé `+221` et neuf chiffres, titulaire et acteurs/date/version. OWNER seul
possède MANAGE_PAYOUT_DESTINATION. Une modification désactive la révision
précédente et en crée une nouvelle. `previousDestinationId` détecte les conflits.
Chaque payout conserve méthode, numéro et titulaire immuables. L'API de lecture
masque le numéro ; aucun secret provider ni clé d'idempotence provider n'est exposé.

OWNER seul possède EXECUTE_PAYOUTS. MANAGE_SETTLEMENTS ne permet plus de transférer
des fonds. VIEW_PAYMENTS autorise les lectures selon la politique existante.
MANAGER, OPERATOR et VIEWER ne peuvent déclencher ni certifier
de sortie de fonds. L'API n'accepte ni montant, ni acteur, ni statut financier,
ni transaction provider dans les commandes navigateur ; champs inconnus rejetés.

## Réservation et preuve

Une demande porte sur une campagne et **tout son disponible courant calculé au
serveur**, sans montant libre ou fraction volontaire. Elle contient l'identifiant
de destination confirmée et un en-tête UUID Idempotency-Key. Rejouer la même clé
retourne la même opération ; changer sa campagne/destination est un conflit.

La transaction réserve et snapshote les fonds avant tout appel réseau. Un claim
atomique ne laisse qu'un appel d'initiation. Un timeout conserve les fonds en
REVIEW_REQUIRED ; une nouvelle demande ne contourne pas cette réservation.
L'initiation seule n'est jamais une preuve de succès. La réconciliation demande
au provider une preuve serveur et vérifie authentification, provider/account,
référence métier, transaction unique, montant, devise et destination snapshotée.
SUCCEEDED ajoute le débit dans la même transaction, une seule fois, sans régression.
FAILED/CANCELLED libèrent la réservation seulement avec une observation temporelle
acceptable. Un succès tardif reste reconnu et peut rendre le solde négatif.

Il n'existe aucun bouton ni endpoint « J'ai reçu l'argent ». La demande de
réconciliation ne permet pas de fournir soi-même une preuve ou un statut.
SUPPLIER_ORDERED n'est pas conditionné au succès d'un reversement en V1.

## Provider sandbox et désactivation

`GROUPIA_BICTORYS_PAYOUTS_ENABLED=false` reste supporté pour les refunds existants.
Le merchant settlement a son propre opt-in
`GROUPIA_MERCHANT_PAYOUTS_ENABLED=false`. L'adaptateur
`PaymentOutgoingMerchantPayoutProvider` délègue à `payment::api`. Il exige les deux
flags payouts, le provider Bictorys activé, son URL sandbox exacte et ses credentials
de test valides. Le ledger, les soldes, la destination et l'UI restent consultables
avec les flags désactivés. Les credentials de production restent refusés.

Le payout marchand utilise `POST /pay/v1/payouts` avec `transactionType=payment`.
Le webhook unique Payment traite ensuite la preuve corrélée. Les refunds conservent
leur contrat `transactionType=refund` et le même transport HTTP.

## API et interface

Préfixe privé `/api/organizations/{organizationId}` ; réponses `Cache-Control: no-store`.

| Méthode et suffixe | Permission / contrat |
| --- | --- |
| GET /merchant-balance | VIEW_PAYMENTS, totaux + campagnes + capacités |
| GET /grouping-campaigns/{campaignId}/merchant-settlement | VIEW_PAYMENTS, position incluant cette campagne |
| GET /payout-destination | VIEW_PAYMENTS, destination masquée ou corps vide |
| PUT /payout-destination | MANAGE_PAYOUT_DESTINATION, previousDestinationId/method/phoneE164/holderName/confirmed |
| POST /grouping-campaigns/{campaignId}/merchant-payouts | EXECUTE_PAYOUTS, Idempotency-Key + destinationId/confirmed |
| GET /merchant-payouts | VIEW_PAYMENTS, historique masqué |
| POST /merchant-payouts/{payoutId}/reconcile | EXECUTE_PAYOUTS, interrogation serveur sans renvoi d'argent |

Angular : feature lazy `/app/versements`, store à signals, client OpenAPI régénéré,
résumé du solde, frais séparés, destination, confirmation, historique et erreurs
réseau. Liens depuis la navigation et Boutique. Le panneau « Fonds boutique »
apparaît dans le détail du groupage à partir de CLOSED. Le navigateur ne recalcule
pas l'argent et conserve la clé de demande après erreur réseau.

## Exploitation et recette locale

1. Démarrer PostgreSQL et l'application avec les flags payouts désactivés. Laisser
   Liquibase appliquer les migrations jusqu'à 027 normalement ; ne jamais corriger des checksums à la main.
2. Se connecter en OWNER, ouvrir Mes versements, enregistrer Wave/Orange Money avec
   confirmation explicite. Vérifier le numéro masqué et la bannière d'indisponibilité.
3. Sur une commande réellement réglée, comparer goods + shipping à la collecte ;
   constater que les frais sont séparés. Une campagne OPEN n'a aucun disponible.
4. Clôturer la campagne : constater le disponible et le panneau Fonds boutique.
   Le passage à la commande fournisseur demeure utilisable sans payout.
5. Comparer MANAGER (destination visible, non modifiable), VIEWER (lecture seule)
   et OPERATOR (aucune mutation financière). Aucune demande externe n'est possible
   tant que l'adaptateur de production est indisponible.
6. Exécuter les tests PostgreSQL avec fake provider pour réservation, concurrence,
   timeout, réconciliation, preuves incompatibles, succès/replay et solde négatif.
   Les fixtures restent dans les bases isolées Testcontainers et les mocks E2E.

Pour un consumer en panne, rechercher les publications FAILED/incomplètes de
`MerchantLedgerListeners` via la procédure existante de payment-operations. Corriger
la cause puis resoumettre avec `FailedEventPublications` ; la référence source
empêche un doublon. Ne jamais réparer par UPDATE du ledger. Observer les échecs
répétés et la latence d'attribution avant d'ouvrir les versements réels.

## Avant production

- Valider sur une transaction sandbox réelle le POST, le webhook et le GET détail,
  puis capturer des fixtures anonymisées. Les tests automatisés utilisent le contrat
  documenté ; aucune transaction externe réelle n'a été exécutée par ces tests.
- Conserver l'unicité commune refund/merchant payout garantie par le registre 027.
- Définir une récupération provider par référence/idempotency key après timeout
  sans transaction connue et une supervision des opérations en revue. Ne jamais
  relancer aveuglément une sortie d'argent.
- Ajouter une authentification récente ou renforcée avant les payouts de production,
  notamment avec de futures sessions prolongées ; aucun rôle PLATFORM_ADMIN n'est inventé ici.
- Paginer l'historique et remplacer les lectures de faits par lots/projections si
  les volumes le nécessitent. Le calcul V1 parcourt l'historique de la boutique.

Le flow sandbox est implémenté ; l'activation production reste hors périmètre.

## Bictorys Merchant Payout V1 — sandbox

This tranche supersedes the unavailable provider and OWNER/MANAGER execution policy.
Only OWNER with EXECUTE_PAYOUTS can request or reconcile; financial reads remain available
under VIEW_PAYMENTS. Destination changes remain OWNER-only.

Payment owns the generic outgoing provider API and transaction registry. Settlement
owns the reserved funds and atomic SUCCEEDED + principal-only ledger debit. The shared
outgoing receipt journal and durable Modulith events recover consumer failures.
POST acknowledgement is never proof. Uncertain payouts keep funds reserved; no automatic
POST retry or new idempotency key is allowed. Reconcile never initiates a transfer.
Without a transaction ID operator review is required. Contradictions/reversals after
success remain incidents without changing historical money.

Merchant transactionType is payment in the current sandbox contract (not the earlier
future-settlement assumption). Provider fees are borne by Groupia. Incompatible amount
semantics produce PAYOUT_AMOUNT_SEMANTICS_UNCONFIRMED, never a guessed fee formula.
See [ADR 0027](../adr/0027-bictorys-merchant-payout.md).
