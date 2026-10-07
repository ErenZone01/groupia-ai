# Modèle métier Groupia V1

Statut : modèle validé le 17 septembre 2026, avec précisions d'architecture et points ouverts explicitement identifiés ci-dessous. Ce document décrit la cible. Les tranches identity/organization (18 septembre 2026), Product/ProductVariant et GroupingCampaign/GroupingOffer/consultation publique (20 septembre 2026) ainsi que Customer/Order/OrderItem/checkout public (20 septembre 2026) sont implémentées ; les autres entités et workflows restent non implémentés. Voir les contrats [identity/organization](identity-organization-api.md), [catalogue](catalog-api.md), [groupage](grouping-api.md) et [commandes](ordering-api.md).

Les décisions reçues remplacent deux propositions antérieures : les prix des offres peuvent évoluer pendant OPEN ; shipping ne dépend jamais de payment. Les règles détaillées sont dans [business-rules.md](business-rules.md), le graphe dans [module-dependencies.md](module-dependencies.md) et les décisions structurantes dans les [ADR](../adr/README.md).

## Périmètre et conventions

- Une commande appartient à exactement une organisation, une campagne et une devise. Le MVP accepte uniquement XOF.
- Customer est un contact commercial, distinct de UserAccount. Un achat public ne nécessite pas de compte.
- Tous les identifiants métier sont des UUID. Les références intermodules sont des identifiants, jamais des associations JPA.
- Les objets appartenant à une organisation portent son identifiant. UserAccount est une identité globale ; un webhook non encore attribué ou un audit plateforme peut ne pas avoir d'organisation.
- Les montants sont des décimaux exacts, avec devise explicite. Les charges finales XOF ont une échelle de zéro ; HALF_UP s'applique sauf contrat prestataire contraire.
- Les horodatages sont des instants. Les agrégats mutables portent une version de concurrence ; les observations et calculs historiques sont immuables.
- Une absence de mesure ou d'estimation n'est jamais représentée par zéro.
- Les statuts ci-dessous sont des vocabulaires métier. Leur représentation physique sera décidée à l'implémentation.

## identity

### UserAccount — racine

**Données :** id, phoneNumber E.164 unique, email facultatif, passwordHash BCrypt interne, statut ACTIVE/DISABLED, createdAt, updatedAt, version. Le premier slice utilise une identité locale téléphone/mot de passe et des access tokens JWT. Le hash n'est jamais exposé.

**Responsabilité :** identifier et authentifier un utilisateur organisateur. Aucun panier, Customer ou Order n'appartient à cet agrégat.

**Invariants :** l'identifiant de connexion choisi est unique selon sa règle de normalisation ; un compte désactivé ne peut exercer de droits organisateur. Les permissions dans une organisation appartiennent à organization.

**Relations :** aucune dépendance métier vers organization ou ordering. OrganizationMember référence le compte par UUID.

**Cycle de vie :** inscription en ACTIVE, connexion autorisée pour les comptes actifs. DISABLED est prévu mais aucun endpoint de désactivation n'est exposé. OTP, récupération, MFA et refresh tokens restent hors de la première tranche.

## organization

### Organization — racine ; OrganizationMember — entité de gouvernance

**Organization :** id, nom, statut ACTIVE/ARCHIVED, defaultCurrency XOF, countryCode SN, timezone Africa/Dakar, createdAt, updatedAt, version. Les valeurs par défaut sont fixées par le serveur dans la première tranche.

**OrganizationMember :** id, organizationId, userAccountId, rôle OWNER/MANAGER/OPERATOR/VIEWER, statut ACTIVE/REVOKED, dates d'adhésion/révocation, version de concurrence.

**Responsabilité :** propriété des données, adhésions et permissions centralisées.

**Invariants :** unicité du couple organisation-utilisateur ; toute opération privée contrôle utilisateur authentifié, adhésion et permission ; une organisation active conserve au moins un OWNER actif. Le retrait ou changement de rôle d'un OWNER coordonne l'organisation afin d'éviter deux retraits concurrents du dernier propriétaire. Le MVP peut créer uniquement des adhésions OWNER sans disperser des comparaisons de rôles.

**Relations :** userAccountId vers identity par UUID. Pas de collection d'ordres, produits ou campagnes dans Organization.

