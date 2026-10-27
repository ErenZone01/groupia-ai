# Catalogue V3 — règles de conception réutilisables

Maquette validée : `groupia_catalogue_maquette.html` (mobile et desktop).

## Comportements conservés
- Création de produits, saisie de variantes et gestion des images via les composants métier existants. Ces composants sont présentés dans des dialogues natifs accessibles, qui restituent le focus après fermeture.
- Pagination serveur existante, y compris les droits boutique ; une recherche ou un filtre **ne s'applique qu'à la page courante**, ce qui est indiqué à proximité des contrôles. Il ne faut pas confondre ces compteurs avec une statistique globale du catalogue.
- Les photos et variantes appartiennent au produit, et les prix à l'offre d'un groupage. Aucun prix fictif n'est affiché dans le catalogue.

## Composants et règles à reprendre
- `g-product-thumbnail` : aperçu de 78 px avec gestion commune de l'image cassée, réutilisé dans les listes Commandes, Groupage et Fournisseur.
- `g-badge`, `g-button` : composants partagés pour le statut et les actions.
- Cartes : miniature fixe, zone de texte `min-width:0`, titre sur deux lignes au maximum avec `title` complet, liste des variantes sous contrôle `aria-expanded`. L'emplacement des détails ne doit jamais déplacer les autres cartes hors écran.
- Descriptions longues repliées, boutons de taille tactile (44 px), recherche explicite avec libellé accessible, tri et filtres actifs représentés par `aria-pressed`.
- L'état de la boutique et de la session invalide l'affichage et les anciennes requêtes médias. Ne jamais laisser visibles des photos provenant d'une autre boutique.
- Le dialogue de galerie ouvre le composant `ProductGallery` inchangé, avec ses vérifications d'image et d'idempotence. On ne doit pas simuler de nouveaux endpoints ou de fausses opérations.

## Tests
`Frontend/e2e/catalog-ux.spec.ts` teste largeur 320/360/390/768/1440, mode clair/sombre, absence de débordement, filtres avec compteurs par page, recherche locale, tri, variantes, images, création.
`Frontend/e2e/product-media.spec.ts` conserve le contrôle des uploads, des droits, du réordonnancement et de l'idempotence dans le nouveau dialogue.

Avant de reproduire ces cartes dans une autre page, vérifier que les statuts et métriques qu'elle consomme existent dans l'API. Ne pas inventer de compteur global ou de recherche serveur quand ils n'existent pas.

## Édition et retrait V3.3

- Le produit existant peut être modifié (nom, description) dans un dialogue isolé, avec les champs initiaux, limites serveur (160/5 000 caractères) et protection par `version` du produit.
- En cas de 409 `PRODUCT_VERSION_CONFLICT`, aucune réécriture automatique : l'utilisateur voit un message et peut **actualiser** explicitement le produit avant de recommencer.
- Les variantes actuelles et la galerie **ne sont pas modifiées** par le formulaire nom/description. Les fonctions backend des variantes existent et nécessitent leur propre UX.
- « Retirer du catalogue actif » = **Archiver**, pas DELETE. L'opération est soumise à confirmation explicite, mentionne qu'elle n'est pas réversible dans la version courante, respecte l'autorisation serveur `MANAGE_CATALOG` et garde tout l'historique des commandes et des groupages.
- Les produits archivés restent visibles sous « Archivés » ; aucune action d'édition ou de ré-archivage n'est proposée dans la liste.
- En cas d'erreur de version à l'archivage, il faut actualiser la fiche et confirmer à nouveau ; ne jamais relancer l'écriture automatiquement.
- Le backend actuel ne propose **ni DELETE ni restauration** ; ne pas afficher un faux bouton « Supprimer définitivement » ou une promesse de restauration.
- Tests : `catalog-management.spec.ts`, `catalog-api.spec.ts` (sauvegarde, annulation, version obsolète, archivage explicite, droits via API).
