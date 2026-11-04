# Vente directe — destination exacte et transparence des zones (V1)

## Problème constaté

Le checkout géolocalisé enregistre déjà les coordonnées du point de livraison
dans le snapshot immuable `direct_orders.delivery_address_snapshot`, mais
l'API privée des commandes n'exposait que la région et les descriptions.
La gérante ne voyait donc aucun point exploitable pour organiser l'expédition.

La boutique publique ne rendait pas visible la politique de livraison
(`ALL`, `SELECTED`, `DISABLED`) avant d'ouvrir le panier.
Le contrôle serveur de couverture GPS existait déjà, mais son message
était trop peu explicite côté cliente.

## V1 — fonctionnement attendu

### Gérante

- Le détail d'une commande de livraison **GPS validée** contient désormais
  latitude/longitude provenant du snapshot figé à la création de la commande.
- La gérante voit le repère dans une carte **en lecture seule** avec zoom
  rapproché et peut ouvrir une carte OpenStreetMap ou un itinéraire Google
  Maps, par une action explicite.
- Aucun jeton privé de la cliente, clé API, numéro de téléphone ou adresse
  n'est ajouté à l'URL des cartes : seuls les deux nombres WGS84 sont transmis
  au fournisseur externe lorsque la gérante clique sur un lien.
- Une adresse **saisie manuellement** reste sans coordonnées artificielles ;
  elle est explicitement signalée comme non vérifiée.
- L'API demeure strictement **`VIEW_ORDERS` + organisation de la commande**,
  en lecture seule et `Cache-Control: no-store`. La liste des commandes et
  la route publique de suivi ne diffusent pas ces coordonnées.
- Aucune livraison effective ou remise n'est autorisée par l'affichage du
  repère. La vérification du paiement et les états serveur restent obligatoires.

### Cliente

- La boutique affiche, **avant le panier**, les régions que la gérante a
  autorisées. Si la livraison est désactivée, seul le retrait est indiqué.
  Si la politique n'est pas joignable, on n'affiche jamais à tort que toutes
  les régions sont desservies.
- À la sélection du mode livraison, les mêmes régions sont visibles avant
  la demande de localisation GPS.
- Après confirmation du repère, le résultat du contrôle de région effectué
  par le backend est explicite : **Zone desservie** ou **Zone non desservie**,
  avec le nom de la région lorsque celle-ci est connue.
- L'état affiché est un **contrôle de la région** et non une attestation de
  présence physique, de route praticable, de livraison effectuée ou de
  paiement. Le serveur vérifie à nouveau les limites et le montant lors
  de la création de la commande.
- Une zone hors périmètre ne permet pas de finaliser la commande en mode
  livraison. Le retrait ou un autre point restent possibles.

### Cartographie

Le style MapLibre/OpenFreeMap passe de `positron` (cartographie minimale)
à `liberty`, qui affiche davantage de noms de routes et points d'intérêt
sur les données OSM existantes. La carte ne peut pas faire apparaître des
routes ou bâtiments absents des données OpenStreetMap ; les liens externes
permettent de consulter des cartes alternatives.

## Hors périmètre de cette tranche

La simplification profonde du parcours de commande (moins de clics et
regroupement des étapes) est une **étape UX ultérieure**. Les preuves de
règlement, les règles de livraison, les zones et l'isolation des boutiques
restent inchangées. Pas de nouvelle API cartographique payante ni de
géolocalisation non consentie.