**Transitions :** adhésion ACTIVE vers REVOKED ; une réactivation exige une opération autorisée. Les politiques d'archivage complet d'organisation et d'invitation ne sont pas nécessaires au premier slice de catalogue.

## catalog

### Product — racine ; ProductVariant — entité du produit

**Product :** id, organizationId, nom, description, statut ACTIVE/ARCHIVED, dates, version. primaryMediaAssetId reste une cible future non implémentée dans la tranche catalogue, sans image ni upload.

**ProductVariant :** id, organizationId, productId, SKU facultatif, attributs flexibles JSONB, clé canonique de combinaison, unité vendue, estimations unitaires de poids/volume facultatives, indicateur actif.

**Responsabilité :** décrire un catalogue réutilisable ; Product ne porte aucun prix de campagne autoritaire.

**Invariants :** au moins une variante active pour un produit ACTIVE ; variante DEFAULT pour un produit sans options ; combinaison d'attributs unique au sein du produit ; SKU unique dans l'organisation lorsqu'il existe. Le retrait de la dernière variante est interdit. Les références déjà commercialisées sont archivées plutôt que supprimées. Les variantes demeurent dans l'agrégat Product.

**Attributs :** color, size, shoeSize, capacity peuvent varier sans colonne par famille de produits. Les clés, types, systèmes de tailles et unités sont validés ; un JSON syntaxiquement valide ne suffit pas. Les montants, devises, identifiants et statuts restent des données structurées distinctes. Une clé canonique évite les variantes doublonnées malgré un ordre différent des attributs.

**Relations :** productId interne au module ; organizationId scalaire avec FK SQL, sans cascade intermodule. La référence vers media reste future. Les poids catalogue ne remplacent pas les mesures réelles.

**Tranche livrée :** attributs textuels normalisés, clé DEFAULT ou v1:SHA-256, unités UNIT/PAIR/SET/PACK, SKU normalisé unique par organisation. Les écritures de variantes contrôlent et incrémentent la version du produit. Produit archivé lisible mais non éditable, aucun hard delete. Voir [API catalogue](catalog-api.md) et [ADR-0007](../adr/0007-catalog-variant-identity-and-concurrency.md).

**Snapshots :** les commandes copient les descriptions, SKU et attributs nécessaires ; une modification du catalogue ne les modifie jamais.

## grouping

### GroupingCampaign — racine

**Données :** id, organizationId, publicIdentifier UUID v4 distinct et stable, slug dérivé du titre, titre, description, statut, devise XOF, closesAt facultatif, openedAt/closedAt/cancelledAt, motif d'annulation privé, createdAt/updatedAt, version. Les transitions fournisseur, transport et arrivée sont implémentées ; voir le dossier logistique en fin de document.

**Conditions de transport :** originCountry facultatif, transportMode AIR/SEA/ROAD/OTHER, méthode PER_KG/PER_CBM/PER_ITEM/FIXED, mode AFTER_FINAL_ASSESSMENT/ESTIMATED_AT_CHECKOUT, tarif, devise XOF et HALF_UP. Un bloc peut manquer en DRAFT mais doit être complet à l'ouverture, puis reste figé. Les vocabulaires et ShippingTerms sont exposés dans shipping::api, sans calcul ni évaluation de transport dans cette tranche. Les futures commandes conserveront les conditions acceptées.

**Responsabilité :** piloter le cycle de groupage et autoriser les commandes. La campagne n'englobe ni toutes les commandes ni leurs paiements.

**Invariants :** les offres appartiennent à la même organisation ; la référence publique n'autorise que la consultation publique prévue ; les nouveaux placements exigent OPEN et le respect des échéances. L'admission, la clôture et les changements de prix sont arbitrés de façon cohérente.

**Parcours nominal :**
DRAFT → OPEN → CLOSED → SUPPLIER_ORDERED → IN_TRANSIT → ARRIVED → DISTRIBUTING → COMPLETED.

**Gardes :** CLOSED interdit les nouvelles commandes ; SUPPLIER_ORDERED matérialise le périmètre pris en charge après résolution des paiements concernés ; ARRIVED n'autorise pas automatiquement un retrait ; COMPLETED exige la résolution des commandes et régularisations concernées. CANCELLED est une branche explicite dont les stades autorisés et conséquences financières restent une politique ouverte, pas une transition universelle.

