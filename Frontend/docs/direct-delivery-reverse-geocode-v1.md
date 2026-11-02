# Ventes directes — suggérer une adresse depuis un point GPS (V1)

## Objectif et UX
Après sélection et confirmation explicite du repère MapLibre/OpenFreeMap, la page
cliente **peut afficher** une adresse estimée par Geoapify (pays/région, ville,
quartier si fournis). Aucune commune n'est déduite arbitrairement d'un "district"
ou d'un quartier. Le nom du lieu est **indicatif** et ne remplace jamais le point
confirmé ni le contrôle de livraison côté serveur.

L'écran reste simple : GPS → vérifier/déplacer le repère → confirmer le lieu.
Le retrait et l'adresse saisie sans GPS continuent de fonctionner. Une erreur
du fournisseur ou l'absence de clé API ne bloque pas le récapitulatif.

## Backend et coût
Endpoint public avec réponse `Cache-Control: no-store` :
`POST /api/public/direct-stores/{publicIdentifier}/reverse-geocode`
Corps : `{"latitude":14.72,"longitude":-17.46}`.
Réponse : `status` = `FOUND`, `NO_RESULT` ou `UNAVAILABLE`, avec des
champs de localisation tous optionnels. Seules les boutiques publiées sont
autorisées. Les coordonnées invalides sont rejetées (400).

**Désactivé par défaut** :
```properties
GROUPIA_GEOAPIFY_ENABLED=false
GROUPIA_GEOAPIFY_API_KEY=
```
Pour essayer le service, ajouter la clé Geoapify **sur le serveur uniquement** et
activer explicitement `GROUPIA_GEOAPIFY_ENABLED=true`. Ne jamais exposer cette
clé dans Angular ni la committer. Ne pas activer les paiements directs.

La migration additive 035 ajoute deux compteurs globaux partagés PostgreSQL,
sans position ni identifiant personnel :
- 2 400 tentatives par jour UTC (plafond inférieur aux 3 000 crédits gratuits) ;
- maximum trois tentatives par seconde (sous la limite annoncée de cinq/s).

Les compteurs sont atomiques et communs aux instances. Le plafond protège le
budget mais **n'est pas un anti-abus par cliente**. Avant une forte exposition,
ajouter le filtrage IP/réseau **à l'edge** sans faire confiance à
`X-Forwarded-For` et surveiller le quota auprès de Geoapify.

## Confidentialité et limites
La position est envoyée par HTTPS à Groupia puis, seulement si la fonctionnalité
est activée, à Geoapify pour rechercher une adresse. Ni les coordonnées ni la
réponse du fournisseur ne sont stockées dans cette tranche. Les données
géographiques externes ne garantissent pas la commune ou même la région.
L'attribution Geoapify/OpenStreetMap apparaît dans l'interface si une adresse
est affichée.

La **vérification de zone de livraison n'est pas encore implémentée à partir
des polygones administratifs fiables** : une adresse suggérée n'est pas une
preuve de position et ne permet pas d'activer le paiement direct. La politique
régionale V1 existe mais reste provisoire pour les zones vérifiées.
Cette tranche continue à cibler le Sénégal pour les zones de vente ; les autres
pays ouest-africains peuvent être reconnus par leur code pays, sans prétendre
qu'ils sont livrables.

## Vérification
CI : Security, Maven `verify`, lint, tests Angular, build, suite Playwright.
Tests ajoutés : parsing partiel et international, refus GPS invalide,
adresse indisponible sans clé, annulation logique des réponses GPS tardives.

Tests réels : essais à Dakar, Thiès, Pikine et zones rurales ; comparer les
localités renvoyées avec les connaissances terrain avant d'activer le service
commercialement. Vérifier un Android à faible débit, refus GPS et coupure réseau.

Documentation officielle :
- https://apidocs.geoapify.com/docs/geocoding/reverse-geocoding/
- https://www.geoapify.com/pricing/
