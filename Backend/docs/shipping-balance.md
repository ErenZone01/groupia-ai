# Shipping Finance V1 — solde transport

## Sources et calcul

La position est dérivée dans ordering, sans table de montants dupliqués :

- finalShippingAmount : explication du ShippingCalculation FINAL courant uniquement ;
- initialShippingPaidAmount : initialShippingChargeAmount historique de la commande CONFIRMED (ou COMPLETED en lecture) ;
- additionalShippingPaidAmount : somme des obligations SHIPPING_BALANCE CLOSED portant un settledPaymentId ;
- totalShippingPaidAmount = initial + compléments réglés ;
- delta = final - total réellement payé.

| Condition | Position | Montant |
|---|---|---|
| Aucun FINAL | NOT_FINALIZED | Pas de demande de paiement |
| delta > 0 | BALANCE_DUE | amountDue = delta |
| delta = 0 | SETTLED | Aucun nouveau paiement |
| delta < 0 | CREDIT_DUE | creditAmount = -delta |

Une tentative CREATED/PENDING, une URL de retour, une estimation ou un état navigateur ne contribue jamais au payé.
CREDIT_DUE est une régularisation à effectuer par la boutique, pas un remboursement automatique.
Les montants restent des BigDecimal XOF, sans nouveau frais plateforme. Chaque obligation a goods=0, fee=0, shipping=expectedAmount=delta positif.

## Architecture et transactions

OrderShipping conserve autorisation, verrou groupage puis commande, puis fait prévisualiser le calcul.
ShippingFinance verrouille ensuite toutes les obligations transport dans un ordre stable et refuse les tentatives dangereuses.
La finalisation append-only, l'annulation de l'obligation remplaçable et la création du nouveau solde partagent une transaction DB.
Toute erreur provoque le rollback intégral. Aucun appel réseau provider ne s'exécute dans cette transaction.

Référence déterministe : SHIPPING_BALANCE:<orderId>:<calculationId>. Un index partiel limite à une obligation transport OPEN par commande.
La création réutilise le mécanisme d'idempotence payment par businessReference et vérifie le contenu de la demande.
Un replay de finalisation avec une ancienne version retourne toujours le conflit optimiste existant ; il ne crée pas de seconde obligation.

PaymentOperations conserve ses méthodes initiales et expose initiateRequest, request, shippingRequests, lockShippingRequests et cancelShippingRequest.
La réservation d'une tentative verrouille les obligations transport avant toute création, puis libère la transaction avant Bictorys.
L'initiation ne lit jamais les montants du navigateur. Le contexte client provient du snapshot de commande.
Customeraccess reste dépendant uniquement de ordering::api pour ce parcours. Aucune nouvelle dépendance Modulith, aucun lien JPA entre modules.

Le subscriber durable PaymentRequestSettled valide organisation, commande, purpose, demande, paiement alloué, montant, devise et fermeture.
Pour SHIPPING_BALANCE, il relit la position actuelle sans confirmer à nouveau la commande et sans transition de retrait.
Un événement incohérent échoue et reste récupérable via le registre Modulith existant ; aucun paiement accepté n'est effacé.

## Corrections et incertitude

- Sans tentative dangereuse : annulation de l'ancienne obligation ouverte et nouveau delta lié à la nouvelle révision.
- CREATED, PENDING, SUCCEEDED non alloué ou reconciliationRequired : 409 SHIPPING_BALANCE_PAYMENT_IN_PROGRESS, aucune finalisation financière.
- Après allocation réelle : conservation du règlement précédent. Une hausse crée seulement le complément ; une baisse peut donner CREDIT_DUE.
- Un succès tardif sur une obligation annulée reste en review via le moteur existant. Il n'est pas ajouté au payé et bloque une nouvelle initiation tant que l'incertitude subsiste.
- FAILED/CANCELLED/EXPIRED reconnu par le moteur et sans review : nouvelle tentative possible sur la même obligation.
- Le statut brut Bictorys « expired » n'est actuellement pas normalisé en EXPIRED : la politique existante de review est conservée. Ne pas élargir ce contrat sans preuve provider.