**Tranche implémentée :** DRAFT → OPEN, OPEN → CLOSED et DRAFT/OPEN/CLOSED → CANCELLED uniquement. Une campagne déjà ouverte reste publique après fermeture/annulation si son organisation est ACTIVE. orderable exige OPEN, une offre utilisable et une échéance absente ou strictement future ; aucun scheduler. Titre, description et closesAt restent éditables en DRAFT/OPEN, les prolongations sont journalisées. Voir [API groupage](grouping-api.md).

### GroupingOffer — racine distincte

**Données :** id, organizationId, campaignId, productVariantId, libellé commercial, prix unitaire courant, devise, révision de prix, actif, dates, version.

**Responsabilité :** vendre exactement une variante dans une campagne.

**Invariants :** unicité campagne-variante ; cohérence de propriétaire et devise ; prix non négatif. Le changement de prix est autorisé pendant OPEN pour les commandes futures. Il incrémente la révision de prix et produit une trace auditable avec acteur, ancien/nouveau montant, devise et instant. Aucun changement ne réécrit une commande existante. La variante d'une offre déjà commercialisée n'est pas remplacée silencieusement.

**Checkout :** vérifier côté serveur campagne, offre, organisation, devise, quantité et prix/révision observés. Un prix affiché devenu différent produit PRICE_CONFLICT sans commande ni obligation de paiement. Une version incohérente exige une revalidation explicite ; aucune substitution silencieuse. L'acceptation du prix et le placement constituent une seule décision atomique. Voir [ADR-0005](../adr/0005-offer-price-changes.md).

**Relations :** campaignId interne ; productVariantId scalaire vers catalog. CatalogVariants fournit les lectures publiques minimales et la disponibilité par organisation. Pas de dépendance vers ordering.

**Coordination livrée :** les offres restent des racines distinctes avec leur version ; toute écriture contrôle aussi la version de campagne dans une transaction commune. grouping_changes conserve atomiquement les changements de prix et d'échéance avec acteur. Ce journal interne ne constitue pas une implémentation de AuditLog ; voir [ADR-0008](../adr/0008-grouping-publication-and-coordination.md).

## ordering

### Customer — racine de contact

**Données :** id, organizationId, firstName, lastName, téléphone normalisé E.164 obligatoire, email facultatif, dates, version.

**Responsabilité :** conserver le contact utile aux commandes de l'organisation.

**Invariants :** aucun compte obligatoire ; téléphone non unique ; aucune fusion automatique ou divulgation d'historique sur un téléphone non vérifié. Les contacts de deux organisations ne sont pas automatiquement rapprochés.

**Relations :** Order référence Customer dans la même organisation. Aucune association implicite à UserAccount.

### Order — racine ; OrderItem — entité possédée

**Order :** id, organizationId, campaignId, customerId, numéro commercial, OrderStatus, FulfillmentStatus, devise, coordonnées client snapshotées, sous-total marchandises, frais et règle de frais snapshotés, conditions de transport snapshotées, estimation facultative, transport exigible initialement, total initial exigible, clé d'idempotence et empreinte du contenu du checkout, échéance de paiement, empreinte du jeton privé et dates d'expiration/révocation, jalons, motif d'annulation, version.

**OrderItem :** id, organizationId, orderId, numéro de ligne, groupingOfferId, identifiants d'origine du produit et de la variante, libellé/SKU/attributs/unité snapshotés, quantité, prix unitaire et total de ligne snapshotés. La devise est celle de la commande.

**Responsabilités :** préserver l'achat accepté, piloter les états commercial/logistique et coordonner les obligations de paiement à partir des faits de commande et de transport. OrderItem n'est pas modifiable indépendamment.

**Invariants :** une organisation, campagne et devise ; au moins une ligne ; quantités positives ; offres de la bonne campagne ; calcul serveur de tous les montants ; numéro de ligne unique ; idempotence sans double commande. Les conditions commerciales sont immuables après placement. Le total initial est marchandises + frais + transport initial exigible, avec frais fixes XOF configurables snapshotés (quatrième tranche). Un prix commercial initial n'est jamais remplacé par le nouveau prix d'une offre.

