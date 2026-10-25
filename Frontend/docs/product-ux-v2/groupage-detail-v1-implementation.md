# Groupia — Détail d'un groupage V1

**Statut : en revue, ne pas fusionner avant CI et validation visuelle.** Cette itération suit la navigation V6, l'Accueil V3/V3.4 et la liste Groupages V1.

## Intention

Répondre rapidement aux questions : *Quel est le statut du groupage ? Quelles informations vérifier ? Où continuer mon travail ?*

- En-tête Groupia cohérent avec les pages déjà validées : retour à Groupages, titre, badge d'état et phrase contextuelle.
- Trois repères **issus du groupage réel** : clôture à l'heure **Africa/Dakar**, nombre d'articles présents dans `offers`, et disponibilité du lien public. Ne pas assimiler le nombre d'articles au nombre de commandes.
- Partage visible uniquement pour statut OPEN et identifiant public existant ; conserver les boutons « Copier le lien » et « Voir comme une cliente ».
- Onglets et sections métier existants laissés en place : aperçu, produits, commandes, fournisseur, distribution, paramètres.
- Conditions de livraison structurées en paires libellé/valeur et non en texte délimité ambigu.
- Aucun nouvel indicateur financier, aucune action métier simulée. Les paiements/reversements restent des composants propriétaires.
- Les textes « prochaine étape » sont **informatifs**. Aucun droit ou statut d'action n'en découle ; les contrôles existants restent la seule autorité.

## Protection contre les régressions identifiées

1. **CSS isolé** dans `groupage-detail.page.scss` de taille modeste ; ne jamais augmenter le budget de compilation.
2. **Playwright** : viser les conteneurs précis `.detail-hero`, `.shipping-summary`, `.share` et les noms de rôles exacts (pas les expressions génériques ambiguës).
3. Préserver les assertions existantes sur les actions `Publier le groupage`, `Fermer les commandes`, validation, onglets, conflit de version et liens publics. Adapter les captures *après* mise à jour des écrans, pas supprimer des tests pour les faire passer.
4. **Ne pas toucher à la navigation V6** ni au panneau de fermeture à droite ; couvrir les breakpoints 390/1280 et états DRAFT/OPEN, light/dark et vérifier aussi 360/430/768/1440.
5. Préserver l'isolation par organisation et les droits de mutation ; aucun changement backend, API générée, migration ou contrats financiers.
6. Valider Security/Backend/Frontend, tests unitaires, lint, build, E2E et captures Playwright avant merge.

## Limites de cette PR

- Ce n'est pas la refonte complète des panneaux logistiques / versement : ils nécessitent chacun un audit des règles métier.
- Aucun calendrier d'étapes fictif, nombre de clients, recettes ou dates extrapolées.
- La vente directe reste hors périmètre.

