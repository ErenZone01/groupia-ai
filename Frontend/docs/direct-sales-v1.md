# Vente directe Groupia — V1, tranche 1 : vitrine et publication

## Intention

La vente directe est un parcours **indépendant** des groupages. Ne jamais
créer une campagne fictive pour pouvoir payer : les commandes, le transport,
le suivi et la comptabilité des groupages ont aujourd'hui un vrai lien FK
avec `grouping_campaigns`. Aucun faux prix, commande ou disponibilité n'est
inventé.

## Livré dans cette tranche

- **Gérante** : `/app/vente-directe`, réutilisation des variantes existantes
  (tous les produits du catalogue restent dans le catalogue commun).
- Une boutique permanente par organisation. Son `publicIdentifier` ne
  change pas au renommage. Lien partageable `/v/{publicIdentifier}`.
- Offres indépendantes : variante, prix entier XOF, brouillon/publiée,
  version optimiste, maximum 100 offres, unicité variante/boutique.
- Une publication refuse les variantes inactives/archivées. Les prix sont
  contrôlés au serveur à chaque mutation, sans confiance au formulaire.
- Les accès de gérante exigent `MANAGE_CATALOG` ; la lecture privée requiert
  `VIEW_CATALOG`. Toutes les écritures sont rattachées au `organizationId`
  autorisé, les FK incluent `(organization_id, id)`.
- La lecture publique ne demande pas de compte client. Elle retourne
  uniquement les variantes actives, **publiées**, d'une organisation active,
  et les rendus média READY. `Cache-Control: no-store` pour éviter de servir
  des offres dépubliées depuis un cache HTTP.
- La boutique sans aucune offre publiable répond **404** plutôt qu'exposer
  un brouillon.
- Les commandes de groupage, leur paiement, les données de finance et les
  webhooks existants restent intacts.

## API

| Méthode | Route | Accès |
|---|---|---|
| GET | `/api/organizations/{organizationId}/direct-store` | VIEW_CATALOG |
| PUT | `/api/organizations/{organizationId}/direct-store` | MANAGE_CATALOG |
| POST | `/api/organizations/{organizationId}/direct-store/offers` | MANAGE_CATALOG |
| PUT | `/api/organizations/{organizationId}/direct-store/offers/{offerId}` | MANAGE_CATALOG |
| GET | `/api/public/direct-stores/{publicIdentifier}` | Anonyme |

`PUT` d'offre requiert `version`, `published` explicite et `priceXof`.
Une version obsolète donne 409. Les prix sont dans `[1, 999999999999]`.
Une variante n'est ajoutable que dans sa propre boutique.

## Non livré et prochaine tranche obligatoire pour lancer les ventes

**Le panier, le checkout sans compte, le paiement Bictorys, le suivi,
l'identification du type de commande, la distribution et les KPI de vente
directe ne sont pas encore disponibles.** L'interface publique le dit
explicitement. Aucun bouton « acheter » qui échoue n'est affiché.

La tranche 2 doit concevoir des **commandes DIRECT distinctes** ou un contrat
d'ordering unifié correctement historisé, avec :
- snapshots immuables des prix, quantités et variantes ;
- idempotence et fenêtres de paiement, avec demandes Bictorys idempotentes ;
- politique de retrait/livraison indépendante du transport Chine → Sénégal ;
- aucune rupture des commandes GROUPING, des settlements et de l'analytics ;
- séparation des ventes directes/groupages sur listes et graphiques ;
- tests de concurrence, anti-double paiement et sécurité multi-tenant.

## Vérifications requises

```powershell
git fetch origin
git switch --track origin/direct-sales-storefront-v1
cd Backend
.\mvnw.cmd spotless:check
.\mvnw.cmd "-Dtest=DirectStorefrontIntegrationTests,ModularityTests" test
.\mvnw.cmd verify
cd ..\Frontend
npm run lint
npm test
npm run build
npm run test:e2e
```

Le workflow GitHub Actions reste bloqué par le problème de facturation
du compte. **Aucun test n'a pu être exécuté depuis l'éditeur GitHub connecté**.
Ne pas fusionner avant validation.
