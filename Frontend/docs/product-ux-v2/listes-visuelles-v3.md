# Groupia — Listes visuelles V3 · conventions de réutilisation

Maquette approuvée : redesign Détail Groupage, mobile (Fournisseur/Commandes) et desktop. Cette implémentation conserve la navigation, les étapes métier, les reçus et les actions prioritaires des versions précédentes.

## Composants à réutiliser

- `g-product-thumbnail` (`shared/ui/product-thumbnail.ts`) : photo 32–56 px, `alt`, lazy loading, icône de repli si URL absente ou image cassée. Il est déjà utilisé dans Offres et Fournisseur.
- `g-order-product-preview` : groupe de trois photos maximum + indicateur du nombre d'autres produits ; réutilisable dans toutes les listes de commandes, avec identifiants fiables du backend.
- `g-order-list` : cartes mobiles et tableau compact desktop, liens vers la commande, états et montants réels. Utilisé dans la page Commandes et l'onglet Commandes d'un groupage.
- `g-orders-panel` : filtres serveur par statut réel, recherche par numéro et tri **seulement sur la page actuelle**, explicités à la gérante.
- `g-supplier-summary-panel` : quantités et montants historiques, miniatures uniquement si l'identité est prouvée, limite initiale de quatre lignes et détails secondaires repliables.

## Données et règles de sûreté

Le backend retourne dans `OrderSummary` `previewProductIds` (au plus trois produits distincts) et `productCount` (total distinct), via **une requête SQL** isolée par `organization_id` après vérification de `VIEW_ORDERS`. Il ne transmet ni téléphone ni contact client en liste. Une seule requête `product-media` alimente les miniatures visibles ; aucune requête N+1 sur les commandes.

Dans Fournisseur, les achats enregistrés ont `productId` dans `SupplierProjectionLine`. Avant achat, l'identité peut seulement être reconstruite si le SKU exact est unique ou, à défaut, si nom et variantes correspondent de manière unique au catalogue courant. Toute ambiguïté affiche une icône neutre, **jamais une photo potentiellement erronée**.

Les statuts « En préparation » ou « Prêt » propres à une hypothétique plateforme fournisseur ne sont pas inventés ; seuls les états et droits réels de Groupia sont affichés. Les filtres « Prêtes / Retirées » ne sont pas simulés avec le statut de paiement tant que l'API de filtrage physique ne les prend pas en charge.

## Convention pour les futures pages

Réutiliser une miniature et une rangée compacte plutôt qu'une grande liste de texte. Afficher 4 éléments initialement dans les sections de synthèse (avec `aria-expanded`), puis fournir « Voir les autres ». Sur les longues descriptions, employer `details/summary` avec chevron, libellé et focus visible. Ne jamais cacher les décisions à risque (paiement, remboursement, finalisation). Le titre du groupage reste limité à deux lignes avec accès au titre complet. Préserver les vrais libellés, tests Playwright, accessibilité clavier et isolation tenant.
