# Accueil Groupia — intégration visuelle V3/V3.4

**Statut : en cours de validation visuelle.** Base : navigation V6 intégrée à `main` via PR #35 et [validation mobile V3.4](accueil-mobile-v3.4-validation.md).

## Ce qui est implémenté

- En-tête compact et contextualisé : `Accueil`, bienvenue, nom de la boutique authentifiée, action principale `Créer un groupage`.
- Priorités : uniquement les brouillons et campagnes ouvertes possédant un identifiant public, selon `HomeStore.priorityLabel` ; aucune fausse alerte, lien vers le vrai détail.
- Deux repères **explicitement qualifiés** : total de groupages retourné par la pagination et nombre d'actions repérées parmi les 20 derniers groupages seulement. Ne pas les appeler groupages ouverts globaux ni commandes à préparer.
- Aperçu des groupages récents hors priorités, sans dupliquer la même campagne.
- Aperçu commercial des 3 mois issus de l'API validée, avec 2 chiffres et 3 mini-barres CSS ; graphique détaillé **uniquement dans Statistiques**. Les ventes sont une valeur brute d'articles confirmés, hors frais, avant remboursements, **ni profit ni reversement**.
- Dernières commandes : 3 extraits en provenance de la véritable API, montant dû initial distinct du montant d'articles.
- Démarrage : sans aucun groupage, étapes progressives catalogue → création → publication/partage, sans métriques ni commandes inventées.
- Design : Poppins, surfaces et tokens Groupia (vert, orange en CTA, clair/sombre), styles **isolés** dans `dashboard-home.scss` afin de ne pas modifier la page Statistiques.

## Limitations métier

La **vente directe n'est pas implémentée** dans l'API ni activée dans la navigation. Le scénario Groupages seul est celui réellement opérationnel. Ne jamais afficher de bascule « Vente directe », de compteurs mixtes, de lien boutique publique direct ni de préparation/livraison imaginaire. Les scénarios mixte/direct prévus dans V3.4 restent des spécifications UX futures.

Le label « actions repérées » désigne uniquement les *deux priorités affichées*, pas une mesure exhaustive des campagnes. Le total groupages provient du `totalElements` de la page 0, et les commandes sont un échantillon de trois. Les ressources sont isolées par session/boutique et leurs erreurs ne doivent jamais devenir des valeurs zéro.

## Critères avant merge

- Vérifier mobile 360/390/430, tablette 768, desktop 1024/1280/1440, clair/sombre et zoom 200 %.
- Vérifier la séquence **priorités → repères → groupages → mini-activité → dernières commandes** ; priorité visible sans long scroll.
- Vérifier boutique vide, API groupages en erreur, API analytics en erreur, réponses retardées lors d'un changement de boutique et session expirée.
- Conserver `HomeStore` (20 campagnes, 3 commandes, 1 produit seulement si boutique vide) et une seule requête analytics 3 mois.
- Examiner les **captures réelles** Playwright et demander validation visuelle avant la fusion. La CI verte n'est pas une validation de design.

**Périmètre :** Angular Accueil seulement, ses tests et sa documentation. Pas de migration, pas de backend, pas de changement de finances ou de navigation V6.
