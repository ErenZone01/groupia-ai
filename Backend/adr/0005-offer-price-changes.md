# ADR-0005 — Prix d'offre modifiables pendant OPEN

- Statut : accepté
- Date : 2026-09-17
- Portée : grouping et placement ordering
- Remplace : la proposition de revue consistant à figer les prix à l'ouverture, qui n'était pas une décision définitive

## Contexte

L'organisateur doit pouvoir modifier une offre pendant une campagne ouverte. L'acheteur ne doit pas accepter involontairement un prix différent de celui qu'il a consulté ; les commandes déjà placées doivent rester intactes.

## Décision validée

- Une GroupingOffer correspond exactement à une variante dans une campagne.
- Le prix peut changer pendant OPEN pour les futurs placements.
- Le checkout revalide côté serveur prix courant et version/cohérence de l'offre.
- Un prix affiché différent du prix courant entraîne un conflit explicite ; aucune substitution silencieuse.
- Les commandes existantes conservent leurs snapshots.
- Toute modification du prix est auditable.

## Protocole cible

1. La consultation publique fournit prix, devise et révision utiles au contrôle.
2. Un utilisateur autorisé modifie le prix ; le système coordonne la mise à jour concurrente et incrémente la révision.
3. Le changement produit atomiquement une trace durable destinée à l'audit : offre/campagne/organisation, acteur, avant/après, devise, révision et instant. Une publication persistante consommée idempotemment par audit peut assurer ce transfert.
4. Au placement, ordering demande à grouping une validation autoritaire de la campagne et des offres. Le prix client est un témoin de consentement, jamais une valeur à facturer.
5. Une lecture suivie d'une écriture sans coordination est insuffisante. Les modifications de prix/clôtures et le placement doivent être sérialisés à leur point de décision, par le protocole transactionnel/contrôle de concurrence du slice.
6. Un écart de prix provoque PRICE_CONFLICT et ne crée aucune commande/obligation. Une version obsolète peut aussi provoquer un conflit de cohérence même si le montant est identique ; le client revalide les conditions.
7. Si les contrôles réussissent, la commande copie le prix et les conditions validés. Toute hausse ou baisse postérieure concerne les placements suivants.
8. Une répétition idempotente d'un placement déjà réussi retourne ce résultat sans lui appliquer un prix plus récent.

## Cas d'acceptation à tester lors du slice

- Hausse et baisse entre consultation et placement : conflit explicite.
- Modification immédiatement après placement : snapshots inchangés.
- Deux modifications organisateur concurrentes : pas d'écrasement silencieux.
- Modification de prix ou clôture concurrente au placement : résultat cohérent avec un ordre de décision déterminé.
- Un panier à plusieurs lignes est refusé intégralement si une ligne présente un conflit, sans placement partiel implicite.
- Rejeu après réponse perdue : pas de doublon et pas de repricing d'une commande existante.
- Ancienne révision avec retour ultérieur au même montant : contrôle de cohérence explicite, pas d'ignorance de la version.
- Publication d'audit rejouée : une seule trace du même changement.

## Conséquences

L'offre courante et le prix historique commandé peuvent légitimement différer. La version globale de l'agrégat et la révision du prix ne sont pas nécessairement la même notion ; leur représentation sera choisie dans le slice.

Les tarifs de transport et règles de frais ne deviennent pas modifiables pendant OPEN par extension implicite de cette décision : leur politique propre reste à préciser, avec préservation obligatoire des snapshots.

## Options écartées

Gel systématique des prix pendant OPEN ; substitution silencieuse ; prix fourni par le client utilisé comme autorité ; recalcul rétroactif des commandes ; simple contrôle avant placement laissant une fenêtre de course.
