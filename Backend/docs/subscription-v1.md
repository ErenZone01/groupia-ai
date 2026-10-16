# Groupia-managed Subscription Billing V1

Groupia owns subscription periods and entitlement. Bictorys processes standard payment charges only. No provider-managed recurring billing is used in V1. Mobile Money payment is voluntarily initiated for each invoice. Duplicate provider successes never create additional entitlement.

## Calendrier et facture

Une boutique conserve un abonnement unique. Le trial historique reste intact. `billing_timezone`, initialisé depuis la boutique en 026, devient immuable. Le prix, le contact de facturation, les références, le fuseau et les dates sont snapshotés avant tout paiement dans `subscription_invoices`.

Le début est la fin du trial actif ou de la couverture payée actuelle ; après une interruption, il est l'instant courant, tronqué à la microseconde. La fin est `start.atZone(billingTimezone).plusMonths(1).toInstant()` : jamais 30 jours fixes. Une seule période future peut être prépayée. Un changement de tarif ne modifie aucune facture ouverte.

La facture est OPEN, PAID ou EXPIRED. Une OPEN entièrement passée n'est plus payable. Une nouvelle demande expire cette ancienne facture puis repart de maintenant. Une facture dont le règlement attend déjà son consumer durable reste protégée contre cette expiration. Un succès après `periodEnd` suit LATE_SUCCESS et ne crée aucun droit automatique.

## Contrat HTTP

Base : `/api/organizations/{organizationId}/subscription`.

| Méthode et chemin | Contrat |
| --- | --- |
| GET | Accès, disponibilité du provider, configuration du tarif, contact |
| GET /billing-contact, PUT /billing-contact | Contact courant ; modification OWNER |
| GET /history | Périodes payées |
| GET /invoice-preview | Dates et prix estimés de la prochaine facture, aucune création |
| GET /invoices, GET /invoices/{id} | Factures et état financier synthétique |
| POST /invoices | Pas de body financier ; Idempotency-Key obligatoire |
| POST /invoices/{id}/payment | Body `{ "provider": "BICTORYS" }` seulement ; Idempotency-Key obligatoire |
| POST /start, POST /cancel | 410 SUBSCRIPTION_PROVIDER_BILLING_RETIRED |

OWNER gère ; MANAGER consulte. VIEWER et OPERATOR n'ont pas VIEW_SUBSCRIPTION. Les données privées sont no-store. Les clés de création satisfont `[A-Za-z0-9._:-]{16,128}`. Une même clé retourne toujours la même facture, même après changement de date. Une autre clé alors qu'une facture OPEN existe retourne 409 SUBSCRIPTION_INVOICE_EXISTS et son invoiceId ; aucune table d'alias ni seconde obligation n'est créée.

Création : verrou abonnement, recherche clé, calendrier et snapshots, création PaymentRequest et facture dans une transaction locale atomique. Aucun appel réseau dans cette transaction. Initiation : contexte de facture relu côté serveur puis moteur Payment standard hors transaction réseau.

## Preuve et droits

`subscription → payment::api` uniquement. PaymentRequest cible `SUBSCRIPTION_INVOICE/id`, avec purpose SUBSCRIPTION_MONTHLY, orderId null, ventilation goods/fee/shipping nulle, expectedAmount positif et expiresAt = periodEnd. La FK est portée par invoice vers request, jamais l'inverse.

Le webhook Bictorys existant vérifie secret, compte provider, références, transaction unique, montant, devise et éligibilité. Le premier règlement publie PaymentObligationSettled. Son consumer durable verrouille abonnement puis facture, relit PaymentSettlementEvidence et compare les preuves avant de créer une période et marquer la facture PAID. Un replay exact est idempotent ; une contradiction échoue et reste récupérable dans event_publication. Ne jamais supprimer une publication échouée pour masquer une contradiction.

Une période future payée pendant trial ne force pas ACTIVE. SUSPENDED reste SUSPENDED. Invoice OPEN, Payment CREATED/PENDING, checkoutUrl et redirect navigateur ne donnent aucun droit. Un double débit ouvre une PaymentReview et ne donne pas un deuxième mois. Une reversal après attribution conserve les périodes et la réconciliation ; aucune suppression, suspension ou restitution automatique n'est inventée.

Les opérations commandes, transport, distribution, retrait, clôture et ledger marchand ignorent les obligations d'abonnement. PaymentRequestSettled historique conserve exactement son schéma et n'est publié que pour ORDER.

## Configuration et legacy

Conserver GROUPIA_SUBSCRIPTIONS_ENABLED, GROUPIA_SUBSCRIPTION_ENFORCEMENT_ENABLED (false par défaut), GROUPIA_SUBSCRIPTION_TRIAL_MONTHS et GROUPIA_SUBSCRIPTION_MONTHLY_XOF. La disponibilité des charges dépend du ProviderRegistry et de la configuration Bictorys existante, avec son garde-fou sandbox inchangé.

GROUPIA_BICTORYS_SUBSCRIPTIONS_ENABLED et GROUPIA_BICTORYS_SUBSCRIPTION_PLAN_ID sont dépréciés et ignorés par ce flux. Les classes de setup provider sont hors wiring. Les colonnes provider_customer_id, provider_subscription_id, provider_setup_key, provider_setup_requested_at, cancel_at_period_end et canceled_at restent historiques sans réutilisation. Aucune API Bictorys Billing/Customer Management n'est appelée.

## Frontend et reprise

La page propose un paiement volontaire, l'état pending/review, une période future déjà payée, et un rafraîchissement explicite. Une génération de contexte, la session et les séquences d'opérations protègent A→B→A. Les GET et mutations quittés sont annulés côté navigateur sans présumer l'annulation serveur.

Après timeout, conserver facture et clés en mémoire dans la session courante, puis relire le serveur. Ne pas créer automatiquement une autre facture. Après rechargement complet, la liste serveur retrouve la facture ouverte ; une initiation inconnue reste protégée par les verrous/états Payment. Une nouvelle tentative n'est possible que selon l'éligibilité serveur. Les clés ne constituent pas une preuve de paiement.

## Migration et exploitation

026 est la seule nouvelle migration ; 001–025 sont immuables. Elle backfill uniquement les cibles ORDER et le fuseau, sans facture ni période historique inventée. Elle protège snapshots, liens financiers, unicité et preuves de périodes. Les nouveaux liens des périodes historiques restent null.

Avant production : configurer et valider séparément les credentials/provider production, surveiller les publications Modulith échouées, traiter les reviews et reversals, mesurer le volume de factures (historique V1 non paginé). Aucun prélèvement récurrent ni refund automatique.

Voir [ADR-0025](../adr/0025-groupia-managed-subscription-billing.md), [API financière](payment-api.md) et [dépendances](module-dependencies.md).
