# ADR-0010 — Noyau financier, règlement et reprise

- Statut : accepté pour la cinquième tranche, sans prestataire réel
- Date : 2026-09-20
- Complète ADR-0001, ADR-0003, ADR-0004 et ADR-0009

## Obligation et tentative

PaymentRequest est une racine indépendante et immuable commercialement : UUID organisation/commande, purpose, businessReference, montant XOF et ventilation. Payment représente une seule tentative provider. PaymentWebhookEvent est une réception technique indépendante. PaymentRefund reste une cible future ; SUCCEEDED n'est jamais remplacé par REFUNDED.

INITIAL_ORDER est créé automatiquement, une seule fois par organisation/commande. Sa businessReference est `INITIAL_ORDER:<orderId>`. SHIPPING_BALANCE et SUPERSEDED sont réservés dans le vocabulaire mais aucun workflow transport réel ou remplacement d'obligation n'est créé. Les échéances sont obligatoires dans cette tranche. Une obligation nulle peut être conservée mais son initiation est refusée avec PAYMENT_ZERO_AMOUNT_REQUIRES_REVIEW ; aucune fausse preuve de débit n'est fabriquée.

## Transaction de checkout : amendement explicite

La demande utilisateur de cette tranche remplace, pour INITIAL_ORDER seulement, la création après commit envisagée dans ADR-0001/0004. CheckoutService appelle PaymentOperations.createRequest, avec propagation MANDATORY, dans la transaction d'admission existante. Customer, Order, OrderItem et PaymentRequest commitent ou sont annulés ensemble. Une FK scalaire composée garantit la même organisation. Aucun appel provider n'a lieu au checkout. Les futures évaluations de transport et effets secondaires restent hors de cette transaction.

Les montants viennent exclusivement des snapshots Order : marchandises + frais + transport initial = initialAmountDue. L'échéance est paymentDeadline, à la précision microseconde PostgreSQL. Aucun recalcul catalogue. BigDecimal, XOF entier, absence de double/float ; les sommes déjà normalisées sont validées sans nouvel arrondi.

## Frontière réseau

Ordering valide le jeton privé, son expiration/révocation, le numéro, l'état PENDING_PAYMENT et l'échéance dans une transaction terminée avant initiation. Payment revalide l'acceptation sous verrou.

1. Transaction courte : verrou PaymentRequest, recherche de la clé client, contrôle des tentatives, création/claim Payment CREATED et commit. La tentative est déjà marquée PROVIDER_STATE_UNCERTAIN pour couvrir un crash immédiatement après ce commit.
2. Sans transaction : PaymentProvider.initiate avec merchantReference et providerIdempotencyKey générées côté serveur. Un succès immédiat est également vérifié avec verify.
3. Nouvelle transaction : validation financière, enregistrement du résultat, éventuelle affectation du règlement et publication persistante.

Les orchestrateurs portent NEVER. La simulation de test vérifie l'absence de transaction dans tous les appels provider. Aucun retry ne rappelle automatiquement initiate. Un timeout conserve la tentative pour verify ; il ne fabrique pas FAILED. Une erreur d'écriture après l'appel laisse le claim durable réconciliable.

Idempotency-Key client est distincte de providerIdempotencyKey : même clé/même demande retrouve la tentative, même clé/autre provider est refusée. Une nouvelle clé est autorisée après FAILED, CANCELLED ou EXPIRED certain, sans réconciliation en attente et tant que la demande reste ouverte/non expirée. CREATED/PENDING, succès ou anomalie bloquent un nouveau débit. Le compte marchand est sélectionné par configuration serveur, jamais par le navigateur. Le registre accepte actuellement un compte par provider ; multi-compte reste une extension de résolution, les données et unicités portent déjà ce périmètre.

## Port prestataire et preuve

PaymentProvider (domain.service) définit initiate, verify, verifyAuthenticity et parse. ProviderInstruction/ProviderObservation/NormalizedWebhook sont propres à payment. Un futur adaptateur infrastructure doit normaliser références, compte, transaction, demande, commande, purpose, montant, devise, statut et date, et corréler les métadonnées fiables. Il doit pouvoir vérifier à partir de la merchantReference même si la réponse d'initiation a été perdue.

Aucun adaptateur runtime dans cette tranche. SIMULATED vit exclusivement dans src/test ; le runtime retourne PAYMENT_PROVIDER_UNAVAILABLE. Aucun endpoint sandbox ni force-success.

Avant succès : comparaison exacte de toutes les références et du montant/devise avec la demande et la tentative. La transaction prestataire appartient à une seule tentative dans le périmètre provider/compte. Un webhook authentique SUCCEEDED exige en plus verify, avec une preuve cohérente. Montant/devise incohérents : AMOUNT_MISMATCH/CURRENCY_MISMATCH, conservation de la preuve minimale, aucune confirmation. Une référence inconnue reste une réception REVIEW_REQUIRED ; on ne modifie pas une tentative d'un autre provider/compte.

## Transitions et affectation

