# Modèle métier Groupia V1

Statut : modèle validé le 17 septembre 2026, avec précisions d'architecture et points ouverts explicitement identifiés ci-dessous. Ce document décrit la cible. Les tranches identity/organization (18 septembre 2026) et Product/ProductVariant (20 septembre 2026) sont implémentées ; les autres entités et workflows restent non implémentés. Voir [contrat de cette tranche](identity-organization-api.md).

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

**Données :** id, organizationId, référence publique, titre, description, statut, devise XOF, transportMode AIR/SEA/ROAD/OTHER, dates d'ouverture/clôture et jalons opérationnels, motif/date d'annulation, version.

**Conditions de transport proposées :** méthode PER_KG/PER_CBM/PER_ITEM/FIXED, mode AFTER_FINAL_ASSESSMENT/ESTIMATED_AT_CHECKOUT, tarif, devise, unité facturable et règles applicables. Les contrats de calcul sont publics dans shipping ; les conditions acceptées sont copiées dans chaque commande.

**Responsabilité :** piloter le cycle de groupage et autoriser les commandes. La campagne n'englobe ni toutes les commandes ni leurs paiements.

**Invariants :** les offres appartiennent à la même organisation ; la référence publique n'autorise que la consultation publique prévue ; les nouveaux placements exigent OPEN et le respect des échéances. L'admission, la clôture et les changements de prix sont arbitrés de façon cohérente.

**Parcours nominal :**
DRAFT → OPEN → CLOSED → SUPPLIER_ORDERED → IN_TRANSIT → ARRIVED → DISTRIBUTING → COMPLETED.

**Gardes :** CLOSED interdit les nouvelles commandes ; SUPPLIER_ORDERED matérialise le périmètre pris en charge après résolution des paiements concernés ; ARRIVED n'autorise pas automatiquement un retrait ; COMPLETED exige la résolution des commandes et régularisations concernées. CANCELLED est une branche explicite dont les stades autorisés et conséquences financières restent une politique ouverte, pas une transition universelle.

### GroupingOffer — racine distincte

**Données :** id, organizationId, campaignId, productVariantId, libellé commercial, prix unitaire courant, devise, révision de prix, actif, dates, version.

**Responsabilité :** vendre exactement une variante dans une campagne.

**Invariants :** unicité campagne-variante ; cohérence de propriétaire et devise ; prix non négatif. Le changement de prix est autorisé pendant OPEN pour les commandes futures. Il incrémente la révision de prix et produit une trace auditable avec acteur, ancien/nouveau montant, devise et instant. Aucun changement ne réécrit une commande existante. La variante d'une offre déjà commercialisée n'est pas remplacée silencieusement.

**Checkout :** vérifier côté serveur campagne, offre, organisation, devise, quantité et prix/révision observés. Un prix affiché devenu différent produit PRICE_CONFLICT sans commande ni obligation de paiement. Une version incohérente exige une revalidation explicite ; aucune substitution silencieuse. L'acceptation du prix et le placement constituent une seule décision atomique. Voir [ADR-0005](../adr/0005-offer-price-changes.md).

**Relations :** campaignId interne ; productVariantId vers catalog. Pas de dépendance vers ordering.

## ordering

### Customer — racine de contact

**Données :** id, organizationId, nom affiché, téléphone normalisé E.164 facultatif, email facultatif, dates, version.

**Responsabilité :** conserver le contact utile aux commandes de l'organisation.

**Invariants :** aucun compte obligatoire ; téléphone non unique ; aucune fusion automatique ou divulgation d'historique sur un téléphone non vérifié. Les contacts de deux organisations ne sont pas automatiquement rapprochés.

**Relations :** Order référence Customer dans la même organisation. Aucune association implicite à UserAccount.

### Order — racine ; OrderItem — entité possédée

**Order :** id, organizationId, campaignId, customerId, numéro commercial, OrderStatus, FulfillmentStatus, devise, coordonnées client snapshotées, sous-total marchandises, frais et règle de frais snapshotés, conditions de transport snapshotées, estimation facultative, transport exigible initialement, total initial exigible, clé d'idempotence et empreinte du contenu du checkout, échéance de paiement, empreinte du jeton privé et dates d'expiration/révocation, jalons, motif d'annulation, version.

