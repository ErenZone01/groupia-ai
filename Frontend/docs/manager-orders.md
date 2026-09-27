# Commandes côté gérante — 26 septembre 2026

> Complément livré : l’onglet **À commander** est désormais connecté au read-model backend. Voir [récapitulatif fournisseur et recette](../../Backend/docs/supplier-summary.md). Le compte rendu ci-dessous décrit la tranche initiale et ses limites avant cet ajout.

## Livré

- /app/orders : cartes sur mobile/tablette, tableau à partir de 1024 px, filtre de statut serveur, pagination de 20 commandes, recherche par numéro limitée à la page chargée.
- /app/orders/:orderId : contact client, articles et descriptions historiques, attributs, quantités, prix et montants snapshotés, statut de commande et état de retrait séparés.
- /app/groupages/:id, onglet Commandes : même liste avec campaignId exact transmis au backend. Retour du détail vers le bon onglet avec filtre/page conservés.
- Dashboard : quatre nombres de commandes fiables (toutes, CONFIRMED, PENDING_PAYMENT, EXPIRED), toutes périodes, et cinq commandes récentes.
- /app/commandes et /commandes redirigent vers /app/orders. Navigation desktop/mobile actualisée.

Le parcours public, le backend, les migrations et les fichiers OpenAPI générés restent inchangés. Aucune nouvelle dépendance.

## API réellement utilisée

| Méthode | Endpoint | Usage |
| --- | --- | --- |
| GET | /api/organizations/{organizationId}/orders | Liste paginée ; paramètres campaignId, status, page, size |
| GET | /api/organizations/{organizationId}/orders/{orderId} | Détail explicite d’une commande |

Ces lectures utilisent OrderingControllerService.list4/get5 derrière OrdersApi, avec l’interceptor Bearer existant. La boutique vient du contexte autorisé, jamais d’un champ de saisie.

Modèles existants utilisés : OrderPage, OrderSummary, OrganizerOrderView, CustomerContact, OrderReceipt et OrderLineView. L’OpenAPI vivant sur localhost:8080 a été comparé au snapshot local : ces six schémas et ces deux endpoints sont identiques. Aucune régénération nécessaire.

Principaux fichiers :
- features/orders/orders-api.ts : façade de lecture avec timeout de 15 secondes.
- features/orders/orders.store.ts : page chargée, loading/error, recherche locale et annulation des requêtes périmées.
- features/orders/orders-panel.* : filtres, recherche, pagination, états vides et section fournisseur.
- features/orders/order-list.* : affichage cartes/tableau et liens de détail.
- features/orders/order-detail.page.* : contact et historique de commande.
- features/orders/order-labels.ts : traductions distinctes des libellés de groupage.
- features/dashboard/orders-dashboard.store.ts : quatre lectures bornées, compteurs via totalElements.
- features/groupages/groupage-detail.page.* : intégration de l’onglet Commandes.

## Données disponibles et limites

### Liste : cliente et nombre d’articles absents

OrderSummary expose seulement id, campaignId, orderNumber, status, fulfillmentStatus, initialAmountDue, currency et placedAt. Il ne contient ni customerContactSnapshot ni items, ni nombre total d’articles.

Conséquence : la liste affiche le numéro, le montant initial, le statut, la date et l’accès au détail. Les colonnes Cliente et Articles sont omises ; les cartes ne prétendent pas connaître ces données. Une phrase indique qu’elles sont disponibles dans le détail. Aucun GET détail n’est déclenché pour enrichir une liste.

Une future évolution minimale de la projection de liste pourrait fournir le nom/prénom de contact et la somme des quantités. Elle nécessite une tranche backend séparée.

### Récapitulatif fournisseur : endpoint dédié nécessaire

L’API permet correctement la corrélation par campaignId et le filtrage status=CONFIRMED. En revanche, elle ne fournit pas les snapshots d’articles dans la liste. Les obtenir nécessiterait un GET détail pour chaque commande, puis toutes les pages : ce serait précisément le N+1 et le chargement exhaustif interdits.

Le calcul fournisseur n’est donc **pas implémenté**. La section « À commander au fournisseur » explique son indisponibilité et permet d’ouvrir uniquement les commandes payées. Aucun montant initial (incluant les frais) n’est utilisé comme montant fournisseur ; aucune somme de page n’est présentée comme total global.

Proposition de contrat pour une tranche backend séparée, non créée ici :
- lecture autorisée pour la boutique et un groupage ;
- sélectionner exclusivement status=CONFIRMED, donc exclure aussi COMPLETED, PENDING_PAYMENT, EXPIRED et CANCELLED ;
- agréger les snapshots historiques des lignes, sans jointure au catalogue actuel pour leurs libellés/prix ;
- conserver une identité produit non ambiguë et une représentation canonique des attributs pour regrouper produit + variante + unité de vente ; éviter de fusionner des produits homonymes ;
- renvoyer les lignes agrégées (nom/libellé historique, attributs, unité, quantité, montant marchandises) ;
- fournir séparément quantité totale et montant marchandises confirmé, sur tout le groupage, sans frais de service ni transport ;
- prévoir pagination des groupes agrégés et totaux globaux calculés côté serveur avec une lecture cohérente.