CREATED peut devenir PENDING ou un état terminal vérifié. PENDING peut devenir SUCCEEDED/FAILED/CANCELLED/EXPIRED. Les observations non réussies plus anciennes sont ignorées. FAILED/CANCELLED/EXPIRED ne redeviennent pas PENDING ; ils peuvent devenir SUCCEEDED uniquement avec la preuve financière valide. SUCCEEDED ne régresse jamais. Une observation incohérente peut ajouter un signal de réconciliation sans effacer un succès déjà acquis.

Un premier succès validé règle uniquement une demande OPEN, non expirée et non réglée : settledPaymentId, CLOSED, closedAt. L'heure de décision est celle de Groupia lors de l'enregistrement validé, pas la date déclarée par le provider. À l'échéance exacte, il est déjà trop tard. Cette règle conservatrice signifie qu'une preuve tardivement reçue d'un débit antérieur nécessite aussi une investigation.

- Succès tardif ou acceptation annulée : Payment SUCCEEDED, LATE_SUCCESS, aucun règlement automatique.
- Deuxième succès réel : Payment SUCCEEDED, DUPLICATE_SUCCESS ; première affectation conservée, aucun second événement. Le doublon prévaut si la demande était déjà réglée, même après l'échéance.
- Aucun remboursement, transfert d'excédent ou réactivation automatique.

## Événement et commande

PaymentRequestSettled est publié dans la transaction de règlement avec eventId, occurredAt, organisation, commande, demande, tentative, purpose, montant, devise. Spring Modulith persiste sa publication dans event_publication. OrderSettlementListener utilise ApplicationModuleListener : traitement asynchrone dans sa propre transaction, verrou de commande, contrôle du contrat via payment::api puis confirmation idempotente.

Une panne du consumer ne rollbacke pas l'argent enregistré. Un doublon n'incrémente pas à nouveau la version ni confirmedAt. CANCELLED/EXPIRED/COMPLETED ne sont jamais réactivées ; une incompatibilité est signalée dans les logs pour investigation. Une livraison tardive d'un règlement accepté avant l'échéance peut confirmer une commande encore PENDING_PAYMENT : la date du fait de règlement est conservée.

## Expiration concurrente

OrderExpiration est configurable, utilise Clock et parcourt les commandes dues par pages avec curseur UUID. Chaque commande est traitée dans une transaction REQUIRES_NEW : verrou Order, fermeture de l'acceptation via payment::api, puis EXPIRED. Si un règlement a déjà gagné, l'expiration attend la confirmation événementielle et ne change rien. Une première page contenant de tels cas ne bloque pas les suivantes.

Les écritures financières prennent les verrous dans l'ordre PaymentRequest puis Payment. Une réception webhook ajoute son propre verrou en premier. Le traitement du succès ne verrouille jamais Order ; il ne forme donc pas de cycle avec l'expiration. Les verrous PostgreSQL et contraintes d'unicité protègent les exécutions concurrentes, y compris plusieurs instances. Plusieurs instances peuvent dupliquer des lectures/verify sans double débit ni double affectation. Il n'y a pas de coordination distribuée supplémentaire.

## Réceptions et exploitation

Authenticité vérifiée avant parsing financier, réception commitée séparément du traitement. Stockage : SHA-256 du payload, observation normalisée strictement financière, codes stables ; jamais headers de signature, credentials ou payload brut. Limite applicative 64 Kio. Une signature invalide est conservée IGNORED et répond 401 sans réserver l'identifiant externe d'un événement légitime.

Déduplication par provider/compte/eventId fiable ; sinon empreinte. Les valeurs nulles de compte font partie du périmètre PostgreSQL NULLS NOT DISTINCT. Le traitement financier reste idempotent pour des payloads différents décrivant le même succès.

Échec temporaire de vérification ou de transaction : RETRYABLE_FAILURE, délai 60 secondes × nombre de tentatives, maximum cinq, puis REVIEW_REQUIRED. La réception reste durable même si une panne DB empêche aussi d'enregistrer le compteur de reprise. Le scheduler poursuit les autres réceptions après l'échec d'une réception. Doublons et concurrence respectent nextAttemptAt sous verrou. Voir le [runbook](../docs/payment-operations.md) pour Modulith, suivi des erreurs et reprise manuelle.

## Schéma et dépendances

Migration additive 007-payments : trois tables, contraintes monétaires, unicités, FK scalaires sans cascade, index de consultation/reprise/expiration, FK différée de settledPaymentId garantissant la même demande/organisation. @Version complète les verrous. Aucune unicité ne limite une demande à un seul Payment SUCCEEDED. PostgreSQL 15+ requis pour NULLS NOT DISTINCT (environnement du projet : PostgreSQL 18).

Les commandes existantes reçoivent une obligation à partir de leurs snapshots ; seules les PENDING_PAYMENT sont OPEN, les autres CANCELLED. Aucun débit ni succès historique n'est inventé par la migration.

Graphe Java : ordering → payment::api ; payment → organization::api, shared::api. payment ne dépend pas de ordering ou shipping. La FK SQL vers orders n'est pas une dépendance Java ni une lecture transversale dans un service. VIEW_PAYMENTS est centralisé : OWNER/MANAGER/VIEWER, pas OPERATOR. shared héberge uniquement la reprise technique du registre, sans contrat métier. Aucune dépendance Maven ajoutée ; test Modulith inchangé et intégral.
