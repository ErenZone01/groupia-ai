# Robustesse responsive des textes métier (commande, offre, fournisseur)

## Problème traité
Un montant XOF généré par Intl (ex. **49 600 000 F CFA**) contient des espaces insécables ; combiné à `white-space: nowrap` et à un nom produit variable dans un flex, il peut imposer une largeur minimale supérieure à celle du mobile.

## Règles UI obligatoires à réutiliser
- Les textes éditables ou issus d'instantanés (noms produit, cliente, description, SKU, attributs, titres de groupage) ont un contenant `min-width: 0` et `max-width: 100%`, avec `overflow-wrap: anywhere` et un retour à la ligne lorsque l'information doit rester lisible.
- Les montants monétaires ne sont jamais `nowrap` dans les cartes mobiles : conserver la valeur entière et permettre la césure si nécessaire ; les montants ne doivent **pas** être ellipsés.
- Pour les lignes de commandes, laisser la miniature à largeur fixe et placer le nom puis le total en lignes séparées, pas en concurrence dans la même rangée.
- Dans les grilles, toujours `minmax(0, 1fr)` pour les colonnes de contenu et les montants. Sur mobile Fournisseur, les quantités/montants passent sous le nom sans forcer une 3e colonne.
- Les titres condensés sur deux lignes (comme la liste d'offres) doivent conserver l'accès au texte complet quand nécessaire, mais ne jamais provoquer de défilement horizontal de la page.
- Limiter le défilement horizontal à des contrôles explicites (onglets, navigation) dans des conteneurs isolés ; ne pas masquer globalement le débordement de `body` ou de la page, ce qui masquerait un bug.

## Non-régression
`Frontend/e2e/manager-orders.spec.ts` : scénarios `320 / 360 / 390 / 768 / 1440` px en clair/sombre avec nom sans espaces, description et e-mail longs, total initial important et prix de **49 600 000 XOF** ; vérifie l'absence d'overflow global et local avant et après ouverture des panneaux. Test additionnel sur l'onglet Offres du groupage.

## Portée
Styles renforcés dans `g-order-line-card`, `order-detail.page.scss`, `order-list.scss`, `supplier-summary-panel.scss` et `groupage-detail.page.scss`. Pour les autres vues futures, appliquer les mêmes conventions et créer un test similaire pour chaque type de liste accueillant du contenu utilisateur.