Les tests d’agrégation et de regroupement de variantes appartiendront à cette implémentation. Cette tranche teste à leur place le filtre strict CONFIRMED, l’absence de calcul partiel et l’absence de chargement des détails en cascade. Aucun algorithme non utilisé n’a été ajouté pour donner l’impression que l’agrégation est disponible.

### Dashboard et recherche

Le backend trie les commandes par placedAt décroissant, puis id croissant. Une requête size=5 fournit les commandes récentes et le total global. Trois requêtes size=1 filtrées fournissent les totaux par état. Seul totalElements est utilisé pour les compteurs ; il n’y a ni parcours des pages ni calcul depuis les seules lignes reçues.

Les compteurs reflètent chacun leur réponse serveur au chargement/à l’actualisation ; les quatre appels ne sont pas un snapshot transactionnel unique. Les valeurs restent indisponibles en cas d’erreur, jamais remplacées par zéro. Aucun bénéfice, revenu net, solde ou chiffre d’affaires hebdomadaire n’est calculé.

L’API n’expose pas de recherche par texte. La recherche locale indique explicitement « sur cette page », porte sur orderNumber et ne déclenche aucune requête supplémentaire. Changer de filtre revient à la première page. Les filtres et la page sont conservés dans l’URL pour le retour du détail.

## Sécurité et comportement

- CONFIRMED est traduit « Payée » ; PENDING_PAYMENT « En attente ». Les autres états sont traduits sans déduction financière.
- Aucun bouton « Marquer comme payé », aucune mutation de commande.
- Aucun appel de paiement ou de réconciliation depuis cette tranche.
- Les références financières internes et tokens ne sont jamais rendus ou journalisés.
- Le détail utilise exclusivement les snapshots de commande ; modifier le catalogue ne modifie pas cet affichage.
- Changer de boutique, de page ou de filtre annule la requête précédente. Détruire le composant annule la lecture en cours.
- Le récapitulatif fournisseur ne présente aucune donnée tant que le contrat nécessaire n’existe pas.

## Recette réelle

Le parcours public jusqu’à CONFIRMED a été validé manuellement par l’utilisateur avant cette tranche. Il n’est pas nécessaire de refaire un paiement pour vérifier une commande existante.

Si une nouvelle commande de recette est nécessaire :
1. Démarrer PostgreSQL, Spring Boot, le frontend et le tunnel webhook suivant [la recette publique](public-checkout.md#recette-réelle-manuelle).
2. Créer/publier un groupage, passer une commande cliente et effectuer manuellement le paiement sandbox.
3. Attendre que le suivi public indique CONFIRMED après le vrai webhook.
4. Dans la session gérante de la boutique concernée, ouvrir /app/orders.
5. Actualiser si nécessaire ; la commande doit apparaître « Payée ». Vérifier qu’une commande PENDING_PAYMENT apparaît « En attente ».
6. Filtrer Payées puis ouvrir le détail : vérifier le contact, les descriptions/options historiques, la quantité, le prix unitaire, les totaux de lignes, les frais, le transport initial et le total.
7. Revenir à la liste : filtre et pagination doivent être conservés.
8. Ouvrir le groupage, onglet Commandes : la même commande doit apparaître via la corrélation campaignId.
9. Dans « À commander au fournisseur », cliquer « Voir les commandes payées » : seules les CONFIRMED sont affichées. Le récapitulatif par produit et son montant restent indisponibles jusqu’au futur endpoint.
10. Vérifier le dashboard après actualisation et les vues mobile/light/dark.

Vérification réelle effectuée pendant cette tranche : serveur OpenAPI accessible (HTTP 200), contrats conformes au client généré. La lecture authentifiée d’une commande réelle n’a pas pu être réalisée : l’outil de contrôle du navigateur a échoué au démarrage à deux reprises. Aucun identifiant privé n’a été extrait et aucun paiement réel/sandbox n’a été automatisé.

## Validation automatisée

Depuis Frontend :

```powershell
npm test
npm run build -- --configuration production
npm run test:e2e
```

Les fixtures sont isolées dans les tests. Les parcours publics existants restent dans la suite de non-régression.

## Résultats obtenus

- `npm test` : **133 tests réussis**, 18 fichiers (20 tests supplémentaires).
- `npm run test:e2e` : **56 tests navigateur réussis**, dont les 45 scénarios existants et 11 scénarios Commandes gérante.
- `npm run build -- --configuration production` : **réussi**, budgets respectés ; bundle initial 301,46 kB, transfert estimé 84,33 kB.
- Responsive vérifié à 360, 768, 1024 et 1440 px, thèmes clair/sombre ; captures du tableau desktop et du détail mobile inspectées.
- `git diff --check` : réussi. Backend, migrations, parcours public et client généré inchangés.
- Agrégation fournisseur absente pour la raison contractuelle décrite ci-dessus : aucun total fictif, aucun N+1 ajouté.