**OrderItem :** id, organizationId, orderId, numéro de ligne, groupingOfferId, identifiants d'origine du produit et de la variante, libellé/SKU/attributs/unité snapshotés, quantité, prix unitaire et total de ligne snapshotés. La devise est celle de la commande.

**Responsabilités :** préserver l'achat accepté, piloter les états commercial/logistique et coordonner les obligations de paiement à partir des faits de commande et de transport. OrderItem n'est pas modifiable indépendamment.

**Invariants :** une organisation, campagne et devise ; au moins une ligne ; quantités positives ; offres de la bonne campagne ; calcul serveur de tous les montants ; numéro de ligne unique ; idempotence sans double commande. Les conditions commerciales sont immuables après placement. Le total initial est marchandises + frais + transport initial exigible, selon la politique de frais encore à définir. Un prix commercial initial n'est jamais remplacé par le nouveau prix d'une offre.

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

### PaymentRefund — racine : restitution

**Données :** id, organizationId, paymentId, montant/devise, ventilation restituée, motif, état REQUESTED/PENDING/SUCCEEDED/FAILED, clé d'idempotence, référence prestataire ou preuve d'exécution manuelle, dates, version.

**Responsabilité :** suivre une restitution sans effacer le paiement SUCCEEDED.

**Invariants :** montants positifs ; remboursements réussis et en cours réservés ne dépassant pas l'encaissement ; coordination concurrente sur le paiement ; reprise idempotente et vérification de l'issue prestataire. Une demande de remboursement ne prouve pas sa réalisation. La politique d'éligibilité vient de la décision commerciale, pas du prestataire.

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

**CalculationStatus :** ESTIMATED → FINALIZED ; une correction ajoute une révision tout en conservant les anciennes. CANCELLED arrête l'évaluation selon la décision commerciale sans supprimer son histoire. Une estimation peut être inconnue : aucun montant fictif n'est créé. Une méthode dont le montant définitif est déjà déterminable peut être finalisée sans attendre une mesure fictive.

### ShippingMeasurement — observation immuable

**Données :** id, organizationId, assessmentId, poids réel et/ou volume réel selon la méthode, source, measuredAt, acteur UUID facultatif, supersedesMeasurementId facultatif, motif de correction.

**Responsabilité :** tracer ce qui a été mesuré. Une correction ajoute une observation. Le poids ne doit pas être arrondi avant le calcul ; unités et précision source sont conservées.

### ShippingCalculation — résultat immuable et révisé

**Données :** id, organizationId, assessmentId, numéro de révision, kind ESTIMATE/FINAL, measurementId facultatif, méthode/tarif/devise, quantité facturable/unité, règle d'arrondi, montant, date, référence du calcul remplacé et motif facultatifs.

**Invariants :** révision unique dans l'évaluation ; montant reproductible à partir des données conservées ; un nouveau FINAL ne remplace pas physiquement l'ancien ; pas de mesure fictive pour FIXED/PER_ITEM. Le calcul courant est désigné explicitement.

**Méthodes :** PER_KG = poids facturable en kg ; PER_CBM = volume facturable en m³ ; PER_ITEM = somme des quantités d'articles ; FIXED = montant par commande. La règle définissant le poids/volume facturable et les tarifs commerciaux restent à préciser.

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
- Order 1—N PaymentRequest ; Request 1—N Payment ; Payment 1—N PaymentRefund.
- Les cardinalités minimales des paiements sont zéro avant création/encaissement.
- Les FK SQL intermodules autorisées ne changent pas le graphe des imports Java.

Snapshots indispensables : descriptions/SKU/attributs/unité/quantité/prix de ligne ; contact et frais de commande ; conditions et estimation de transport ; mesures et révisions finales ; montant/devise/ventilation de chaque obligation et remboursement. Les projections de statut peuvent évoluer, les faits financiers ne sont jamais réécrits.

## Points ouverts et limites

Les questions de politique non résolues sont centralisées dans [business-rules.md](business-rules.md#questions-ouvertes-et-blocage-par-slice). Elles ne réouvrent pas les décisions ci-dessus. Les guards détaillés d'annulation, échéance, disponibilité et tarification ne doivent pas être inventés pendant l'implémentation.

Hors MVP : multi-devise, panier multicampagne, paiement fractionné volontaire, portefeuille client, retrait partiel, multi-entrepôt, répartition collective du fret et gestion fournisseur complète. La détection des doublons financiers, le traitement des paiements tardifs et la trace des remboursements restent nécessaires.
