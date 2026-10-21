# Groupia — Charte d'expérience produit et répartition des pages (V2)

**Statut : DÉCISION PRODUIT VALIDÉE LE 21 OCTOBRE 2026.** Séparation Accueil = pilotage quotidien des groupages ; Statistiques = analyse de l'évolution commerciale. Les détails visuels restent à prototyper et valider avant merge. Aucun changement des règles métier ou des autorisations n'est implicitement accordé.

**Contexte :** revue du dashboard de la PR #29, de la navigation Angular et des pages Groupages, Commandes, Paiements, Versements et Boutique le 21 octobre 2026. Ce document est la mémoire de conception inter-pages : toute PR UX doit consulter et mettre à jour la matrice avant d'ajouter ou de dupliquer une information.

## 1. ADN Groupia

Groupia n'est ni Shopify ni un logiciel comptable. Groupia permet à une organisatrice de préparer un groupage, partager son lien (notamment via WhatsApp), centraliser les achats, suivre l'avancement et organiser la livraison/restitution.

Promesse UX : **« Je sais ce qui se passe dans mes groupages, ce que je dois faire ensuite, et si mon activité avance. »**

Personnalité : chaleureuse sans infantilisation, sobre sans froideur, fiable sur l'argent et les statuts, rassurante sur les étapes, efficace sur mobile. Ne jamais créer des fonctions ou indicateurs pour embellir un écran.

Principes de décision (ordre de priorité) :
1. Action et compréhension en moins de 5 secondes : situation / prochaine étape / où cliquer.
2. Une intention principale par écran ; les détails résident dans leur page de référence.
3. Informations pertinentes à la phase réelle du groupage (brouillon, publié, fermé, fournisseur, transport, distribution, clôture).
4. Fiabilité : les données monétaires, périodes et états doivent être explicitement définis.
5. Une seule action visuellement dominante dans une zone.
6. Défilement mobile court avant les premières informations utiles ; progressivité plutôt qu'un mur de cartes.
7. Accessibilité, simplicité de saisie et performance avant animation.

## 2. Accueil ≠ Statistiques : décision validée

Aujourd'hui `/app/accueil` contient KPI de ventes, courbe 12 mois, 4 compteurs opérationnels, dernières commandes et 4 raccourcis. La page dépasse largement un écran mobile avant que l'organisatrice n'atteigne ses groupages.

**Cible validée :**
- **Accueil (`/app/accueil`) = cockpit de travail du jour**, personnalisable par situation, pas page de rapport.
- **Statistiques (`/app/statistiques`, nouvelle route à évaluer) = suivi et comparaison de l'activité**, avec la courbe et les détails de 3/6/12 mois de la PR #29.
- Pas de nouvel onglet mobile primaire par défaut : entrée « Voir les statistiques » depuis l'aperçu d'accueil, éventuellement dans les pages secondaires du menu Plus. Évaluer l'usage avant de créer une entrée top-level supplémentaire.
- Garder un aperçu commercial compact sur l'accueil (ex. ventes d'articles confirmées **ce mois-ci** si une API le fournit exactement), **sans refaire** la courbe détaillée ni les sélecteurs.
- Pas de donnée synthétique ou de compteur temps réel fictif en production.
- Revue à mener sur route, titres, permissions et compatibilité avec les tests existants avant implémentation.

### Ordre d'apparition proposé — accueil mobile

1. **Petit en-tête** : boutique active, « Accueil », une phrase utile facultative. CTA principal « Créer un groupage » visible mais compact.
2. **À suivre** : **seulement** des actions réellement faisables par la gérante, avec libellés précis et liens directs. Exemples candidats (conditionnels aux vraies API) : partager un groupage publié, finaliser un brouillon, consulter une anomalie de paiement qu'elle a le droit de résoudre, préparer la distribution après arrivée. Un « paiement client en attente » n'est **pas automatiquement** une action gérante.
3. **Mes groupages en cours** : 1 à 3 groupages récents/pertinents, statut clair, prochaine échéance/étape si réellement disponible, bouton vers le détail. Le détail de groupage reste propriétaire de la totalité du cycle.
4. **Mon activité** : 1 ou 2 chiffres maximum, explicitement datés, lien « Voir les statistiques ». Pas de promesse de bénéfice, revenus encaissés ni reversements.
5. **Dernières commandes** : aperçu de 2 à 3 lignes seulement si cela apporte de l'information au-dessus de la page Commandes ; lien vers liste complète.
6. Pas de « Pour la suite » de quatre liens répétant les éléments déjà visibles. Afficher des raccourcis seulement pour une tâche absente des sections précédentes.