**OrderStatus :**
- placement réussi → PENDING_PAYMENT ;
- PENDING_PAYMENT → CONFIRMED après vérification et affectation du paiement initial attendu ;
- PENDING_PAYMENT → EXPIRED à l'échéance selon la politique de réconciliation à préciser ;
- PENDING_PAYMENT ou CONFIRMED → CANCELLED seulement si la politique d'annulation le permet ;
- CONFIRMED → COMPLETED après remise complète et résolution financière ;
- aucun paiement tardif ne fait sortir automatiquement de EXPIRED ou CANCELLED.

Le traitement éventuel d'une commande à montant initial nul reste à décider si ce cas est admis.

**FulfillmentStatus :**
- NOT_READY → READY_FOR_PICKUP après disponibilité, préparation et satisfaction des conditions de règlement ;
- READY_FOR_PICKUP → PICKED_UP lors d'une remise complète autorisée ;
- NOT_READY/READY_FOR_PICKUP → CANCELLED lorsque la commande est annulée ;
- une nouvelle information bloquante avant remise peut ramener READY_FOR_PICKUP à NOT_READY avec motif et audit ;
- aucun événement financier ne simule une remise ni n'efface une remise passée.

Les motifs d'indisponibilité sont distincts de OrderStatus. Les corrections après remise ne réécrivent pas le fait PICKED_UP.

**Suivi privé :** jeton aléatoire de forte entropie, conservé uniquement sous forme d'empreinte/hash ; expiration, révocation et limitation des essais. Référence publique, numéro de commande et clé d'idempotence ne valent pas autorisation.

**Relations :** Customer et lignes internes ; campagne/offres par UUID ; appels aux APIs de shipping et payment. Pas d'association JPA intermodule.

## payment

### PaymentRequest — racine : obligation de paiement

**Données :** id, organizationId, orderId UUID, purpose INITIAL_ORDER/SHIPPING_BALANCE, références UUID facultatives d'évaluation/calcul transport, référence métier idempotente, montant attendu et devise, ventilation marchandises/frais/transport, état d'acceptation, échéance facultative, settledPaymentId facultatif, dates, version.

**Responsabilité :** exprimer une obligation précise transmise par ordering et rattacher ses tentatives. PaymentRequest ne calcule ni prix catalogue ni transport.

**Invariants :** montant et ventilation cohérents et immuables ; référence métier unique par obligation/révision ; devise XOF ; au plus une tentative retenue comme règlement. Plusieurs succès réels restent enregistrables. Une correction financière crée une obligation d'ajustement ou une restitution, pas une modification d'une demande déjà réglée.

**États d'acceptation :** OPEN → CLOSED lors du règlement retenu ; OPEN → CANCELLED ou SUPERSEDED sur décision explicite. Ces états ne sont pas des preuves de disparition d'un mouvement financier. Un succès reçu après fermeture reste à traiter.

### Payment — racine : tentative prestataire

**Données :** id, organizationId, paymentRequestId, fournisseur, compte marchand, référence interne, identifiant de transaction externe facultatif, clé d'idempotence prestataire, montant/devise attendus, montant/devise confirmés facultatifs, statut, informations de réconciliation, dates, version.

**Invariants :** vérifier référence interne, prestataire, transaction, état, montant, devise, commande et purpose avant SUCCEEDED ; ne jamais faire confiance à une confirmation client. Un timeout ne prouve pas l'échec. L'affectation d'un règlement coordonne PaymentRequest ; deux succès réels ne sont pas interdits par une contrainte empêchant leur enregistrement.

**PaymentStatus :** CREATED → PENDING → SUCCEEDED/FAILED/CANCELLED/EXPIRED. Certains prestataires peuvent confirmer directement depuis CREATED. Une confirmation de succès contradictoire avec un échec/une expiration précédents exige une vérification explicite du fait prestataire. SUCCEEDED ne régresse jamais vers PENDING, FAILED, CANCELLED ou EXPIRED. REFUNDED n'appartient pas à cet enum.

**Succès tardif/excédentaire :** enregistrer le fait, signaler la réconciliation et publier l'événement pertinent ; payment ne décide jamais de réactiver une commande.

### Refund — racine : restitution du crédit transport (V1 implémentée)

