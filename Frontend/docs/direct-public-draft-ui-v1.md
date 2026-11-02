# Vente directe — confirmation client et création de brouillon (V1)

## Fonctionnement
Le parcours sans compte affiche les articles, les frais de plateforme et de
livraison recalculés par Spring Boot. La cliente indique ses coordonnées,
choisit le retrait, un GPS confirmé ou une adresse manuelle, puis consulte le
récapitulatif. Si le serveur autorise la création de brouillon et que le tarif
est complet, une case à cocher demande d'accepter **le montant précis affiché**
avant d'enregistrer la commande. Après réussite, la page donne une référence
`DIR-...`, un montant accepté et un avertissement « Aucun paiement effectué ».
La cliente doit conserver cette référence : un suivi public par lien sécurisé
est une tranche suivante, et n'est pas présenté comme déjà disponible.

Le bouton est désactivé lorsque le serveur ne permet pas le checkout, lorsque
la livraison est refusée, lorsque le montant manque ou pendant une requête.
Le checkout reste désactivé **par défaut** avec
`GROUPIA_DIRECT_CHECKOUT_ENABLED=false`. Lorsqu'il est activé explicitement
pour les tests ou un pilote maîtrisé, seul un **brouillon impayé** est créé :
`paymentAvailable=false` et aucune requête Bictorys.

## Contrats
`DirectCartQuoteService.Quote` conserve `checkoutAvailable=false` et ajoute
`draftCreationAvailable`, piloté par le même drapeau backend que le service
de brouillons. Cela sépare clairement une création de demande autorisée et
un moyen de paiement disponible.

Le client POST vers
`/api/public/direct-stores/{publicIdentifier}/checkout` avec
`Idempotency-Key` générée par une source cryptographique. Le corps contient
les informations client, les quantités, les prix et révisions **attendus**,
le mode de remise, la destination (si applicable) et `acceptedTotalXof`.

Le serveur revalide les offres, les régions et le tarif dans la transaction
et refuse `DIRECT_TOTAL_CHANGED` sans créer de commande si le total accepté
ne correspond plus.

## Réessai et données personnelles
Une coupure réseau laisse le même corps et la même clé d'idempotence pour
réessayer **à l'identique** dans le même formulaire. En cas de rejet explicite
400/404/409/503, le devis est rafraîchi et la cliente doit de nouveau
accepter le total. Un changement de boutique réinitialise contacts,
coordonnées, tentative et référence. Les données restent en mémoire
composant, pas en localStorage, et ne sont envoyées qu'à la confirmation.

Limite : recharger ou fermer l'onglet après une défaillance réseau fait
perdre la clé locale. Une récupération sécurisée de commande avec un
jeton opaque sera nécessaire avant ouverture commerciale pour retrouver le
résultat d'une commande créée juste avant une coupure. Ne jamais rendre les
commandes consultables en connaissant seulement leur numéro.

## Vérifications
Tests Angular pour feature flag, consentement, commande sans compte,
réutilisation de clé après erreur réseau, refus d'un prix obsolète et absence
de paiement. Vérifier les tests Playwright, le build, et les scénarios mobiles
avec un backend configuré dans un environnement de test.
