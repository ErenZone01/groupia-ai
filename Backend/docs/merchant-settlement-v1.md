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

PSP fee allocation policy not implemented.

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

OWNER/MANAGER possèdent MANAGE_SETTLEMENTS. VIEW_PAYMENTS autorise les lectures
selon la politique existante. OPERATOR et VIEWER ne peuvent déclencher ni certifier
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

## Provider indisponible

`GROUPIA_BICTORYS_PAYOUTS_ENABLED=false` reste supporté pour les refunds existants.
Le merchant settlement a son propre opt-in
`GROUPIA_MERCHANT_PAYOUTS_ENABLED=false`. L'adaptateur de production fourni est
`UnavailableMerchantPayoutProvider` : **aucun appel réel et aucun faux succès**.
Même activer le nouveau flag ne suffit pas sans adaptateur disponible. Le ledger,
les soldes, la destination et l'UI fonctionnent sans credentials. Seuls les tests
chargent un fake provider capable de confirmer un reversement.

Le futur adaptateur Bictorys utilisera un contrat validé pour `transactionType=settlement`.
L'implémentation d'un endpoint Bictorys supposé fonctionnel est volontairement
reportée, puisque ce compte sandbox n'a pas cette capacité. Aucun webhook marchand
réel n'est branché par cette tranche.

## API et interface

Préfixe privé `/api/organizations/{organizationId}` ; réponses `Cache-Control: no-store`.

| Méthode et suffixe | Permission / contrat |
| --- | --- |
| GET /merchant-balance | VIEW_PAYMENTS, totaux + campagnes + capacités |
| GET /grouping-campaigns/{campaignId}/merchant-settlement | VIEW_PAYMENTS, position incluant cette campagne |
| GET /payout-destination | VIEW_PAYMENTS, destination masquée ou corps vide |
| PUT /payout-destination | MANAGE_PAYOUT_DESTINATION, previousDestinationId/method/phoneE164/holderName/confirmed |
| POST /grouping-campaigns/{campaignId}/merchant-payouts | MANAGE_SETTLEMENTS, Idempotency-Key + destinationId/confirmed |
| GET /merchant-payouts | VIEW_PAYMENTS, historique masqué |
| POST /merchant-payouts/{payoutId}/reconcile | MANAGE_SETTLEMENTS, interrogation serveur sans renvoi d'argent |

Angular : feature lazy `/app/versements`, store à signals, client OpenAPI régénéré,
résumé du solde, frais séparés, destination, confirmation, historique et erreurs
réseau. Liens depuis la navigation et Boutique. Le panneau « Fonds boutique »
apparaît dans le détail du groupage à partir de CLOSED. Le navigateur ne recalcule
pas l'argent et conserve la clé de demande après erreur réseau.

## Exploitation et recette locale

1. Démarrer PostgreSQL et l'application avec les flags payouts désactivés. Laisser
   Liquibase appliquer 020 normalement ; ne jamais corriger des checksums à la main.
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

- Valider et implémenter le vrai contrat Bictorys settlement, les credentials et
  permissions du compte, les preuves serveur/webhooks, les signatures et replays.
- Garantir l'unicité des transactions externes entre familles de flux si Bictorys
  partage l'espace d'identifiants refund/merchant payout. Cette V1 impose l'unicité
  provider/account/transaction dans les merchant payouts, avec corrélation métier.
- Définir une récupération provider par référence/idempotency key après timeout
  sans transaction connue et une supervision des opérations en revue. Ne jamais
  relancer aveuglément une sortie d'argent.
- Définir la politique de frais PSP et les éventuelles exigences renforcées de
  confirmation de destination ; aucun rôle PLATFORM_ADMIN n'est inventé ici.
- Paginer l'historique et remplacer les lectures de faits par lots/projections si
  les volumes le nécessitent. Le calcul V1 parcourt l'historique de la boutique.

Les limites ci-dessus ne sont pas masquées par le fake de tests. Le ledger et la
disponibilité sont utilisables ; un reversement externe réel ne l'est pas encore.
