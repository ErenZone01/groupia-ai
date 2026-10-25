# Accueil et Statistiques — revue visuelle de la PR #29

## Deux questions, deux pages

**Accueil : « Que puis-je faire ensuite ? »** Les tâches ouvrent le groupage réel : reprendre un brouillon ou retrouver le lien à partager. Les groupages en transport / autres phases restent à portée de main. Le petit aperçu économique et trois commandes récentes viennent ensuite. Le premier écran mobile montre une action concrète, sans traverser des KPI ni une courbe.

**Statistiques : « Comment évolue mon activité ? »** Les indicateurs commerciaux, la comparaison annuelle et les périodes 3/6/12 sont conservés, avec montants exacts, navigation clavier/tactile et tableau accessible. La page n’ajoute aucun compteur opérationnel ni liste de commandes. « Retour à l’accueil » ramène au travail quotidien.

La typographie de page passe à 22–28 px, les sections à 18 px, les valeurs à 24–30 px maximum. Les surfaces neutres et frontières légères remplacent l’empilement de grandes cartes. L’orange est réservé à la création d’un groupage ; les liens contextuels restent verts. La police Poppins et les deux thèmes sont préservés. Aucun changement global des autres parcours.

## Données et limites

- Accueil : 1 lecture de groupages (20 maximum, sans détail), 1 lecture de commandes (3 maximum), 1 agrégation `months=3`. Une quatrième requête catalogue de taille 1 uniquement quand aucun groupage n’existe.
- Statistiques : 1 agrégation `months=12` initialement, puis un appel par changement de période. Aucun téléchargement de commandes ou de groupages. Aucun double appel analytics à l’entrée dans une page.
- Les 20 derniers groupages sont un échantillon borné, pas une liste complète des tâches. Les priorités sont des liens de navigation ; les permissions et opérations complètes appartiennent au détail existant. Aucune priorité « distribution » tant que le résumé ne fournit pas l’autorisation correspondante.
- Les montants des commandes sont le montant initial à régler, frais inclus ; les ventes d’articles sont hors frais, brutes avant corrections/remboursements. Les deux notions restent explicitement distinctes.
- Le catalogue n’est testé qu’après un total groupages exactement égal à zéro. Les erreurs ne sont jamais converties en zéro ou en onboarding.
- Les résultats sont liés à la génération de session et à la boutique ; les souscriptions obsolètes sont annulées, et les computed masquent immédiatement les résultats d’un ancien contexte. Pas de cache persistant.

## Galerie réelle Playwright

**Les captures contiennent uniquement des fixtures synthétiques isolées dans les tests.** La page Statistiques n’existait pas avant : ses images « avant » montrent le dashboard analytique dont elle reprend les fonctions, à `/app/accueil`. Ce ne sont pas des captures d’une route ancienne fictive.

| Page / format | Clair avant | Clair après | Sombre avant | Sombre après |
|---|---|---|---|---|
| Accueil 390 | [Avant](captures/before-accueil-390-light-populated.png) | [Après](captures/after-accueil-390-light-populated.png) | [Avant](captures/before-accueil-390-dark-populated.png) | [Après](captures/after-accueil-390-dark-populated.png) |
| Accueil 1280 | [Avant](captures/before-accueil-1280-light-populated.png) | [Après](captures/after-accueil-1280-light-populated.png) | [Avant](captures/before-accueil-1280-dark-populated.png) | [Après](captures/after-accueil-1280-dark-populated.png) |
| Statistiques 390 | [Avant](captures/before-statistiques-390-light-populated.png) | [Après](captures/after-statistiques-390-light-populated.png) | [Avant](captures/before-statistiques-390-dark-populated.png) | [Après](captures/after-statistiques-390-dark-populated.png) |
| Statistiques 1280 | [Avant](captures/before-statistiques-1280-light-populated.png) | [Après](captures/after-statistiques-1280-light-populated.png) | [Avant](captures/before-statistiques-1280-dark-populated.png) | [Après](captures/after-statistiques-1280-dark-populated.png) |