**Données :** id, organizationId, orderId, calculationId, montant XOF, purpose SHIPPING_CREDIT, méthode/provenance, état CREATED/PENDING/SUCCEEDED/FAILED/CANCELLED/REVIEW_REQUIRED, références métier/prestataire et clé d'idempotence, destinataire snapshoté, preuve manuelle éventuelle, acteur/dates et version. Le crédit provenant potentiellement de plusieurs encaissements, aucun paymentId unique ne lui est artificiellement attribué.

**Responsabilité :** suivre une restitution sans effacer le paiement SUCCEEDED.

**Invariants :** crédit complet strictement positif, calculé côté serveur après PICKED_UP ; coordination concurrente sur la commande ; une opération logique par commande en V1 ; succès immuable et vérification de l'issue prestataire. Une demande de remboursement ne prouve pas sa réalisation. La provenance manuelle atteste une restitution externe et ne prétend pas à une vérification provider. Les remboursements génériques d'un paiement individuel restent hors de cette tranche.

### PaymentWebhookEvent — réception technique indépendante

**Données :** id, fournisseur/compte marchand, identifiant externe facultatif, organizationId/paymentId facultatifs tant que non résolus côté serveur, résultat d'authenticité, empreinte et données strictement nécessaires, statut de traitement, date de réception, tentatives, prochain essai, dernier code d'erreur.

**Traitement :** RECEIVED → PROCESSED/IGNORED/REVIEW_REQUIRED/RETRYABLE_FAILURE ; une reprise contrôlée traite un échec réessayable. Le statut de réception n'est pas PaymentStatus.

**Invariants :** identifiant externe unique dans son périmètre fournisseur lorsqu'il existe ; référence inconnue conservée pour investigation ; aucune organisation client considérée fiable ; ni secrets ni payload intégral systématique dans les logs. Sans identifiant externe fiable, l'empreinte aide à reconnaître des doublons mais l'idempotence métier reste nécessaire. La conservation des données brutes sensibles est limitée et protégée.

**Frontière :** payment n'importe aucun contrat de ordering ou shipping. Les UUID orderId/assessmentId sont des références de corrélation et éventuellement des FK SQL scalaires.

## shipping

### ShippingAssessment — racine unique par commande

**Données :** id, organizationId, orderId UUID, état de calcul, conditions de tarification snapshotées, référence du calcul définitif courant facultative, dates, version.

**Responsabilité :** calculer le transport à partir des conditions et quantités propres à une commande. shipping ne crée pas de PaymentRequest, ne consomme pas PaymentSucceeded et ne connaît pas l'encaissement net.

**Invariants :** une seule évaluation par commande ; estimation distincte du réel ; observations et calculs historiques conservés ; aucune répartition de fret collectif dans le MVP.

**CalculationStatus V1 :** PENDING → FINALIZED ; toute correction conserve l'état FINALIZED et ajoute une révision. Pas d'état financier ni d'annulation implémentée ici.

### ShippingMeasurement — observation immuable

**Données :** id, organizationId, assessmentId, poids réel, poids facturable, volume ou dimensions facultatifs selon la méthode, source, occurredAt facultatif et recordedAt serveur, acteur UUID authentifié obligatoire, supersedesMeasurementId facultatif, motif de correction.

**Responsabilité :** tracer ce qui a été mesuré. Une correction ajoute une observation. Le poids ne doit pas être arrondi avant le calcul ; unités et précision source sont conservées.

### ShippingCalculation — résultat immuable et révisé

**Données :** id, organizationId, assessmentId, numéro de révision, kind FINAL (estimation historique conservée dans Order), measurementId facultatif, méthode/tarif/devise, quantité facturable/unité, règle d'arrondi, montant, date, référence du calcul remplacé et motif facultatifs.

**Invariants :** révision unique dans l'évaluation ; montant CALCULATED reproductible ou PROVIDER_QUOTED tracé explicitement ; un nouveau FINAL ne remplace pas physiquement l'ancien ; pas de mesure fictive pour FIXED/PER_ITEM. Le calcul courant est désigné explicitement.

