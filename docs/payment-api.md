# API financière — cinquième tranche

Le checkout crée automatiquement INITIAL_ORDER avec ses snapshots ; aucun provider n'est appelé. PaymentRequest décrit la dette, Payment une tentative. En production aucun PaymentProvider n'est encore enregistré : une initiation répond 503 PAYMENT_PROVIDER_UNAVAILABLE. SIMULATED existe exclusivement dans les tests PostgreSQL/Testcontainers.

## Initiation privée de l'acheteur

`POST /api/public/orders/{orderNumber}/payments`

Headers : `Content-Type: application/json`, `X-Order-Tracking-Token`, `Idempotency-Key` (16 à 128 caractères ASCII alphanumériques, point, deux-points, tiret ou underscore).

Body : `{ "provider": "nom-du-provider-configuré" }`.

Le token constitue l'autorisation ; le numéro seul ne suffit pas. Organisation, montant, devise, compte marchand et références financières viennent du serveur. Même limite d'essais que le suivi privé. Le token doit être valide, non révoqué, non expiré et correspondre au numéro. La commande doit être PENDING_PAYMENT et avant son échéance. Ce contrôle demeure obligatoire lors d'un rejeu : après confirmation/expiration, la route peut refuser, sans initier un autre débit.

200 retourne uniquement `id`, `status`, `expectedAmount`, `currency`, `reconciliationRequired`, `initiatedAt`. Ce reçu ne donne pas accès aux références prestataire, causes internes, coordonnées ou données organisateur. Un statut CREATED/PENDING avec reconciliationRequired n'est pas une preuve d'échec ; le client ne doit pas réessayer avec une nouvelle clé.

Même clé : retour de la tentative existante sans second appel initiate. Nouvelle clé : autorisée seulement après un échec/annulation/expiration provider certain, si l'obligation est encore ouverte. Un timeout est durablement réconciliable. Le suivi GET existant affichera CONFIRMED après consommation de PaymentRequestSettled.

## Réception provider

`POST /api/payment/webhooks/{provider}`

Pas de JWT utilisateur. Corps brut, maximum applicatif 64 Kio ; headers d'authenticité définis par le futur adaptateur. Aucun format de signature universel ni endpoint sandbox n'est exposé. L'adaptateur valide l'authenticité avant parsing. Un provider non configuré répond 503.

202 signifie réception persistée/prise en charge, pas commande confirmée. Un événement authentique incohérent peut donc répondre 202 avec REVIEW_REQUIRED en interne. Authenticité invalide : 401 PAYMENT_AUTHENTICITY_FAILED, empreinte conservée IGNORED, aucun changement financier. Une erreur transactionnelle répond 500 ; la réception initiale reste durable et sera reprise. Une panne DB avant persistance exige la redélivrance par le provider.

Le serveur déduplique les eventId fiables dans leur périmètre provider/compte. Sans eventId, l'empreinte évite le rejeu du même corps, et les verrous/invariants empêchent le double règlement de corps différents. Un succès exige une vérification auprès du provider et une égalité exacte montant/devise/références.

## Consultation organisateur

| Route | Réponse |
| --- | --- |
| GET /api/organizations/{organizationId}/payment-requests?page=0&size=20 | items, page, size, totalElements, totalPages |
| GET /api/organizations/{organizationId}/payment-requests/{id} | request et attempts |

JWT valide, compte actif, appartenance et permission VIEW_PAYMENTS. OWNER, MANAGER et VIEWER autorisés ; OPERATOR refusé. Pagination : page 0..1 000 000, size 1..100. Une demande d'une autre organisation est introuvable même pour un membre autorisé de l'organisation de l'URL.

La demande expose obligation/ventilation/échéance/acceptation/settledPaymentId/dates/version. Les tentatives exposent statut, montants attendu/confirmé, devise, provider/transaction, dates et motif de réconciliation. Ni credentials, ni clé provider d'idempotence, ni headers/payload brut. Il n'y a aucune route de modification financière, suppression, réconciliation forcée ou force-success.

## Codes et anomalies

| Situation | Code ou motif stable |
| --- | --- |
| Demande inconnue, fermée, expirée, réglée | PAYMENT_REQUEST_NOT_FOUND, PAYMENT_REQUEST_CLOSED, PAYMENT_REQUEST_EXPIRED, PAYMENT_ALREADY_SETTLED |
| Clé absente/invalide, conflit d'initiation | PAYMENT_IDEMPOTENCY_KEY_REQUIRED, PAYMENT_INITIATION_CONFLICT |
| Commande hors délai | ORDER_PAYMENT_EXPIRED |
| Provider absent, authenticité invalide | PAYMENT_PROVIDER_UNAVAILABLE, PAYMENT_AUTHENTICITY_FAILED |
| Obligation/ventilation invalide ou référence réutilisée | INVALID_PAYMENT_REQUEST, INVALID_PAYMENT_BREAKDOWN, PAYMENT_REQUEST_CONFLICT |
| Montant/devise incohérents | AMOUNT_MISMATCH, CURRENCY_MISMATCH (motifs persistés, pas erreur HTTP du webhook) |
| Référence/provider/cible/transaction incohérents | UNKNOWN_REFERENCE, PROVIDER_MISMATCH, TARGET_MISMATCH, TRANSACTION_MISMATCH |
| État inconnu, échec du traitement | PROVIDER_STATE_UNCERTAIN, PROCESSING_FAILURE |
| Encaissement tardif, double encaissement | LATE_SUCCESS, DUPLICATE_SUCCESS |

Voir [ADR-0010](../adr/0010-financial-core-and-settlement.md) et [exploitation/reprise](payment-operations.md). Shipping balance réel, remboursements, notifications et intégrations prestataires ne font pas partie de cette tranche.

Résultats exécutés et couverture : [validation financière](payment-validation.md).
