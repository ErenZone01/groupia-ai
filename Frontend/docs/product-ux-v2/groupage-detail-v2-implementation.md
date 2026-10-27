# Détail Groupage V2

Objectif : alléger l'interface mobile et séparer les fonctions métier.

La page s'ouvre sur Offres ; Commandes reste toujours accessible. Fournisseur est consultable dès l'ouverture des commandes et actionnable après clôture, Transport apparaît après commande fournisseur, Retraits après arrivée. Finances et Bilan ont chacun leur onglet, séparé des contenus logistiques. Infos rassemble la description, les conditions et les réglages repliés.

L'en-tête affiche uniquement le titre, le statut, la date et le nombre d'offres. Le lien public se dévoile à la demande ; sa copie reste accessible. Les onglets sont horizontaux et adaptés au clavier.

Aucune API, migration, permission ni transition métier modifiée. Avant fusion : CI au vert, contrôle responsive, tests Playwright et validation visuelle par l'utilisateur.

## Itération de lisibilité (retour gérante)

- Action métier prioritaire dans l'en-tête selon l'état (fournisseur, transport, retraits, bilan) ; les boutons qui confirment des transitions restent dans leur onglet et gardent leurs validations.
- Offres : 4 lignes par défaut, puis « Voir toutes » / « Réduire ». Miniature réelle de 56px via l'API media batch tenant-bound, repli sûr si image absente/indisponible. Sélecteur de produit : miniature de la variante choisie.
- Transport : statut et arrivée visibles, référence/prestataire et historique dans des panneaux natifs repliables. Liste des frais par commande chargée sur demande et limitée initialement à 4 lignes ; le suivi d'origine, les mesures et confirmations restent identiques.
- Retraits : actions physiques (préparer/retirer) avant les blocs financiers ; 3 indicateurs visibles, indicateurs secondaires repliés, liste des commandes limitée à 4 par défaut, filtres conservés.
- Finances : priorité à « Disponibles » ; autres montants séparés ; lien « Mes versements » conservé. Rien n'est recalculé côté navigateur.
- Les résumés historiques de commande fournisseur et de commande cliente sont des **snapshots** sans référence d'image produit fiable : ne pas associer de photo par simple comparaison de nom. Leur éventuelle association doit être définie via contrat métier sûr, sans modifier l'historique.
- Tests E2E historiques adaptés aux nouvelles destinations d'onglets sans retirer les validations de versions, paiements, mutations et confirmations.

**Fusion bloquée jusqu'aux contrôles CI complets et à la validation visuelle humaine.**
