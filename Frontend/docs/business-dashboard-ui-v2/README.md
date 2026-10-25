# Business Dashboard UI V2

Base : `main` à `3b983b285ecf645e371fe5f82f77a0ca4fa70593` (PR #28 intégrée).
Branche : `business-dashboard-ui-v2`. Aucun changement backend, migration ou règle métier.

## Diagnostic et choix UX

Le dashboard précédent donnait quatre compteurs par statut actuel et les cinq dernières commandes. Une grande bannière de création occupait le haut de page. Il ne montrait ni évolution commerciale ni comparaison temporelle. L'ancien `sales-chart.ts` était une démonstration à sept valeurs et libellés fixes ; il n'a pas été connecté à la production.

La nouvelle page met la boutique active et « Vue d’ensemble » en tête, avec un CTA compact vers `/app/groupages/nouveau`. Le composant `BusinessOverview` rassemble les périodes 3/6/12 mois, trois indicateurs commerciaux et la courbe. La zone opérationnelle reste séparée et explicitement « Toutes périodes ». Les commandes récentes et leurs liens réels sont conservés, sans exposition de coordonnées clientes.

- Ventes d’articles confirmées, commandes confirmées et panier moyen d’articles : valeurs agrégées reçues du serveur, sans recalcul frontend.
- Comparaison avec la même période de l’année précédente, y compris le mois partiel ; aucune variation inventée lorsque la base est nulle.
- Montants bruts avant remboursements/corrections, hors frais de plateforme et transport. Le sens financier est visible dans l’interface et ne doit pas être transformé en bénéfice, encaissement ou reversement.
- SVG sans bibliothèque additionnelle : deux séries, points exacts, grille, axes, sélection d’un mois au survol, au doigt ou avec les contrôles clavier. Les segments droits évitent les dépassements artificiels d’une interpolation lissée entre les observations mensuelles.
- Inspecteur stable sous le graphique, plutôt qu’une infobulle qui masque les points sur smartphone ; tableau natif accessible et dépliable. Le dernier mois est sélectionné initialement. La taille du SVG suit son conteneur avec ResizeObserver : ses libellés ne sont pas réduits à quelques pixels sur mobile.
- Les thèmes, Poppins et les tokens existants sont réutilisés. La série N−1 est différenciée par un tireté et un libellé, pas seulement par sa couleur. Les transitions utilisent la durée existante de 160 ms et la règle globale reduced-motion.

Le test d’agrandissement du texte à 200 % a révélé un débordement préexistant du bandeau supérieur. Une correction CSS limitée autorise le retour à la ligne ; aucune navigation ni gestion du focus du menu Plus n’a été réécrite. La grille des compteurs s’adapte maintenant aussi à la taille du texte.

## API et isolation

Source réelle : `http://localhost:8080/v3/api-docs`, synchronisée avec `npm run api:generate` (workflow officiel `tools/generate-api.mjs`). Le backend était déjà actif ; une tentative de second démarrage a échoué uniquement parce que son port était occupé. Aucun fichier généré n’a été modifié à la main.

La comparaison sémantique des snapshots OpenAPI ne relève que l’endpoint analytics, `BusinessAnalyticsView`, `MonthlyPoint` et l’URL de serveur générée. Le diff textuel plus large vient du formatage JSON du script officiel. `provideApiConfiguration('')` conserve les appels same-origin existants en production.

Contrat consommé : `BusinessAnalyticsControllerService.businessAnalytics({ organizationId, months })`, soit `GET /api/organizations/{organizationId}/analytics/business?months=3|6|12`. La devise attendue est XOF et le fuseau Africa/Dakar. Les dates, sommes, nombres de commandes, moyenne, variations et points mensuels proviennent intégralement de cette réponse.

`BusinessAnalyticsStore` associe chaque résultat à la génération de session, l’organisation, la période et la demande de retry. Les computed masquent immédiatement les résultats dont cette clé n’est plus courante, avant même l’effet de chargement suivant. L’ancienne souscription est annulée et ses callbacks vérifient encore la clé. Des changements de période ne rechargent pas les compteurs opérationnels. `OrdersDashboardStore` protège également ses données affichées lors d’un changement de boutique ou de session.

À l’entrée : une requête analytics et les quatre lectures opérationnelles existantes (au plus cinq résumés de commandes récents, puis trois pages d’une ligne utilisées pour leurs totaux). À chaque nouvelle période : une seule requête analytics, aucune pagination complète des commandes. Aucune cache persistante de ces données financières.

## Précision et limites explicites

Le contrat OpenAPI transporte des nombres JSON. Tout montant XOF ou compteur non entier, négatif ou supérieur à `Number.MAX_SAFE_INTEGER` est refusé avant affichage. Les valeurs sûres sont formatées exactement ; une réponse non représentable produit un état d’erreur de précision, jamais un montant approximatif présenté comme exact. Une prise en charge au-delà de cette borne nécessitera un contrat de chaînes décimales, hors périmètre. Les moyennes/variations nulles sont distinguées de zéro.

Les tailles de captures et les contrôles de reflow couvrent Chromium. Le zoom est vérifié avec un agrandissement de la police racine à 200 % sur chacune des sept largeurs ; les petites largeurs couvrent aussi la redistribution de contenu qu’impose un zoom de page. Il ne s’agit pas d’un audit manuel avec tous les lecteurs d’écran ou tous les navigateurs.

## Captures Playwright réelles

**Toutes les valeurs visibles dans ces captures sont des fixtures synthétiques de tests**, isolées dans `e2e/business-dashboard-fixture.ts` et interceptées côté navigateur. Aucune fixture n'est importée en production ni envoyée au backend.

Chaque combinaison desktop 1280/mobile 390 et clair/sombre possède les états `populated`, `empty`, `loading`, `error`, avant et après : 32 captures dans `captures/`. Les captures « avant » ont été prises avant modification du dashboard. Les captures « après » sont produites par `business-dashboard.visual.spec.ts`. Les vérifications responsive complémentaires couvrent 360, 390, 430, 768, 1024, 1280 et 1440 px dans les deux thèmes, tableau ouvert et texte à 200 %.

| Vue avec données de test | Avant | Après |
|---|---|---|
| Desktop clair | [Avant](captures/before-1280-light-populated.png) | [Après](captures/after-1280-light-populated.png) |
| Desktop sombre | [Avant](captures/before-1280-dark-populated.png) | [Après](captures/after-1280-dark-populated.png) |
| Mobile clair | [Avant](captures/before-390-light-populated.png) | [Après](captures/after-390-light-populated.png) |
| Mobile sombre | [Avant](captures/before-390-dark-populated.png) | [Après](captures/after-390-dark-populated.png) |

Exemples d’états : [aucune vente](captures/after-390-light-empty.png), [chargement](captures/after-390-dark-loading.png), [erreur](captures/after-1280-light-error.png).

## Mesures et validations

Les mesures et résultats finaux sont consignés dans `validation.md`. Les tailles comparent le build production avant/après sur la même machine. Le total gzip est la somme des fichiers compressés séparément ; ce n’est ni une mesure de latence réseau ni la taille téléchargée sur une seule route. Aucune dépendance ajoutée, aucun changement de lockfile.

Les tests préexistants gardent leurs garanties. Seuls le titre intentionnellement modifié, l’autorisation du nouvel endpoint dans le test de trafic réseau, les fixtures analytics et le contexte de session des tests du store opérationnel ont été adaptés. Les diagnostics de débordement et les contrôles de contraste ont été renforcés.
