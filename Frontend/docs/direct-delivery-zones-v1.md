# Groupia — zones de livraison V1 (Sénégal)

Objectif : permettre aux gérantes de choisir facilement leurs régions de livraison,
et adapter les choix proposés aux clientes **avant** l'activation du paiement direct.

## Configuration par boutique

Depuis `/app/vente-directe` :
- `DISABLED` (valeur par défaut pour toute boutique sans politique) : pas de
  livraison, retrait uniquement ;
- `ALL` : livraison proposée dans les 14 régions du Sénégal ;
- `SELECTED` : livraison proposée uniquement dans les régions cochées,
  par exemple Dakar.

La configuration est historisée côté base avec une version concurrente
(édition protégée par verrou du storefront), sous `direct_delivery_policies`.
Les permissions de lecture/écriture sont `VIEW_CATALOG` et `MANAGE_CATALOG`.
Les règles ne sont pas globales : elles sont attachées à une boutique et à une organisation.

API :
- `GET /api/organizations/{organizationId}/direct-store/delivery-policy`
- `PUT /api/organizations/{organizationId}/direct-store/delivery-policy`
- `GET /api/public/direct-stores/{publicIdentifier}/delivery-policy`

Les clientes ne voient que les régions activées, ou seulement le retrait si
la livraison est désactivée. Le backend refuse également la création d'un
nouveau brouillon de livraison hors des régions autorisées quand
`groupia.direct-sales.checkout.enabled=true` dans les tests.

## Limites explicites et étapes suivantes

**Cette étape NE garantit PAS la localisation réelle d'un client.**
La région renseignée dans le formulaire est une **déclaration**, falsifiable
dans une requête HTTP. Il ne faut pas activer de paiement sur cette seule base.

**Prochaine tranche :** carte MapLibre/OpenFreeMap avec position GPS facultative,
validation manuelle du repère, recherche via géocodage inverse (Geoapify ou
équivalent) et fallback de saisie. Pour éviter les contournements des zones,
vérifier côté backend la position dans des polygones administratifs vérifiés,
en évaluant d'abord la couverture au Sénégal et en Côte d'Ivoire. Ne pas
envoyer les coordonnées de la cliente à des analytics.

La limitation **des ventes elles-mêmes** (notamment avec retrait) exige une
localisation du client indépendante de l'adresse de livraison ; elle est
volontairement distincte de cette tranche. Les frais et la confirmation des
livraisons restent non calculés. Les contrats de groupage et Bictorys ne
changent pas. `groupia.direct-sales.checkout.enabled` reste désactivé par défaut.

## Vérifications attendues

Dans `Backend` :
```powershell
.\mvnw.cmd spotless:apply
.\mvnw.cmd verify
```
Dans `Frontend` :
```powershell
npm run lint
npm test
npm run build
npx playwright test e2e/direct-sales-v1.spec.ts
```

Scénarios manuels : boutique nouvellement créée (retrait seulement) ; activer
Dakar uniquement ; voir seulement Dakar sur le formulaire public ; sélectionner
une autre boutique et vérifier l'absence de fuite des anciennes zones ;
désactiver la livraison ; retenter avec un ancien prix/paramétrage. Ne pas
fusionner sans résultats de tests confirmés.
