# ADR-0004 — Checkout public sans compte et suivi privé protégé

- Statut : accepté
- Date : 2026-09-17
- Portée : parcours public grouping/ordering
- Complément : [ADR-0005, prix évolutifs](0005-offer-price-changes.md)

## Contexte

Un acheteur public doit pouvoir commander sans compte. Une URL publique et un téléphone déclaratif ne suffisent cependant pas à autoriser l'accès à une commande privée.

## Décision validée

- Customer reste distinct de UserAccount ; aucun compte obligatoire.
- Une commande correspond à une organisation, une campagne et une devise ; XOF uniquement au MVP.
- Le téléphone Customer n'est pas unique et ne déclenche aucune fusion automatique sans vérification.
- Le système résout l'organisation à partir de la campagne publique.
- Une référence imprévisible ne vaut jamais autorisation.
- Le suivi privé utilise un jeton aléatoire conservé seulement sous forme d'empreinte/hash.
- Les conditions commerciales sont revalidées côté serveur et snapshotées au placement.

## Parcours attendu

Le client transmet campagne, offres, quantités, informations de contact et prix/versions observés. Les identifiants et montants ne sont pas réputés fiables : le système résout l'organisation, vérifie campagne/offres/devises et recalcule les montants.

L'admission de la campagne et l'acceptation du prix sont cohérentes avec le placement. La transaction courte peut couvrir la validation via l'API grouping et la création dans ordering, sans accès aux repositories voisins. Aucun appel prestataire dans cette transaction.

Une clé de checkout et une empreinte du contenu évitent les doubles placements. Un rejeu d'un placement réussi retrouve le résultat commercial accepté sans recalculer le prix courant. Une clé réutilisée avec un contenu différent est rejetée. Cette clé ne donne pas accès à des données privées à elle seule.

Après commit, ordering assure les étapes idempotentes de création de l'évaluation nécessaire et des obligations. Une reprise après crash ne crée ni seconde commande ni seconde obligation.

Le jeton privé est généré avec une source cryptographiquement sûre, remis via un canal adapté et absent des journaux. Seule l'empreinte est persistée ; expiration, révocation et limitation des essais protègent son usage. La récupération d'un jeton perdu ne peut pas consister à restituer un secret depuis son empreinte.

## Conséquences

Le client n'a pas à devenir un utilisateur. Le système conserve des contacts éventuellement doublonnés plutôt que fusionner sans preuve. Les instantanés de commande survivent aux changements de catalogue, de contact et de tarifs.

Un endpoint public ne doit exposer ni paiements internes, ni données organisateur, ni commandes privées. Une organisation transmise par le navigateur ne devient jamais une autorité.

## Options écartées

Compte obligatoire ; unicité/fusion globale par téléphone ; accès privé par UUID ou numéro de commande ; stockage du jeton en clair ; confiance dans les montants envoyés par le client.

## Questions restantes

Coordonnées minimales nécessaires au parcours, durée du jeton et politique de récupération/renouvellement avec preuve. Le protocole de rejeu d'un checkout après perte de réponse doit préserver le stockage hashé et ne pas transformer la clé d'idempotence en accès public à une commande.
