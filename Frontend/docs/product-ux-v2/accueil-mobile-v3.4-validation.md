# Validation UX — Accueil mobile mixte V3.4

**Statut : DIRECTION VISUELLE ET HIÉRARCHIE VALIDÉES par le porteur du projet le 24 octobre 2026.**

Ce document complète la [charte d'expérience Groupia](groupia-experience-charter.md) et le [brief Accueil / Statistiques](accueil-statistiques-implementation.md). Il constitue la référence produit pour les futures reprises de l'Accueil mobile. Il ne change pas les contrats d'API ou le périmètre métier de la PR #29.

## Objectif

**Un accueil mobile unique et adaptatif** pour la gérante. Groupia reste identifiable par sa couleur vert profond, son orange ponctuel, ses surfaces sobres et sa typographie équilibrée. La priorité est la compréhension et l'action ; pas une collection de widgets.

**Ordre approuvé sur mobile (largeur de référence 390 px) :**

1. **Identité / bienvenue / contexte de boutique** : rassurer et situer la gérante ; ne pas surcharger le header.
2. **Actions prioritaires réellement réalisables** : préparer ou suivre une commande directe, terminer un brouillon de groupage, partager une campagne publiée ; **pas d'alerte inventée**. Sur le premier écran, ces actions priment sur les données analytiques.
3. **Synthèse opérationnelle très courte** : au plus deux métriques pertinentes au contexte (groupages, commandes à préparer, livraisons ou commandes en attente) ; ne pas mélanger comptages toutes périodes et filtres 3/6/12 mois sans étiquette.
4. **Opérations en cours** : groupage ouvert ou commandes de vente directe à préparer / livrer, avec statut et accès au détail.
5. **Aperçu commercial compact** : 1–2 chiffres maximum, période explicitement indiquée, lien vers `/app/statistiques`. Graphiques détaillés exclusivement dans Statistiques.
6. **Dernières commandes** : deux ou trois extraits non redondants avec lien vers `/app/orders`.
7. **Navigation mobile** : quatre entrées principales déjà existantes et `Plus`; la route Statistiques demeure secondaire. Pas d'ajout d'onglets simplement pour illustrer le mode direct.

Les éléments ci-dessus peuvent être **masqués plutôt que rendus vides** si une activité n'existe pas ou si les données nécessaires ne sont pas accessibles ; les erreurs réseau ne doivent jamais se transformer en « zéro vente ».

## Quatre scénarios de la même architecture

| Scénario | Accueil | Action dominante | KPI utiles | Carte centrale |
|---|---|---|---|---|
| Groupages uniquement | État des campagnes | Créer / finaliser / partager un groupage, selon le contexte | Groupages ouverts ; commandes à suivre | Groupage actif |
| Groupages + vente directe (V3.4 validée) | Activités commerciales mixtes | Action actionnable prioritaire (y compris préparation de commande directe) | Deux indicateurs contextuels | Groupage et/ou vente directe, avec origines visibles |
| Vente directe uniquement | Préparation et livraison des commandes | Mettre en vente / préparer la prochaine commande | À préparer et à livrer | Commande directe |
| Boutique sans activité | Démarrage guidé, pas de KPI inutiles | Choisir le premier parcours | Pas de cartes de compteurs à zéro | Deux parcours d'onboarding compréhensibles |

La personnalisation de l'accueil est **contextuelle** ; elle ne doit pas entraîner quatre implémentations indépendantes ni une navigation différente par boutique.

## Pages propriétaires et cohérence inter-pages

- **Accueil** : arbitrage rapide et extraits orientés actions.
- **Groupages / détail de groupage** : campagne, lien public, étapes de distribution, offres et historique détaillé.
- **Ventes directes (futur)** : mise en vente et gestion des produits hors campagne, à préciser dans un sprint métier dédié.
- **Produits** : catalogue de produits, variantes et médias communs lorsque la modélisation future le permet.
- **Commandes** : liste commune et détails, origine clairement identifiable (groupage ou directe) ; pas de duplication en deux historiques divergents.
- **Statistiques** : historique/comparaisons de ventes, et filtres par type de vente seulement après disponibilité de données fiables.
- **Paiements / Versements** : statut de paiement et opérations financières séparés de la valeur brute des articles.

**Vente directe encore non implémentée** : les statuts « à préparer », « à livrer », les métriques mixtes, stocks éventuels, modes de livraison et seuils financiers montrés dans les maquettes/prototype sont illustratifs. Ne pas les activer, ni créer de faux chiffres en production. Il faudra concevoir et tester leurs règles métier, paiements, permissions et périmètres de données.

## Références de conception

- Desktop mixte : [Figma V3.1](https://www.figma.com/design/mNQmfvIDtdNNJp1xq6kgeA?node-id=28-14)
- Mobile mixte : [Figma V3](https://www.figma.com/design/mNQmfvIDtdNNJp1xq6kgeA?node-id=45-152)
- Mobile groupages : [Figma](https://www.figma.com/design/mNQmfvIDtdNNJp1xq6kgeA?node-id=45-38)
- Mobile vente directe : [Figma](https://www.figma.com/design/mNQmfvIDtdNNJp1xq6kgeA?node-id=45-266)
- Mobile boutique vide : [Figma](https://www.figma.com/design/mNQmfvIDtdNNJp1xq6kgeA?node-id=45-379)
- **V3.4 prototype mobile mixte** : validation conversationnelle du 24 octobre 2026 ; prototype HTML conservé dans la conversation, **pas encore recopié dans Figma** du fait de la limite MCP du plan Starter. La validation concerne les principes et la disposition de V3.4, pas une disponibilité fictive de ses actions.

## Critères à vérifier avant implémentation

- Validation visuelle réelle 360, 390, 430 px, clair/sombre, zoom texte 200 %, clavier et tactile.
- Actions accessibles sans défilement excessif, zones tactiles 44 px quand applicable, boutons identifiables et contraste WCAG AA.
- UI agile : requêtes bornées, aucune boucle de chargement, aucun graphique lourd à l'accueil, états de chargement / absence de données / erreurs distincts.
- Confidentialité : isolation boutique et session, aucune donnée résiduelle au changement de contexte.
- Comparaison de chaque future page avec la [matrice de propriété d'information](groupia-experience-charter.md) : pas de doublon de graphique, liste ou action sans bénéfice identifié.
- Ne pas fusionner un design uniquement parce que ses tests sont verts : vérifier les captures réelles et l'alignement à l'écran validé.
