# Groupia — préparation de la version exploitable

État vérifié le 6 novembre 2026, base `8f99fb8` (PR 82 fusionnée).
Vente directe et groupage ont la même importance dans le produit.
Cette liste décrit les écarts observés ; elle ne certifie pas une mise en production.

## Parcours et critères de sortie

| Domaine | Présent dans le code | Reste à valider ou terminer |
| --- | --- | --- |
| Groupage | Catalogue, publication, commande, paiement initial, achat fournisseur, transport, solde transport, retrait, clôture | Recette complète et cohérence des écrans, remboursement et incidents compris |
| Vente directe | Vitrine, panier, devis serveur, zones et frais, GPS privé gérante, paiement, préparation | Expédition, livraison et retrait final : implémentation en cours de validation ; adresse du point de retrait ; gestion des commandes anciennes sans devis final |
| Paiements | Obligations typées, preuves serveur, webhooks idempotents, rapprochement, revues | Qualification Bictorys réelle, remboursement des ventes directes, visibilité homogène des cibles dans les finances |
| Abonnement | Essai, facture mensuelle, paiement volontaire, période acquise seulement après preuve serveur, protection vente directe et groupage | Recette du renouvellement et des expirations ; supprimer les textes limitant l'abonnement aux seuls groupages |
| Versements | Ledger et réservation de fonds par groupage, destination propriétaire, Bictorys sandbox, rapprochement | Inclure les ventes directes dans le ledger et les reversements ; authentification récente ; qualification des montants/frais provider |
| Interface | Écrans Angular, mode sombre, composants, navigation mobile | Maquettes puis refonte cohérente de chaque parcours ; vente directe accessible au même niveau que groupage ; moins de texte, espacement, contraste, états vides/chargement/erreur |
| Déploiement | Flags conservateurs, contrôles CI, migrations, procédures financières | Environnement de recette, restauration de sauvegarde, secrets, domaine, supervision, validation financière réelle avant activation |

## Ordre de réalisation

1. Fermer les parcours métier incomplets avec tests des permissions, historiques et courses concurrentes.
2. Unifier les fonds de la boutique sans attribuer les frais Groupia à la gérante ni compter deux fois un paiement.
3. Tester les abonnements, les anomalies et les restitutions ; qualifier les intégrations externes.
4. Créer les maquettes responsive des parcours stabilisés.
5. Implémenter le frontend par parcours complet : accueil/navigation, catalogue, ventes/groupages, commandes, finances, boutique/abonnement, expérience cliente.
6. Recette de déploiement : CI entièrement verte, tests métier et responsive, configuration et exploitation validées.

## Vente directe : remise finale

Transitions proposées : `READY_FOR_PICKUP → PICKED_UP` ou
`READY_FOR_DISPATCH → DISPATCHED → DELIVERED`.
Chaque action exige la permission `MANAGE_FULFILLMENT`, une confirmation physique,
le verrouillage de la commande et de l'obligation, et une preuve financière exacte
sans rapprochement en cours. Un replay renvoie le reçu historique et ne répète pas
l'action. Le journal conserve acteur, date et preuve ; le suivi privé client expose
les dates sans exposer les acteurs. Une anomalie financière ultérieure n'efface pas
une livraison déjà effectuée.

La remise ne déclenche aucun versement automatique. Les paramètres de paiement et
de payout restent désactivés par défaut. Les tests simulés ne remplacent pas une
qualification réelle du prestataire.

## Vérifications de cette branche

- Frontend initial : 508 tests réussis ; après tests de remise : 510 réussis.
- Lint frontend : exécuté ; résultat final à contrôler avec le commit livré.
- Backend local : bloqué par l'accès Maven indisponible et l'absence de Docker.
  La compilation, PostgreSQL/Testcontainers, Spotless et l'architecture doivent
  donc être validés dans la CI avant fusion.
- Aucune activation production ni transfert réel effectué.
