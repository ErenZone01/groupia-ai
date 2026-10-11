# ADR-0023 — Payment Review / Duplicate Provider Success

Date : 11 octobre 2026. Statut : accepté.

## Décision

Le périmètre est une `PaymentRequest`, jamais la commande entière. Un règlement
initial et un solde transport sont des obligations distinctes. Le premier succès
valablement alloué sous verrou reste définitivement le règlement principal. La
migration 024 interdit en PostgreSQL tout remplacement ou effacement d'un
`settled_payment_id` non nul. Aucun classement selon la date fournisseur n'est fait.

Un succès fournisseur et son allocation commerciale sont deux faits distincts.
Après validation complète de la preuve selon la politique du provider, un débit
supplémentaire crée une `PaymentReview` `OPEN / DUPLICATE_PROVIDER_SUCCESS`.
Une seconde tentative devient `SUCCEEDED` si sa transaction est absente ou déjà
identique à la transaction observée. Si elle est liée à une autre transaction,
ses faits financiers restent intacts : seule la review représente le nouveau débit.
Cela couvre plusieurs transactions sur une même `merchantReference`, y compris
la tentative principale. `principalPaymentId` peut donc égaler `anomalousPaymentId`.

La review conserve un snapshot d'incident : provider, compte, transaction,
montant, devise, date fournisseur facultative et date serveur de détection.
Les FK composites imposent organisation et obligation communes. Son identité
unique est provider/compte/transaction/raison avec `NULLS NOT DISTINCT`.
Une retransmission retrouve la même review, même résolue ; une troisième
transaction crée une review distincte. Les snapshots et résolutions terminales
sont protégés contre réécriture et suppression par PostgreSQL.

## Preuves, transactions et concurrence

La détection est dans `PaymentTransactions`, commune au webhook et aux résultats
vérifiés par le serveur. L'ordre reste demande verrouillée, tentative verrouillée,
validation et coordination transaction fournisseur, décision, persistance et
flush. La transaction fournisseur est également recherchée dans les incidents
existants pour empêcher sa réutilisation sur une autre tentative ou obligation.
La vérification de collision du remboursement tient compte des reviews.

Une transaction différente n'est admissible comme incident qu'après succès
authentique, corrélation complète, montant/devise exacts, identifiant valide et
demande déjà réglée. Sans ces conditions, les diagnostics historiques restent
applicables. Une observation de reversal ne devient pas un duplicate success.
Les verrous pessimistes et advisory locks PostgreSQL existants sont conservés.
L'enregistrement du succès secondaire et de sa review est atomique.

Cette décision fait évoluer uniquement la représentation du succès secondaire
dans ADR-0014. Bictorys reste `AUTHENTICATED_WEBHOOK`, sans GET `/status` obligatoire.
Les autres politiques de preuve ne sont pas assouplies. Un succès tardif sur une
obligation non allouée garde son traitement existant `LATE_SUCCESS`.

Un incident n'émet ni `PaymentRequestSettled`, ni `OrderCommerciallyAccepted`, ni
notification commerciale. La collection `COLLECTION:<paymentRequestId>` reste
réservée au principal. Aucun payout n'est déclenché.

## Consultation et résolution

Endpoints privés sous `/api/organizations/{organizationId}/payment-reviews` :

- GET : filtre `status` facultatif, pagination 1–100, tri `detectedAt DESC, id` ;
- GET `/{reviewId}` : détail avec UUID de commande dérivé de l'obligation ;
- POST `/{reviewId}/resolve-refunded` : référence externe et note facultative.

Lecture : `VIEW_PAYMENTS` (OWNER, MANAGER, VIEWER). Résolution : `MANAGE_REFUNDS`
(OWNER, MANAGER). Aucun ADMIN global ni permission nouvelle pour OPERATOR.
L'autorisation passe par `OrganizationAccess`, sans dépendance aux internes
d'identity ou ordering. Les réponses privées utilisent `Cache-Control: no-store`.

La seule transition est `OPEN -> RESOLVED_REFUNDED`. Elle atteste un remboursement
déjà effectué extérieurement ; elle n'exécute aucun refund, payout, mouvement
comptable ni événement `ShippingCreditRefundSettled`. Payment reste un historique
de débit réussi. Acteur et date proviennent du serveur. Le client ne peut pas
fournir de montant, statut, acteur ou date de résolution.

Référence : strip, obligatoire, maximum 160 caractères. Note : strip, vide vers
null, maximum 1000. Une commande identique normalisée retourne la résolution
initiale, acteur/date/version compris, même depuis un autre acteur autorisé.
Une contradiction retourne 409. Demande, paiement puis review sont verrouillés
dans cet ordre ; un retry webhook ne rouvre jamais une review résolue.
Le diagnostic scalaire duplicate est retiré seulement après résolution de toutes
les reviews ouvertes de la tentative, et seulement s'il porte encore ce motif.
Une autre anomalie, notamment une reversal, n'est jamais effacée par la résolution.
Les receipts restent des faits historiques `REVIEW_REQUIRED`.

## Limites explicites

Aucun backfill ni rejeu automatique des anciens receipts `REVIEW_REQUIRED`.
Aucune transformation SQL des anciens paiements. Une reprise historique exige
une opération dédiée avec revalidation des preuves et validation métier.
La V1 ne vérifie pas extérieurement la référence de remboursement déclarée par
l'acteur autorisé et ne gère pas les remboursements partiels.
Le snapshot d'incident évite de dépendre d'une reconstruction mutable ; aucun
module d'audit générique ni nouvel événement sans consommateur n'est ajouté.

## Vérification

Tests sur PostgreSQL Testcontainers : doubles succès sur une ou deux tentatives,
identités NULL, preuves invalides, transactions réutilisées, allocation concurrente,
résolutions concurrentes et contradictoires, autorisations/isolation, rollback de
review, protection SQL du principal et de l'historique, absence de double collection
et notification, séparation initial/transport et réconciliation.