Les finals historiques de V1 (avant cette tranche) sont lisibles sans écriture implicite. Une première obligation absente est créée à la demande de paiement autorisée, sous verrou commande, ou lors d'une nouvelle finalisation. Aucune conversion financière automatique des anciennes lignes.

## API et confidentialité

POST /api/public/customer-access/orders/{orderNumber}/shipping-balance/payments

Corps : {"provider":"BICTORYS"}. Header Idempotency-Key obligatoire.
Session HttpOnly ORDER_ACCESS de cette commande ou PHONE_VERIFIED correspondant au téléphone ; CSRF obligatoire.
Les champs inconnus (amount, purpose, currency, organizationId, orderId, paymentRequestId) sont refusés.
Réponse CustomerPaymentAttempt : id, statut, montant attendu, devise, indicateur de réconciliation, date et checkoutUrl privée.
Ce DTO ordering équivaut au reçu PaymentAttemptView sans ajouter un arc customeraccess → payment.

GET /api/public/customer-access/orders/{orderNumber} et le suivi privé historique exposent shippingSettlement.
GET /api/organizations/{org}/grouping-campaigns/{campaign}/shipping-assessments ajoute la même position à chaque ligne.
Les endpoints de prévisualisation/finalisation existants et leurs réponses restent compatibles.
La projection publique n'expose ni organisation, ni références provider, ni données d'autres commandes, ni URL de checkout.
Les consultations et mutations clientes conservent les protections no-store/CSRF et n'utilisent aucun bearer gérante.

## Migration 016

016-shipping-finance.sql enlève le NOT NULL global sur expires_at puis impose une deadline pour INITIAL_ORDER.
SHIPPING_BALANCE peut avoir expiresAt=null ; requireOpen, settlementRejection et matches gèrent cette absence.
La contrainte de dates existante continue à vérifier toute expiration renseignée.
Contraintes supplémentaires : solde strictement positif, ventilation sans frais et une obligation transport OPEN par commande.
Les anciennes migrations 001–015, leurs checksums stockés et les obligations historiques ne sont pas réécrits.
Aucun clearCheckSums, reset ou nouvel outil n'est requis.

## Parcours visuels

Cliente : Transport final / Déjà payé / Reste à payer, bouton de paiement si aucune tentative bloquante.
La clé est conservée en mémoire lors d'un retry réseau ; le double clic est bloqué. Le provider reçoit les snapshots serveur.
Comme le parcours initial existant, le checkout HTTPS Bictorys validé s'ouvre dans un nouvel onglet : le suivi reste accessible dans l'onglet original.
Si l'ouverture est bloquée, le lien « Ouvrir le paiement sécurisé » réutilise la même tentative.
Au retour/focus : « Vérification du paiement en cours » et relecture serveur. Rafraîchissement borné à 36 appels espacés de 5 s, puis actualisation manuelle.
Aucun paramètre d'URL ne prouve le succès. Le lien et les secrets ne sont jamais journalisés ou conservés dans localStorage.
Une tentative incertaine n'expose pas de lien payable. Après un échec terminal relu sur le serveur, le bouton redevient disponible.

Gérante : final, payé initial, compléments, total, dû/crédit, solde ouvert/réglé et tentative en cours.
La position est relue après enregistrement ; aucun calcul monétaire financier n'est fait par le frontend.
Aucun bouton de règlement manuel. Les libellés évitent les enums et noms techniques.

## Recette manuelle

Utiliser un environnement sandbox avec les configurations Bictorys/customeraccess déjà opérationnelles.

