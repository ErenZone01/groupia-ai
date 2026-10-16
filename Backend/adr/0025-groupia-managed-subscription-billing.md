# ADR-0025 — Groupia-managed subscription billing over generic Payment obligations

Statut : accepté ; remplace le setup provider envisagé dans ADR-0021 pour la facturation.

## Décision

Groupia owns subscription periods and entitlement. Bictorys processes standard payment charges only. No provider-managed recurring billing is used in V1. Mobile Money payment is voluntarily initiated for each invoice. Duplicate provider successes never create additional entitlement.

SubscriptionInvoice fige un mois calendaire et le prix avant paiement. PaymentTarget(type,id) permet au moteur Payment de traiter ORDER et SUBSCRIPTION_INVOICE sans dépendre de Subscription. Aucune FK ni import Payment vers Subscription. Subscription dépend de payment::api et porte sa FK vers PaymentRequest.

Le premier règlement émet PaymentObligationSettled. Le fait historique PaymentRequestSettled demeure inchangé et réservé à ORDER. Le consumer Subscription relit une preuve serveur minimale, verrouille abonnement et facture et attribue exactement une période. Un conflit est une erreur récupérable, jamais ON CONFLICT DO NOTHING pour ce flux.

## Calendrier et concurrence

Début = fin de couverture active, sinon maintenant ; fin = plusMonths(1) dans le fuseau figé. Une seule période future. Une clé de création immuable sur invoice garantit le replay exact ; une clé différente confrontée à une OPEN reçoit 409 et reprend la facture existante. Les verrous PostgreSQL et uniques protègent plusieurs instances. La request et la facture sont atomiques, sans réseau. Expiration de request = fin de période ; succès trop tardif reste à examiner.

## Conséquences

Paiement volontaire par facture, sans calendrier PSP. Suspension non levée par paiement. Reversal conserve l'historique et demande une réconciliation sans inventer de suspension automatique. OWNER seul résout une review d'abonnement (MANAGE_REFUNDS et MANAGE_SUBSCRIPTION). VIEWER ne voit pas ces obligations, y compris compteurs et pagination.

001–025 inchangées ; 026 conserve les colonnes legacy et lie les nouvelles périodes aux preuves. Les anciens endpoints start/cancel retournent 410. Les flags de Billing Bictorys deviennent inopérants ; aucun garde-fou sandbox n'est levé et l'enforcement n'est pas activé automatiquement.

Voir [contrat détaillé](../docs/subscription-v1.md).
