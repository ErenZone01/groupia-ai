# Groupia — moteur unifié des soldes et reversements marchands

État au 12 novembre 2026 : code publié dans la PR #86, **non fusionné et non
validé par un nouveau `mvn verify`**. Tests et fournisseurs synthétiques uniquement.

## Architecture commune

- Le registre des encaissements `provider_collections` identifie la source
  immuable, sa boutique, son compte PSP exact et son activité
  (`ORDER` groupage, `DIRECT_ORDER` vente directe).
- La route `GET /api/organizations/{organizationId}/provider-collections/overview`
  affiche leurs **cumuls historiques**, sans prétendre qu'ils sont retirables.
- Le moteur unique `merchant_payouts` conserve destination, référence
  d'intention, clé d'idempotence, fournisseur, compte, statuts, réservations,
  preuve de réception et historique. Une ligne cible exactement un groupage
  ou une vente directe. La migration additive `055` ajoute
  `direct_order_id` et conserve les anciennes lignes sans les réécrire.
- Le moteur comptable `MerchantBalances` expose désormais les positions
  `campaigns` et `directOrders` dans le même solde boutique. Le frontend
  `/app/versements` permet de consulter et de demander les deux types de
  versement, avec la même destination, modal de confirmation et suivi.
- L'API de vente directe est
  `POST /api/organizations/{organizationId}/direct-orders/{directOrderId}/merchant-payouts`
  avec `Idempotency-Key`, `destinationId` et `confirmed`.
  Le chemin historique des groupages ne change pas.

## Quand une vente directe est éligible

Le serveur exige, dans une transaction et sous verrou de boutique et de commande :

1. un encaissement canonique terminé, dans `provider_collections`, lié à
   la même commande et à une preuve réelle `PaymentOperations` de paiement
   `SUCCEEDED`, accepté, sans inversion ni risque de rapprochement ;
2. la commande `COMPLETED` et sans remboursement commercial ouvert,
   ou une annulation `REFUNDED` et un remboursement externe enregistré par
   la vendeuse sans ancienne commission restant à compenser ;
3. le fournisseur, **le compte de fonds** et la devise strictement identiques
   à ceux de l'adaptateur de reversement ; pas de transfert croisé ;
4. le montant exact attribué à la vendeuse, déduction faite de tous les
   payouts directs déjà `SUCCEEDED` et de ceux encore `CREATED`,
   `PENDING` ou `REVIEW_REQUIRED` pour cette commande ;
5. la destination active et le rôle `EXECUTE_PAYOUTS`, avec contrôles
   d'idempotence, relecture de l'éligibilité juste avant l'initiation réseau.

Une réponse de création ou un timeout ne libère jamais les fonds réservés.
La vérification du fournisseur reste indépendante ; les fonds ne sont
considérés versés que sur preuve `SUCCEEDED`. Les versements directs réussis
sont conservés immuablement dans `merchant_payouts`. Le ledger historique
des campagnes n'invente pas de fausse campagne pour ces ventes.

## Verrous de sécurité et activation

Les **deux** variables suivantes restent à `false` hors recette de test :

```dotenv
GROUPIA_MERCHANT_PAYOUTS_ENABLED=false
GROUPIA_DIRECT_MERCHANT_PAYOUTS_ENABLED=false
```

Le drapeau direct est indépendant et désactivé par défaut. Les tests
PostgreSQL l'activent avec un fournisseur entièrement synthétique. En
production SasPay, `PRODUCTION_PAYOUT_RELEASED=false` est conservé :
aucun jeu de variables ne peut autoriser un retrait SasPay LIVE sans
permissions marketplace, preuve indépendante du destinataire, financement
comptable des frais et validation du fournisseur.

Les remboursements clients ne sont pas initiés par ce nouveau parcours.
Une preuve de pay-in ne constitue jamais une preuve de paiement au vendeur.

## Validation à réaliser localement

- `mvn spotless:apply`, `mvn spotless:check`
- `mvn "-Dtest=UnifiedMerchantPayoutIntegrationTests,MerchantSettlementIntegrationTests,MarketplaceSimulationIntegrationTests,OpenApiIntegrationTests" test`
- `mvn verify` après la validation ciblée
- Frontend : test Vitest `merchant-settlement.store.spec.ts`, lint, build
- Export OpenAPI du backend, puis régénérer le snapshot frontend et les clients
  générés (voir `Frontend/tools/generate-api.mjs`).
- Contrôles de sécurité et CI quand les minutes GitHub seront disponibles.

Ce lot **ne prétend pas** valider les frais de payout SasPay ni la disponibilité
des fonds dans un compte vendeur partenaire. Les validations locales avant la
migration `055` ne remplacent pas ces nouveaux contrôles.
