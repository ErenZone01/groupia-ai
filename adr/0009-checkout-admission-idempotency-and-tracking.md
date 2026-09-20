# ADR-0009 — Admission atomique, rejeu et suivi privé du checkout

- Statut : décision d'implémentation de la quatrième tranche
- Date : 2026-09-20
- Complète ADR-0003, ADR-0004 et ADR-0005.

## Transaction d'admission

Ordering ouvre une transaction PostgreSQL READ_COMMITTED, limitée à 15 secondes.
CheckoutAdmission dans grouping exige une transaction existante (MANDATORY) et
verrouille la ligne campagne par SELECT FOR UPDATE. Le verrou est conservé jusqu'au
commit de Customer, Order et ses OrderItem. Toutes les modifications de campagne
et d'offre existantes écrivent déjà la racine campagne dans leur transaction
(ADR-0008) : elles doivent attendre ce verrou avant de pouvoir valider leur commit.

Si fermeture/prix committent d'abord, le checkout voit leur nouvel état et refuse
l'admission/le consentement périmé. Si le checkout obtient le verrou d'abord, son
admission précède ces modifications. Le test de concurrence vérifie une attente
réelle dans pg_stat_activity, puis les deux ordres de commit, sur les routes réelles.
L'instant de validation sous verrou est l'instant d'admission ; closesAt est exclusif.
Aucun appel réseau, paiement, notification ou autre effet aval dans la transaction.

Grouping résout l'organisation et le pays, vérifie la disponibilité organisationnelle,
les offres et le catalogue via leurs APIs. La validation du catalogue est une lecture
courante, pas une réservation de stock. Les changements ultérieurs du catalogue
n'affectent pas les snapshots. Une coordination supplémentaire avec des réservations
de stock éventuelles reste hors périmètre.

## Idempotence

Portée : organisation + clé, avec UNIQUE en base. Clé sensible à la casse, strip des
espaces externes, 16 à 128 caractères parmi lettres ASCII, chiffres, point, tiret,
underscore et deux-points. Une UUID aléatoire par tentative logique est recommandée.

Après verrou de campagne, un verrou transactionnel PostgreSQL advisory, dérivé de
SHA-256(organisation + clé) sur 64 bits, sérialise aussi les mêmes clés utilisées
sur deux campagnes de la même organisation. Une collision de hachage ne produit
qu'une sérialisation supplémentaire ; UNIQUE reste la garantie de stockage.
Ordre des verrous constant : campagne, puis clé ; une requête n'acquiert jamais une
seconde campagne.

Empreinte SHA-256 d'un encodage JSON positionnel versionné checkout-v1 contenant
identifiant public de campagne, contact normalisé, lignes triées par UUID d'offre,
quantités, prix attendus normalisés et révisions attendues. Aucun total ou réglage
courant dans l'empreinte. Les offres dupliquées sont refusées ; 1 à 100 lignes,
quantité entière de 1 à 1 000 000. Ces bornes protègent les traitements publics.

Le rejeu vérifie l'empreinte avant toute nouvelle quotation, même si la campagne
a fermé ou si son prix a changé. Un contenu différent renvoie 409.
Le rejeu est disponible tant que l'organisation est active.

## Secret perdu et réponse rejouée

Le secret est constitué de 32 octets SecureRandom, encodés Base64url sans padding.
Seule son empreinte SHA-256 hexadécimale est stockée, avec unicité/index, expiration
et révocation. Il est remis une seule fois, après commit, avec le résultat 201.
Un rejeu 200 renvoie uniquement orderNumber et replayed=true. Il ne restitue ni
secret, ni contact, ni lignes ou montants privés. Cela résout la question ouverte
d'ADR-0004 sans transformer la clé d'idempotence en capacité de suivi.