**Méthodes :** PER_KG = poids facturable en kg ; PER_CBM = volume facturable en m³ ; PER_ITEM = somme des quantités d'articles ; FIXED = montant par commande. Les règles V1 sont détaillées dans [shipping-v1.md](shipping-v1.md), avec diviseur volumétrique optionnel et montant communiqué sans formule.

**CollectionMode :** AFTER_FINAL_ASSESSMENT ou ESTIMATED_AT_CHECKOUT. Ce paramètre décrit les conditions convenues ; son exécution financière appartient à ordering/payment. ESTIMATED_AT_CHECKOUT exige une estimation calculable avant placement ; une estimation inconnue ne permet pas de substituer silencieusement zéro.

### Situation financière du transport — responsabilité de ordering

Elle est dérivée du dernier calcul accepté et des mouvements affectés au transport. Avant calcul final : non déterminée. Après finalisation : BALANCE_DUE, SETTLED ou CREDIT_DUE selon le signe de l'écart. Ces valeurs ne sont pas des états de ShippingAssessment.

Écart = prix final du transport − encaissement net affecté au transport.
Encaissement net = règlements affectés − remboursements affectés.

La ventilation du paiement initial est incluse. Un deuxième encaissement non affecté reste un excédent à réconcilier et ne doit pas être silencieusement imputé à une autre obligation. ordering crée les obligations supplémentaires de manière idempotente, sans redemander les sommes déjà couvertes par des obligations actives.

## media

### MediaAsset — racine

**Données :** id, organizationId, clé de stockage objet stable, nom original, type, taille, empreinte facultative, statut et dates.

**Responsabilité :** stockage et accès autorisé aux fichiers. Le catalogue conserve les références d'utilisation.

**Invariants :** aucun binaire produit dans PostgreSQL ; une URL signée temporaire n'est pas une référence historique ; ne pas supprimer un média encore nécessaire à un objet commercial archivé.

**Cycle :** mise à disposition puis archivage/retrait contrôlé. Les états techniques d'upload seront définis avec le slice média.

## audit

### AuditLog — enregistrement append-only

**Données :** id, organizationId facultatif pour action plateforme, acteur/type, action, sujet/type/id, occurredAt, correlationId/sourceEventId facultatifs, détails filtrés.

**Responsabilité :** expliquer les actions sensibles, notamment les changements de prix, autorisations et corrections.

**Invariants :** pas de modification ordinaire d'une trace ; déduplication des événements rejoués ; aucun secret ni données personnelles inutiles. AuditLog ne remplace pas l'historique financier ni la publication persistante.

## notification et shared

notification consommera les faits publics et gérera l'idempotence de ses envois ; aucun nouvel agrégat métier n'est requis dans cette étape. shared contient uniquement le contrat technique d'erreurs, sa traduction HTTP et une horloge injectable, justifiés par ADR-0006. Aucun déplacement d'entités ou d'événements métier pour masquer un cycle.

## Relations et snapshots récapitulatifs

- Organization 1—N OrganizationMember ; Product 1—N ProductVariant.
- Campaign 1—N Offer, avec racines séparées ; Offer référence une variante.
- Customer 1—N Order ; Order 1—N OrderItem, au moins une ligne.
- Order 1—0..1 ShippingAssessment pendant initialisation ; unicité stricte, avec reprise idempotente pour obtenir l'évaluation requise.
- Assessment 1—N observations/calculs historiques ; au plus un calcul final courant.
- Order 1—N PaymentRequest ; Request 1—N Payment ; Order 1—0..1 Refund SHIPPING_CREDIT dans cette V1.
- Les cardinalités minimales des paiements sont zéro avant création/encaissement.
- Les FK SQL intermodules autorisées ne changent pas le graphe des imports Java.

Snapshots indispensables : descriptions/SKU/attributs/unité/quantité/prix de ligne ; contact et frais de commande ; conditions et estimation de transport ; mesures et révisions finales ; montant/devise/ventilation de chaque obligation et remboursement. Les projections de statut peuvent évoluer, les faits financiers ne sont jamais réécrits.

## Points ouverts et limites

