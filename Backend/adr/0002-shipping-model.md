# ADR-0002 — Transport par commande et calculs révisés

- Statut : accepté
- Date : 2026-09-17
- Portée : shipping et coordination ordering
- Référence : [modèle métier](../docs/domain-model.md#shipping)

## Contexte

Un poids seul ne permet pas de représenter une tarification au volume. Une estimation peut différer du montant définitif ; une correction après mesure ou encaissement doit préserver l'historique.

## Décision validée

- Une ShippingAssessment unique par commande dans le MVP.
- ShippingMeasurement remplace WeightMeasurement et permet poids ou volume.
- ShippingCalculation conserve les calculs et leurs révisions.
- Les corrections ajoutent une observation/un calcul au lieu de réécrire l'historique.
- Méthodes : PER_KG, PER_CBM, PER_ITEM, FIXED.
- Modes : AFTER_FINAL_ASSESSMENT et ESTIMATED_AT_CHECKOUT.
- Une quantité facturable est déterminable pour chaque commande.
- La répartition d'un coût/poids collectif entre commandes est hors MVP.
- shipping ne dépend PAS de payment. ordering rapproche calculs et faits financiers et crée les obligations de paiement.

## Application et responsabilités

shipping possède les observations, tarifs appliqués, unités, quantités facturables, règles d'arrondi et résultats. Il publie les faits de finalisation/correction sans importer Order ni un type payment.

PER_ITEM utilise la somme des quantités ; FIXED est par commande. Un calcul fixe ou par article ne doit pas exiger une mesure fictive. L'estimation et le calcul définitif restent distincts ; une valeur inconnue n'est pas zéro.

L'état de calcul ESTIMATED/FINALIZED/CANCELLED appartient à shipping. La situation financière BALANCE_DUE/SETTLED/CREDIT_DUE est calculée par ordering après finalisation, à partir des encaissements et remboursements affectés au transport. Elle n'est pas un état financier stocké et entretenu par shipping.

ordering inclut la part transport du paiement initial, traite les obligations déjà actives et n'émet pas un nouveau solde complet à chaque notification. Les révisions et références d'obligation assurent l'idempotence métier.

Après correction d'un montant déjà payé, conserver le calcul et la demande payés, puis traiter l'écart. Un fait ancien rejoué ne remplace pas une révision plus récente.

## Conséquences

La séparation évite le cycle shipping/payment et préserve les faits. Les mesures et calculs de shipping restent utilisables sans intégration à un prestataire.

Les arrondis suivent AGENTS.md : précision décimale, pas d'arrondi préalable du poids, charge finale XOF entière. Les corrections et trop-perçus exigent une coordination explicite dans ordering.

## Options écartées

Un seul champ de poids/prix écrasé ; WeightMeasurement imposé à tous les modes ; shipping consommant PaymentSucceeded ; statut SETTLED déduit d'une estimation payée ; répartition collective cachée dans un calcul par commande.

## Questions restantes

Tarifs de lancement, unité/quantité facturable exacte et traitement contractuel des corrections. Le mode de calcul d'un poids volumétrique éventuel doit être décidé avant son usage ; il n'est pas déduit automatiquement de AIR. La répartition collective reste exclue, même si le transporteur présente une facture globale.