Chaque combinaison existe également en états `empty`, `loading` et `error` dans `captures/` (64 images avant/après, plus 10 vues de fenêtre et de détail pour faciliter la revue mobile). L’état vide de l’Accueil guide vers le catalogue ou le premier groupage. Les erreurs restent propres à chaque zone, sans chiffres artificiels.

## Vérifications et performance

Les résultats d’exécution définitifs et la CI du SHA poussé sont consignés dans la PR. Les tests unitaires couvrent les limites de requêtes, les priorités, l’onboarding, les erreurs indépendantes, l’annulation et le masquage immédiat au changement de contexte. Les assertions financières existantes sont déplacées sur Statistiques, sans suppression des protections. Les anciens tests des quatre compteurs sont adaptés à la suppression intentionnelle de ces widgets et vérifient désormais la lecture bornée des trois commandes réelles.

Playwright couvre les deux pages aux largeurs 360/390/430/768/1024/1280/1440, deux thèmes, texte agrandi à 200 %, focus visible, clavier, menu Plus, déconnexion/expiration et réponses tardives. La première priorité de l’Accueil doit apparaître avant 600 px de hauteur. Les tests vérifient aussi le nombre d’appels et la navigation vers le détail et Statistiques. Limite : Chromium et zoom texte ; pas d’audit manuel exhaustif avec tous les lecteurs d’écran.

Mesures comparées au dashboard analytique précédent de la PR : `bundle-measurements.json`.

| Mesure | Avant séparation | Après séparation |
|---|---:|---:|
| Bundle initial brut (rapport Angular) | 317,39 kB | 317,51 kB |
| Chunk de page Accueil brut | 40,74 kB | 20,27 kB |
| Chunk de page Statistiques brut | inclus dans Accueil | 28,08 kB |
| Tous fichiers JS bruts | 903 013 octets | 914 693 octets |
| Tous fichiers JS gzip séparé | 273 178 octets | 279 181 octets |
| Dépendances ajoutées | — | 0 |

Le surcoût total est de 6 003 octets gzip (+2,2 %), avec une répartition entre deux pages lazy. La mesure est la somme des fichiers `.js` compressés séparément, niveau gzip 6 ; elle inclut les chunks partagés, donc les chunks des deux pages ne doivent pas être additionnés comme mesure réseau. La base est le build de la précédente livraison analytique (le commit documentaire intermédiaire n’a changé que le libellé des compteurs). Aucune dépendance ajoutée. Le graphique est désormais dans le chunk lazy de Statistiques, pas dans celui de l’Accueil. Le total gzip de tous les fichiers n’est pas la quantité téléchargée sur chaque page.

Commandes : `npm ci`, `npm audit --audit-level=high`, `npm run lint`, `npm test`, `npm run build`, `npm run test:e2e`, contrôles Python des migrations/secrets et `git diff --check`. Aucun changement backend/migration ; Maven reste vérifié par le workflow CI habituel. Les avertissements NG8107 existants du transport sont hors périmètre.

**Aucun merge. Les captures doivent être validées visuellement par le propriétaire du produit.**

## Vues mobiles à présenter pour validation

Les captures pleine page montrent naturellement la barre mobile fixe à la hauteur de la fenêtre. Ces vues complémentaires évitent qu’elle masque une valeur située plus bas dans la capture :

- Accueil, première fenêtre : [clair](captures/after-accueil-390-light-viewport.png) / [sombre](captures/after-accueil-390-dark-viewport.png).
- Accueil, activité après défilement réel : [clair](captures/after-accueil-390-light-activity.png) / [sombre](captures/after-accueil-390-dark-activity.png).
- Statistiques, première fenêtre : [clair](captures/after-statistiques-390-light-viewport.png) / [sombre](captures/after-statistiques-390-dark-viewport.png).

La barre n’est pas masquée ni modifiée pour les captures. Tous les écrans proviennent du navigateur Playwright, sans retouche.