Les questions de politique non résolues sont centralisées dans [business-rules.md](business-rules.md#questions-ouvertes-et-blocage-par-slice). Elles ne réouvrent pas les décisions ci-dessus. Les guards détaillés d'annulation, échéance, disponibilité et tarification ne doivent pas être inventés pendant l'implémentation.

Hors MVP : multi-devise, panier multicampagne, paiement fractionné volontaire, portefeuille client, retrait partiel, multi-entrepôt, répartition collective du fret et gestion fournisseur complète. La détection des doublons financiers, le traitement des paiements tardifs et la trace des remboursements restent nécessaires.

## Précisions d'implémentation de la quatrième tranche

Customer, Order et OrderItem sont désormais persistés. Customer conserve firstName,
lastName, phoneE164 obligatoire et email facultatif ; il porte @Version et reste une
racine de contact sans UserAccount. Une création par checkout, aucune fusion automatique.

Order porte @Version et possède ses lignes (cascade PERSIST interne seulement).
OrderItem conserve en plus campaignId pour les FK de cohérence, productNameSnapshot,
productDescriptionSnapshot, commercialLabelSnapshot, variantAttributesSnapshot JSONB,
skuSnapshot, saleUnitSnapshot, estimatedUnitWeightKgSnapshot,
estimatedUnitVolumeM3Snapshot et priceRevisionSnapshot. Les quantités, prix et
total de ligne restent historiques ; la devise provient de la racine.

CustomerContactSnapshot, platformFeeRuleSnapshot et shippingTermsSnapshot sont
des valeurs JSONB indépendantes des sources mutables. Le motif d'annulation et
ses workflows cibles ne sont pas implémentés dans cette tranche. Aucun statut
commercial/logistique ne change après placement.

Voir [API commandes](ordering-api.md) et [ADR-0009](../adr/0009-checkout-admission-idempotency-and-tracking.md).

## Cinquième tranche implémentée (20 septembre 2026)

PaymentRequest, Payment et PaymentWebhookEvent sont persistés par 007-payments.
La demande INITIAL_ORDER est créée dans la transaction de checkout et reprend
ses montants/ventilation/échéance sans recalcul. Le port PaymentProvider n'a
qu'une implémentation de test. SHIPPING_BALANCE/SUPERSEDED restent réservés ;
À cette date, PaymentRefund et transport persistant n'étaient pas implémentés ; les tranches d'octobre décrites ci-dessous ajoutent transport et Refund SHIPPING_CREDIT.

Les demandes ont actuellement une échéance obligatoire et CLOSED signifie
réglée avec settledPaymentId. Payment ajoute initiationKey pour le rejeu client,
distincte de providerIdempotencyKey. Les réceptions stockent une observation
financière normalisée en JSONB, une empreinte et une clé technique de déduplication.

L'affectation se fait à l'heure de validation Groupia, avant l'échéance stricte.
Succès tardifs/doubles restent SUCCEEDED avec réconciliation, sans événement
normal de règlement supplémentaire. OrderExpiration ferme l'acceptation et
expire atomiquement les commandes dues non réglées. Une demande déjà réglée
attend sa confirmation asynchrone sans être expirée entretemps.

Détails concrets, transitions et exceptions documentées :
[ADR-0010](../adr/0010-financial-core-and-settlement.md),
[API](payment-api.md), [reprise](payment-operations.md).

## Dossier logistique externe et timeline — V1 octobre 2026

Shipment est l'agrégat shipping unique par boutique/groupage, enrichissable dès SUPPLIER_ORDERED. Il conserve mode de transport, snapshots privés du prestataire (nom/contact), référence/note, ETA courante, dates réelles facultatives, confirmations serveur distinctes et version.
ShipmentFact est son journal append-only : type, source externe, acteur authentifié, occurredAt nullable, recordedAt serveur, révision, note et snapshots. Une révision d'ETA conserve ancienne/nouvelle valeur. Les corrections ajoutent une information.
V1 : prestataire externe → information → gérante → Groupia. V2 future : prestataire inscrit → information directe → Groupia ; un rattachement futur ne remplacera pas les snapshots. Aucun compte prestataire n'existe en V1.
**Donnée externe inconnue != zéro != maintenant.** ARRIVED ne déclenche ni retrait ni calcul ni paiement.
Voir [contrat logistique](groupage-logistics.md) pour migration historique et [ADR-0015](../adr/0015-external-logistics-dossier.md) pour les choix.

## Shipping V1 implémenté

Voir [Shipping V1](shipping-v1.md) et [ADR-0016](../adr/0016-order-shipping-assessments.md).
Les soldes, états de retrait et remboursements ont ensuite été livrés par Shipping Finance V1, Distribution V1 et Credit Resolution V1 (voir ci-dessous).

## Shipping Finance V1

ShippingSettlementView est dérivée par commande : FINAL courant moins transport initial réellement payé et PaymentRequest SHIPPING_BALANCE allouées. États NOT_FINALIZED, BALANCE_DUE, SETTLED, CREDIT_DUE ; tentative en cours séparée. Aucune table monétaire dupliquée. L’obligation liée au calcul courant est positive, sans frais, avec expiresAt nullable pour SHIPPING_BALANCE uniquement. Historique des paiements et révisions conservé. Voir [shipping-balance.md](shipping-balance.md).

## Distribution V1

Order conserve readyForPickupAt courant, pickedUpAt définitif et completedAt commercial. Le journal order_fulfillment_events garde chaque préparation, révocation motivée et remise, avec acteur authentifié et date serveur. Une commande retirée avec CREDIT_DUE reste CONFIRMED. Aucun nouvel agrégat financier ; voir [distribution-v1.md](distribution-v1.md) et [ADR-0018](../adr/0018-distribution-v1.md).

## Credit Resolution V1

Refund est un agrégat payment distinct des encaissements : UUID boutique/commande/calcul, montant XOF, purpose SHIPPING_CREDIT, méthode et provenance, statut, références métier/provider/idempotence, destinataire snapshoté, acteur/date serveur, preuve temporelle, réconciliation et version. Les receipts normalisés des webhooks sortants sont persistés séparément ; aucun lien JPA cross-module. La migration 019 protège unicité, snapshots et succès immuables.

ShippingSettlementView expose totalShippingPaidAmount brut historique, successfulShippingCreditRefunds et netShippingPaid. RefundSummary expose uniquement montant/moyen/statut/dates/provenance au suivi client ; RefundView réserve l'identifiant et la référence externe masquée à la gérante. ShippingCreditRefundSettled est un événement durable consommé par ordering. Voir [modèle détaillé](shipping-credit-resolution.md) et [ADR-0019](../adr/0019-shipping-credit-resolution.md).

## Merchant Settlement V1 (2026-10-06)

Module `settlement` : `MerchantLedgerEntry` (fait append-only, source unique),
`MerchantPayoutDestination` (révision active unique par boutique) et `MerchantPayout`
(réservation et preuve provider, snapshot de destination immuable). Aucun lien JPA
intermodule. Les UUID organization/campaign/order référencent les faits publics.

Ledger : ORDER_COLLECTION, SHIPPING_BALANCE_COLLECTION,
PLATFORM_FUNDED_CUSTOMER_REFUND, MERCHANT_PAYOUT ; CREDIT/DEBIT et XOF exact.
Payout : CREATED, PENDING, REVIEW_REQUIRED réservent ; SUCCEEDED débite une fois ;
FAILED/CANCELLED libèrent après preuve serveur acceptable. Aucun succès manuel.
MANUAL_EXTERNAL ne produit aucun débit marchand.

[Modèle, calculs et historique 019→020](merchant-settlement-v1.md).

## Subscription V1 (2026-10)

Le module `subscription` possède `OrganizationSubscription`, `SubscriptionBillingContact`
et l'historique append-only `SubscriptionPeriod`. Le trial est provisionné une fois
à la création de la boutique ; les organisations existant au cutover reçoivent un
trial dont la provenance est `LEGACY_MIGRATION_TRIAL`. Sa fin est calculée par mois
calendaire dans le fuseau de la boutique.

L'entitlement est une projection serveur du trial et des périodes payées vérifiées.
Il ne modifie pas les commandes déjà engagées ni les obligations financières.
L'application du contrôle est limitée à la création et l'ouverture de nouveaux
groupages. La migration 021 crée le schéma et le backfill legacy idempotent.

Le provider d'abonnement est indisponible par défaut. La configuration d'une
subscription provider ne crée pas de période payée ; aucune preuve webhook
Bictorys vérifiée ne permet encore l'activation automatique.

[Contrat Subscription V1](subscription-v1.md) et
[ADR-0021](../adr/0021-subscription-entitlements.md).