### Desktop

En-tête + action. Une zone principale « À suivre » et « Groupages en cours », un aperçu économique compact en complément latéral si la largeur le permet. Les commandes récentes ensuite, sans répéter les compteurs de ventes. Le changement de largeur ne change pas l'ordre logique clavier/lecture d'écran.

### Novice vs organisatrice active

Si aucun produit ni groupage : onboarding contextuel **(1) Ajouter des produits, (2) Créer un groupage, (3) Publier et partager** ; ne pas montrer 7 cartes à zéro ou un graphique géant vide. Adapter seulement aux données disponibles.

Si groupages actifs : afficher l'état et les tâches à faire avant les analyses rétrospectives.

Si tout est terminé : montrer synthèse et invitation à relancer un groupage, sans urgence inventée.

## 3. Matrice des propriétaires d'information

| Écran | Rôle / propriétaire de l'information | Peut résumer ailleurs | Ne doit pas être dupliqué ailleurs |
| --- | --- | --- | --- |
| **Accueil** | Priorités **actionnables**, quelques groupages en cours, mini synthèse datée | Synthèse de commandes ou ventes avec lien | Graphique complet, tableau de commandes, état détaillé des paiements |
| **Statistiques** (validé) | Historique des ventes d'articles confirmées et commandes, comparaisons N−1, périodes et courbes | Un ou deux KPI explicitement datés à l'accueil | Statuts des remboursements, gains nets, soldes de reversement |
| **Groupages** | Recherche/liste filtrable des campagnes, statut, échéance et navigation | 1–3 campagnes pertinentes à l'accueil | Édition du contenu et du cycle, détails de transport |
| **Détail du groupage** | **Source de vérité du cycle** : offre, prix, partage, achats associés, fournisseur, transport, distribution, clôture | Statut/étape/alerte vérifiée sur l'accueil | Analyses globales par période, gestion complète de la boutique |
| **Produits** | Catalogue durable, variantes, médias R2, activation et prix de référence | Raccourci « Ajouter un produit » en onboarding | Prix propres aux offres de groupages comme si c'était le prix catalogue |
| **Commandes** | Liste complète, recherche/filtres, suivi et détail d'une commande | 2–3 récentes en accueil, lien ciblé | Agrégation commerciale annuelle, gestion des litiges de paiement |
| **Paiements** | Obligations, pay-ins, anomalies/revues et actions permises | Alerte actionnable à l'accueil si vérifiée | Trésorerie disponible, historique des reversements ou bénéfice |
| **Versements** | Soldes marchands, destination, demandes et états de payout | Statut récapitulatif seulement si exact et utile | Ventes d'articles assimilées aux fonds disponibles |
| **Boutique** | Identité de l'organisation active et choix de boutique | Nom/identité dans shell et accueil | Mélanger abonnements/versements avec configuration de catalogue |
| **Abonnement** | Plan et facturation de Groupia | Éventuelle alerte de renouvellement réellement justifiée | Statistiques de vente et suivi des groupages |
| **Boutique publique** | Découverte/commande côté cliente | Aucun widget gestionnaire | Informations privées de gérante, historique de l'organisation |

**Règle anti-redondance :** une donnée détaillée a **une page propriétaire** ; l'accueil peut afficher un extrait à finalité différente **et un lien contextualisé**. Une métrique recopiée doit garder exactement sa définition, son périmètre de temps et son statut financier. Ne jamais mettre « Aujourd'hui » sur un comptage de toutes périodes.

## 4. Hiérarchie typographique (à tester, pas à imposer aveuglément)

L'existant utilise Poppins partout, base 16px, corps interligne 1.65, titres pouvant atteindre 40px et plusieurs chiffres 30px+ ; le rendu mobile paraît chargé.