1. Créer une campagne FIXED / ESTIMATED_AT_CHECKOUT à 3 000 XOF et une commande. Régler le paiement initial via Bictorys, attendre CONFIRMED et conserver le magic link reçu.
2. Fermer la campagne et figer la commande fournisseur. Dans Transport des commandes, finaliser un montant communiqué de 8 000 XOF. Attendre : payé initial 3 000, compléments 0, dû 5 000, solde ouvert, aucun second frais.
3. Ouvrir le magic link : session ORDER_ACCESS et bouton « Payer … de transport ». Vérifier également avec une session PHONE_VERIFIED. Une autre commande/un autre téléphone ne doit pas donner accès.
4. Cliquer deux fois rapidement : une tentative/une initiation, puis checkout Bictorys dans un nouvel onglet. Tant que le webhook n'a pas été accepté : BALANCE_DUE et paiement en cours. Un simple retour ?status=success ne règle rien.
5. Accepter le paiement sandbox. Attendre le webhook authentifié et corrélé : Payment SUCCEEDED, demande CLOSED avec settledPaymentId, transport réglé. Order reste CONFIRMED, Fulfillment reste NOT_READY. Rejouer le webhook : aucun deuxième règlement ni notification de confirmation.
6. Avec une nouvelle commande au même stade, corriger 8 000 → 7 000 avant toute tentative : l'ancien solde 5 000 est annulé, le nouveau est 4 000. L'ancienne demande n'est plus initiable.
7. Avec une tentative PENDING ou en review, corriger : 409 explicite, révision finale et obligation inchangées. Réconcilier via les opérations existantes, jamais via un marquage manuel de succès.
8. Après un règlement total de 8 000, corriger à 9 000 : nouveau dû 1 000 sans frais. Régler ce complément et vérifier le cumul des règlements. Sur une autre commande réglée à 8 000, corriger à 7 500 : crédit 500 et aucune demande négative.
9. Final = 3 000 avec initial 3 000 : réglé sans nouvelle demande ; final = 2 500 : crédit 500, texte de régularisation par la boutique.
10. AFTER_FINAL_ASSESSMENT : initial transport 0 ; final 8 000 : dû 8 000. Faire échouer/annuler une tentative de manière confirmée : le dû reste 8 000 et une nouvelle tentative est possible.
11. Vérifier les montants gérante/cliente, mobile 360 px et desktop, clair/sombre, focus clavier. Sans FINAL : aucun bouton de paiement transport.
12. En Testcontainers, valider la migration depuis 015 avec obligations historiques, les contraintes et le rollback injecté. Ne jamais provoquer une erreur DB volontaire dans la base de travail.

Les tests Bictorys automatisés utilisent l'adapter réel avec serveur HTTP local et PostgreSQL Testcontainers ; ils ne réalisent aucun paiement externe réel.

## Hors tranche

READY_FOR_PICKUP/PICKED_UP, disponibilité physique, distribution et clôture commerciale ; remboursements automatiques ; espèces/Wave direct ; nouveaux templates ou livraisons WhatsApp.
Aucune dépendance ajoutée.

## Validation du 4 octobre 2026

- Backend `mvnw.cmd test` : 614 tests, zéro échec, erreur ou test ignoré.
- Backend `mvnw.cmd verify` : 614 tests, BUILD SUCCESS ; les 3 tests de modularité sont inclus.
- Après l'ajustement documentaire OpenAPI de `expiresAt` nullable, `mvnw.cmd -Dtest=OpenApiIntegrationTests verify` : 8 tests réussis et archive reconstruite. Le client Angular a été régénéré depuis ce contrat.
- Frontend `npm test` après régénération : 228 tests réussis dans 27 fichiers.
- Frontend `npm run build` après régénération : build de production réussi.
- Frontend `npm run test:e2e` : 111 tests réussis, dont les parcours transport cliente et gérante, mobile 360 px, desktop et thèmes clair/sombre.
- PostgreSQL Testcontainers : migrations sur base fraîche et passage de 015 à 016 validés ; historique et checksums antérieurs conservés.
- `git diff --check` : aucune erreur ; aucune migration SQL 001–015 modifiée.

Logs locaux : `Backend/target/shipping-finance-test.log`, `Backend/target/shipping-finance-verify.log`, `Backend/target/shipping-finance-openapi.log` et `Frontend/artifacts/shipping-finance-{unit,build,e2e}.log`.
Ces vérifications n'appliquent pas la migration à la base locale de travail et ne constituent pas un paiement réel dans le sandbox externe.
