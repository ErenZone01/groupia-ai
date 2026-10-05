# ADR 0018 — Distribution explicite et remise physique

Statut : accepté pour cette tranche, 4 octobre 2026.

## Décision

Order porte la projection physique actuelle. READY_FOR_PICKUP est une attestation humaine, conditionnée par un transport final sans dette ni paiement incertain. CREDIT_DUE autorise le retrait mais empêche la clôture commerciale automatique. Le premier READY fait passer le groupage à DISTRIBUTING atomiquement ; aucun traitement ne clôt le groupage.

Le coordinateur reste dans ordering. Un port grouping::api dédié CampaignDistribution évite d'accorder MANAGE_GROUPINGS aux opérateurs. MANAGE_FULFILLMENT est centralisée dans la politique d'autorisation, pour OWNER/MANAGER/OPERATOR et organisation ACTIVE. Le journal local append-only partage la transaction Order/groupage ; ce fait opérationnel n'exige aucune publication asynchrone ni notification nouvelle.

L'ordre des verrous reste groupage → commande → obligations de paiement. La version courante est obligatoire pour toute mutation. Le moteur financier existant est relu ; aucun solde n'est matérialisé ou contourné. READY et PICKED_UP bloquent les mutations de transport ; la révocation motivée rend NOT_READY avant une éventuelle correction.

## Précisions choisies

- readyForPickupAt désigne le cycle courant ; une révocation l'efface, le journal conserve les dates des anciens cycles.
- Les mesures sont également verrouillées après disponibilité pour éviter un état physique/calcul incohérent ; lecture et historique restent accessibles après la remise.
- Le tableau porte sur les commandes CONFIRMED et COMPLETED. Les compteurs sont calculés serveur ; les catégories de préparation et de dette peuvent se recouper.
- Les mutations renvoient le tableau actualisé et la version du groupage. Un clic périmé retourne 409, sans replay de la transition ni événement supplémentaire.
- Un événement de paiement transport déjà alloué reste consommable après COMPLETED : le délai du consommateur asynchrone ne doit pas créer une publication irrécupérable après un retrait valide.

## Conséquences

Migrations additives 017 et 018, aucun changement des migrations 001–016 et aucun historique de disponibilité inventé. À la suite du démarrage local ayant appliqué une première version de 017, son SQL original est restauré et la séquence technique est déplacée dans 018. Seul le checksum de la seconde version connue est accepté explicitement ; les checksums stockés restent inchangés. Les deux chemins d'upgrade sont testés avec des événements existants. Voir l'explication de l'incident dans [distribution-v1.md](../docs/distribution-v1.md).

La remise d'une commande créditrice laisse un reste à régulariser ; la clôture globale du groupage et le workflow de remboursement sont différés.