Cible indicative :
- **Titre de page :** 24–28px desktop, 22–24px mobile, poids 600 ; un seul titre dominant.
- **Titre de section :** 18–20px, poids 600.
- **Titre de carte / commande :** 15–16px, poids 500/600 selon contexte.
- **Corps et contrôles :** 15–16px, éviter le retour global au 13px ; zoom texte 200 % obligatoire.
- **Légendes courtes :** 13–14px pour métadonnées seulement, contraste contrôlé ; jamais une explication indispensable uniquement en tout petit.
- **Valeur principale :** 24–30px, une seule mise en avant forte par zone ; valeurs secondaires 20–24px si nécessaire.
- **Interligne corps :** viser 1.45–1.55 pour l'UI compacte, conserver 1.6 sur les paragraphes longs.
- **Poppins** pour identité et titres ; comparer avec une police système lisible pour les contenus denses **sans ajouter une font distante**. Valider visuellement sur le mobile réel.
- Éviter les labels TOUT EN CAPITALES répétés, les textes d'accueil génériques et les icônes sans action.

## 5. Couleurs et composants

**Couleurs identitaires à garder :**
- Vert profond / turquoise : continuité, confiance, actions principales et courbe métier, actuellement `--primary: #0f766e`, `--sidebar: #063d39` (clair).
- Orange `#ff7a00` : un **CTA prioritaire par contexte** ou petite signature de marque, pas toutes les cartes ni tous les badges.
- Fonds crème/gris très doux `#f6f7f4` et cartes blanches pour hiérarchie. Variante sombre existante préservée, avec surfaces différenciées et contrastes mesurés.
- Les couleurs de statuts restent **sémantiques** (succès/alerte/danger/info), jamais décodées uniquement par la couleur.

**Équilibre proposé :** neutralité sur 80–90 % des surfaces, vert pour navigation et sélection, orange très sélectivement. Pas de gros dégradés, de halos ou de verre translucide systématique. Utiliser l'espace, le poids typographique, la proximité et des frontières légères avant d'ajouter une carte.

**Composants :** rayons cohérents (petits contrôles et cartes légèrement arrondies), ombres discrètes seulement si utiles, espaces sur grille 4/8px, contrôles tactiles généralement >=44px, focus visible, skeletons stables, error/empty/success contextuels. Vérifier l'internationalisation de « FCFA » et les changements de devise si un futur marché l'exige.

## 6. Motion, performance et confiance

Animations **fonctionnelles** seulement : réponse au clic/tap, progression vérifiée, changement d'onglet/étape ou feedback de succès, typiquement 120–200 ms et `prefers-reduced-motion`. Pas de mouvement permanent ni de courbes qui réaniment à chaque retour sur l'accueil.

**Accueil rapide :** minimiser les requêtes (le dashboard actuel lance 1 agrégation analytics + 4 listes de commandes) ; évaluer endpoint dédié pour 1 à 3 priorités et campagnes avant d'ajouter des widgets. Afficher la structure et les tâches utiles d'abord, réserver les courbes et comparaisons détaillées à la page Statistiques.

Contrats monétaires explicites : le `goodsSales` actuel désigne **valeur brute des articles** des commandes confirmées/terminées, avant remboursements et corrections, hors frais ; **ne signifie pas** profit, encaissement ou fonds transférables. Conserver filtre organisation / permission / protections contre réponses périmées.

## 7. Contrôle de cohérence à chaque nouveau sprint

Avant de coder une page, renseigner dans la PR :
1. Quelle question précise l'utilisatrice se pose-t-elle en entrant ?
2. Quelle est l'action principale ?
3. Quelle page **possède** ces données et permet leur consultation/modification complète ?
4. Une autre page montre-t-elle déjà ces chiffres ? Si oui, pourquoi garder les deux ?
5. Quelle différence pour une boutique vide, active ou terminée ?
6. Quelles API réelles rendent le composant possible ? L'absence de données n'autorise pas une fausse statistique.
7. Captures **avant / après** mobile (390), desktop (1280), clair / sombre et états chargement / erreur / vide.
8. Mesurer la densité au premier écran, le temps jusqu'à l'action, le reflow, les cibles tactiles, les labels et la taille des bundles.
9. Confirmer la cohérence avec la charte ou **mettre à jour la charte dans la même PR**, en documentant les arbitrages.

**Première itération à prototyper :** séparation Accueil / Statistiques et ordre des tâches ci-dessus, sans modifier la logique métier. Faire valider les captures comparées **avant** de fusionner une refonte supplémentaire.

## Références de conception

- Shopify App Design Navigation et Information Architecture : https://shopify.dev/docs/apps/design/navigation
- Shopify App Visual Design : https://shopify.dev/docs/apps/design/visual-design
- Polaris Pro Design Language : https://polaris-site-prod-kit.shopify.prod.shopifyapps.com/design/pro-design-language

Ces références servent pour la méthode, **pas pour copier l'identité graphique**.
