# Détail Groupage V2

Objectif : alléger l'interface mobile et séparer les fonctions métier.

La page s'ouvre sur Offres ; Commandes reste toujours accessible. Fournisseur apparaît après clôture, Transport après commande fournisseur, Retraits après arrivée. Finances et Bilan ont chacun leur onglet, séparé des contenus logistiques. Infos rassemble la description, les conditions et les réglages repliés.

L'en-tête affiche uniquement le titre, le statut, la date et le nombre d'offres. Le lien public se dévoile à la demande ; sa copie reste accessible. Les onglets sont horizontaux et adaptés au clavier.

Aucune API, migration, permission ni transition métier modifiée. Avant fusion : CI au vert, contrôle responsive, tests Playwright et validation visuelle par l'utilisateur.