Conséquence explicite : si la première réponse est perdue, le checkout reste créé,
mais le secret ne peut être reconstruit. La récupération/rotation après preuve
(OTP ou procédure support vérifiée) est reportée ; le client ne doit pas créer
une autre commande avec une nouvelle clé pour contourner une réponse perdue.
Après réception normale, le client conserve le secret de manière confidentielle
et utilise le suivi pour retrouver le résultat commercial complet.

Suivi : GET /api/public/orders/track avec X-Order-Tracking-Token, hors URL.
Réponses privées non mises en cache, aucun jeton/hash dans les DTO de suivi.
Expiration par défaut 180 jours, configurable ; pas de route de renouvellement
ou révocation dans cette tranche, mais métadonnées vérifiées à chaque lecture.
Protection locale : 60 essais/minute/adresse distante, 10 000 fenêtres maximum,
aucune confiance dans X-Forwarded-For. Derrière un proxy, le déploiement doit
appliquer une limitation adaptée au proxy et exclure ce header des journaux.
Cette protection mémoire est par instance, perdue au redémarrage ; pas de Redis.

## Référence, frais et historique

orderNumber = GRP- suivi du compteur PostgreSQL sur au moins 12 chiffres.
Séquence globale, unique, stable ; trous autorisés après rollback. La référence
est lisible mais n'est jamais une autorisation ni un secret.

Frais fixes XOF configurables (développement : 300), fenêtre de paiement de
30 minutes, durée de suivi de 180 jours. Les valeurs appliquées sont snapshotées.
Les frais doivent être entiers, non négatifs, inférieurs à 10^18 XOF ; la fenêtre
de paiement est comprise entre 1 minute et 7 jours, le suivi entre 1 et 730 jours.
Les prix/totaux finaux sont entiers XOF inférieurs à 10^18.

Customer contient prénom, nom, téléphone obligatoire E.164 et email facultatif,
sans fusion ni unicité téléphonique. Le pays de l'organisation sert au numéro
national. La normalisation reprend libphonenumber, déjà présent pour identity,
dans l'adaptateur ordering sans dépendre des internes identity.

OrderItem conserve aussi campaignId pour les FK composées imposant la même
organisation/campagne que la commande et l'offre. Les identifiants produit/variante
restent des UUID scalaires ; aucune cascade SQL ou JPA intermodule.
Les attributs, descriptions, SKU, quantités, estimations unitaires, prix/révision,
contact, frais/règle, conditions de transport et estimation sont des snapshots.
Aucune opération d'édition/suppression de commande ou de ligne n'est exposée.

## Estimation et frontières

shipping expose un calculateur pur PER_KG/PER_CBM/PER_ITEM/FIXED. Il additionne
les quantités décimales avant multiplication puis arrondit une seule fois, XOF
échelle zéro HALF_UP. Donnée inconnue en perception finale : estimation null,
transport initial zéro. En perception au checkout : donnée requise absente,
409 SHIPPING_ESTIMATE_UNAVAILABLE. Aucune estimation n'est un transport réel.

Ordering dépend uniquement de organization::api, grouping::api, shipping::api,
shared::api. Shipping utilise shared::api pour ses erreurs stables. Grouping
enrichit son contrat sans dépendance inverse. Aucun événement/consommateur ou
publication aval n'est introduit : aucun effet secondaire intermodule à exécuter
après placement dans cette tranche. Le futur workflow devra utiliser la publication
persistante et des consommateurs idempotents conformément à AGENTS.md.

## Amendement du 20 septembre 2026 — cinquième tranche

[ADR-0010](0010-financial-core-and-settlement.md) concrétise le noyau financier.
Sur instruction explicite de cette tranche, INITIAL_ORDER est désormais créé
atomiquement pendant le checkout, via payment::api et sans appel réseau.
Cette règle remplace la création après commit précédemment envisagée pour
cette seule obligation. Les futurs effets transport, remboursements et
notifications ne sont pas inclus dans la transaction de placement.
Le provider reste abstrait, sa simulation limitée aux tests. Le règlement
PaymentRequestSettled est fiable et la confirmation Order asynchrone/idempotente.
