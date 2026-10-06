# ADR-0020 — Ledger marchand et reversements distincts des remboursements clients

Date : 2026-10-06. Statut : accepté pour cette tranche.

## Contexte

Groupia encaisse produits, transport et frais de plateforme, mais doit distinguer
son revenu des fonds dus aux boutiques. Les refunds existent déjà avec deux sources
de financement. Le compte Bictorys sandbox ne permet pas de tester de payout réel.

## Décision

Créer le module fermé `settlement`, propriétaire d'un ledger append-only, des
révisions de destinations et des MerchantPayout. Utiliser les APIs publiques et
les publications durables existantes, sans accès aux repositories d'autres modules.

Créditer exactement goods + shipping une fois par obligation réellement réglée
et commande acceptée. Garder fee hors crédit. Débiter les refunds BICTORYS_PAYOUT
SUCCEEDED ; **ne jamais déduire MANUAL_EXTERNAL**, financé par la boutique elle-même.
Reprendre les faits historiques déterministes avec la seule migration 020.

Rendre les crédits disponibles à partir de CLOSED. Réserver le disponible complet
d'une campagne sous verrou de boutique ; plafonner par la position globale après
compensation des dettes et réservations. Aucun payout partiel volontaire, aucun
payout automatique par commande, aucun blocage de SUPPLIER_ORDERED sur un reversement.

OWNER gère les destinations par nouvelle révision ; OWNER/MANAGER demandent un
reversement. L'argent et les références provider ne viennent jamais du navigateur.
Le snapshot de destination, l'idempotence et la réservation précèdent l'appel réseau.
Seule une preuve provider serveur corrélée peut ajouter le débit SUCCEEDED.
Timeout/revue conservent la réservation. Aucun succès manuel par la bénéficiaire.

Séparer `MerchantPayoutProvider` du refund provider. Fournir un adaptateur de
production indisponible et un fake seulement dans les tests. Le nouveau flag
merchant ne rend pas le vrai provider disponible à lui seul. L'adaptateur Bictorys
`transactionType=settlement` sera ajouté après validation du contrat du compte.

## Conséquences et limites

Pas de nouvelle dépendance technique. Les événements peuvent être rejoués après
panne. Les anciennes migrations restent immuables. Un solde négatif est conservé
et compensé par les futurs crédits, jamais prélevé automatiquement sur la gérante.
Les disponibilités par campagne partagent le plafond global et ne sont pas additives.
PSP fee allocation policy not implemented.

L'UI et le ledger fonctionnent provider désactivé ; aucun versement réel n'est
revendiqué. Voir [contrat, recette et prérequis production](../docs/merchant-settlement-v1.md).
