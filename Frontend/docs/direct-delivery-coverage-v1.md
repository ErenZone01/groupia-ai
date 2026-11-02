# Vente directe — vérification des régions de livraison (V1)

## Ce qui change
La gérante sélectionne les régions dans la configuration existante.
Après avoir confirmé un point sur la carte, la cliente reçoit une indication
simple du serveur :
- **INSIDE_ZONE** : le point choisi se trouve dans une région desservie ;
- **OUTSIDE_ZONE** : le point choisi se trouve dans une autre région du Sénégal ;
- **OUTSIDE_SENEGAL** : aucun des 14 polygones sénégalais ne couvre le point ;
- **UNCERTAIN** : près d'une frontière géographique ou intersection ambiguë ;
- **DELIVERY_DISABLED** : la boutique ne propose pas de livraison.

L'interface empêche l'étape récapitulative pour un **point carte connu hors zone**
mais garde le retrait et la saisie d'une adresse manuelle. Un échec de requête
ne bloque pas une prévisualisation, car **aucune commande ni paiement n'est créé**.

## Source géographique et audit
Source : geoBoundaries (Government of Senegal / OCHA ROWCA), SEN ADM1,
2019, données construites fin 2023, licence **CC BY 3.0 IGO**.
Fichier original simplifié et épinglé à la révision `9469f09` :
`https://github.com/wmgeolab/geoBoundaries/raw/9469f09/releaseData/gbOpen/SEN/ADM1/geoBoundaries-SEN-ADM1_simplified.geojson`

SHA-256 de l'original :
`ea2855a2882503b7ed479b3341168002a47035107e423366cb086f401950a57d`.

14 régions ; normalisation des noms (`Thies` → `Thiès`, etc.)
afin de respecter le référentiel des gérantes.
**Aucune requête géographique externe** n'est nécessaire pour la détection des
régions : le fichier est livré avec le backend. Pas de coût d'API pour
la détection elle-même.

Le calcul est effectué en WGS84 (`longitude, latitude`). Les anneaux des
MultiPolygon et les trous sont pris en compte. Un point très proche d'un
segment de contour est considéré **incertain** plutôt que déclaré livrable.
Les limites datent de 2019 et leur précision / leur actualité devront être
vérifiées sur le terrain, notamment aux frontières, côtes et nouveaux
découpages.

## Contrat et sécurité
`POST /api/public/direct-stores/{publicIdentifier}/delivery-coverage`

Corps : `{"latitude":14.6937,"longitude":-17.4474}`.

Réponse : `{"status":"INSIDE_ZONE","region":"Dakar","preliminaryAllowed":true,"boundarySource":"geoBoundaries-SEN-ADM1-2019"}`

Le serveur vérifie que la boutique publique existe et applique **sa propre
politique de zones**. Aucune région envoyée par le navigateur n'est utilisée
comme preuve. Ni coordonnées ni réponses ne sont enregistrées en base.
La réponse utilise `Cache-Control: no-store`.

**Limite de sécurité :** le backend contrôle seulement l'appartenance d'un
*point soumis* à un polygone. Il ne peut pas certifier que la cliente se
trouve physiquement à cet endroit : elle peut demander une livraison à
une autre adresse, ou falsifier les coordonnées. L'autorisation de commande
et le paiement devront valider une destination effectivement déclarée,
persistée et acceptée, avec des frais de livraison explicites. La vérification
doit être reprise **lors du checkout** et jamais uniquement dans Angular.

La création des commandes directes reste désactivée par défaut via
`GROUPIA_DIRECT_CHECKOUT_ENABLED=false`. Cette tranche ne touche pas
aux paiements, au stock ou aux livraisons de groupage. Ne pas activer le
paiement direct sur ce seul précontrôle.

## Vérifications
Tests Java : Dakar, Pikine, Rufisque, Thiès, hors Sénégal, GPS invalide,
politique `SELECTED`, aucune modification des commandes existantes.
Tests Angular : indication de couverture, rejet d'un point hors zone dans la
prévisualisation, réponse périmée ignorée.

CI GitHub : Security, Backend `verify` + Spotless, frontend lint/tests/build,
Playwright. Tests manuels nécessaires sur téléphone et près des frontières.
