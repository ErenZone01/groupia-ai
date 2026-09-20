# API des groupages — troisième tranche

Tranche GroupingCampaign → GroupingOffer → consultation publique, du 20 septembre 2026.
Voir [ADR-0008](../adr/0008-grouping-publication-and-coordination.md) pour les décisions de concurrence, publication et historisation.

## Routes privées

Préfixe : /api/organizations/{organizationId}/grouping-campaigns.
JWT Bearer requis, compte actif, adhésion ACTIVE et organisation ACTIVE.

| Méthode | Suffixe | Fonction |
| --- | --- | --- |
| POST | aucun | Créer un DRAFT ; 201 + Location + détail |
| GET | aucun | Résumés paginés ; 200 |
| GET | /{campaignId} | Détail et toutes les offres ; 200 |
| PUT | /{campaignId} | Remplacer les informations éditables ; 200 |
| POST | /{campaignId}/open | Ouvrir ; 200 |
| POST | /{campaignId}/close | Fermer ; 200 |
| POST | /{campaignId}/cancel | Annuler avec motif ; 200 |
| POST | /{campaignId}/offers | Ajouter une variante comme offre ; 200 |
| PUT | /{campaignId}/offers/{offerId} | Modifier libellé/prix ; 200 |
| POST | /{campaignId}/offers/{offerId}/deactivate | Désactiver ; 200 |
| POST | /{campaignId}/offers/{offerId}/activate | Réactiver après validation catalogue ; 200 |

Toute mutation retourne le détail actualisé de la campagne, avec sa nouvelle version et les versions d'offres. Aucun DELETE ou setter d'état générique.

VIEW_GROUPINGS : OWNER/MANAGER/OPERATOR/VIEWER. MANAGE_GROUPINGS : OWNER/MANAGER. Les contrôles sont centralisés dans organization, sans comparaison de rôle dans les controllers.

La liste accepte page (0 par défaut, maximum 1 000 000), size (20 par défaut, maximum 100), status facultatif parmi les états définis. Réponse items/page/size/totalElements/totalPages. Tri createdAt décroissant puis id croissant ; aucune collection d'offres dans la liste.

## Création et édition de campagne

Création minimale, avant configuration du transport :

```json
{"title":"Groupage de septembre"}
```

Création configurée :

```json
{
  "title":"Chaussures de septembre",
  "description":"Description publique",
  "closesAt":"2026-10-15T18:00:00Z",
  "shippingTerms":{
    "originCountry":"CN",
    "transportMode":"AIR",
    "shippingPricingMethod":"PER_KG",
    "shippingCollectionMode":"AFTER_FINAL_ASSESSMENT",
    "shippingRate":8500
  }
}
```

shippingTerms est un bloc facultatif en DRAFT. Une fois fourni, transportMode, shippingPricingMethod, shippingCollectionMode et shippingRate sont requis. originCountry est facultatif (code pays ISO à deux lettres, normalisé). shippingCurrency et roundingRule peuvent être omis : valeurs XOF et HALF_UP ; les autres valeurs sont rejetées.

TransportMode : AIR, SEA, ROAD, OTHER.
Méthodes : PER_KG, PER_CBM, PER_ITEM, FIXED.
Perception : ESTIMATED_AT_CHECKOUT, AFTER_FINAL_ASSESSMENT.

Tarif positif, sauf FIXED qui autorise zéro ; FIXED est un montant XOF entier. Les tarifs unitaires gardent jusqu'à 18 décimales sans arrondi et au plus 18 chiffres entiers. ESTIMATED_AT_CHECKOUT en PER_KG/PER_CBM exige des estimations catalogue disponibles à l'ouverture et pour toute offre ajoutée/réactivée pendant OPEN.

Le titre est nettoyé, non vide, au plus 160 caractères. Description facultative, au plus 5000 caractères, blanche convertie en null. Devise de campagne XOF fixée par le serveur. publicIdentifier, slug, organisation, statut, versions et dates techniques ne sont pas des champs pilotables à la création ; les propriétés inconnues sont refusées.

PUT campagne reprend title, description, closesAt et shippingTerms avec un champ version obligatoire :

```json
{"version":3,"title":"Groupage prolongé","description":"Informations publiques","closesAt":"2026-10-20T18:00:00Z"}
```

Les champs texte facultatifs et closesAt omis deviennent null. En DRAFT, shippingTerms omis/null retire les conditions ; en OPEN, omis/null conserve les conditions figées. Renvoyer des conditions identiques est accepté, changer l'une d'elles donne SHIPPING_TERMS_FROZEN. Aucun changement de transport ou de devise après ouverture.

En OPEN, une nouvelle échéance non nulle doit être future ; null enlève l'échéance fixe. Une échéance passée inchangée est autorisée lors d'une correction de texte. publicIdentifier reste stable et slug suit le titre. Chaque changement effectif d'échéance est journalisé avec acteur et avant/après.

## Transitions

- DRAFT → OPEN : au moins une offre active, toutes ses variantes utilisables, transport valide, XOF et closesAt absent ou futur.
- OPEN → CLOSED.
- DRAFT / OPEN / CLOSED → CANCELLED.
- Les autres valeurs SUPPLIER_ORDERED, IN_TRANSIT, ARRIVED, DISTRIBUTING, COMPLETED sont réservées ; aucun endpoint ne permet d'y passer.

Open/close : corps {"version":4}.
Cancel : corps {"version":4,"reason":"Motif interne"} ; motif requis, au plus 500 caractères, jamais exposé publiquement.

Pas de réouverture de CLOSED ou de CANCELLED. Une répétition de close renvoie GROUPING_ALREADY_CLOSED si la version est courante. Les modifications de campagne/offres sont permises seulement en DRAFT/OPEN.

## Offres et concurrence

Ajout :

```json
{
  "campaignVersion":1,
  "productVariantId":"<UUID de variante>",
  "commercialLabel":"Offre spéciale",
  "unitPrice":12500
}
```

Le serveur vérifie propriétaire, produit ACTIVE et variante active via CatalogVariants. Il conserve uniquement l'UUID de variante. Chaque paire campagne/variante est unique, y compris après désactivation ; utiliser activate pour réactiver.

PUT offre :

```json
{"campaignVersion":2,"version":0,"commercialLabel":"Libellé public","unitPrice":13000}
```

Activate/deactivate :

```json
{"campaignVersion":3,"version":1}
```

Le prix est un entier XOF positif ou nul (18 chiffres maximum), jamais un double/float. Aucun arrondi silencieux d'une saisie fractionnaire. Une offre ne change pas de variante. Libellé facultatif, 160 caractères maximum. Les offres sont ajoutables et les prix modifiables pendant OPEN, pour de futures commandes seulement.

Le serveur exige la version de campagne pour chaque écriture et la version d'offre pour chaque modification d'offre. Une écriture d'offre modifie aussi la version de campagne. Un conflit reçoit 409 ; recharger et faire confirmer l'intention, sans retry aveugle.

priceRevision augmente seulement lorsque le montant change ; version évolue pour les changements de prix, libellé ou activation. Le retour à un ancien prix ne réutilise pas l'ancienne révision. Prix/révisions/acteur/avant-après sont conservés atomiquement dans grouping_changes. Une panne du journal annule la modification.

En OPEN, la dernière offre active ne peut pas être désactivée ; fermer ou annuler la campagne. En DRAFT, zéro offre active est possible. Aucune suppression physique. Réactivation : même offre persistée, sous réserve d'une variante toujours utilisable et d'estimations compatibles.

## Endpoint public pour le frontend

GET /api/public/groupings/{publicIdentifier}, sans JWT.

Aucun organizationId n'est nécessaire : le serveur le résout depuis la campagne. Seule cette route GET est ouverte ; les APIs organisateur restent protégées.

publicIdentifier est un UUID v4 aléatoire distinct de l'identifiant interne. Le slug n'est pas une autorisation et n'intervient pas dans la recherche. Une campagne jamais ouverte retourne 404, y compris si elle a été annulée avant publication. Les organisations archivées ne sont pas publiées.

Réponse 200, Cache-Control: no-store :

```json
{
  "publicIdentifier":"<UUID public>",
  "slug":"chaussures-de-septembre",
  "title":"Chaussures de septembre",
  "description":"Description publique",
  "status":"OPEN",
  "currency":"XOF",
  "orderable":true,
  "closesAt":"2026-10-15T18:00:00Z",
  "version":4,
  "shippingTerms":{
    "originCountry":"CN",
    "transportMode":"AIR",
    "shippingPricingMethod":"PER_KG",
    "shippingCollectionMode":"AFTER_FINAL_ASSESSMENT",
    "shippingRate":8500,
    "shippingCurrency":"XOF",
    "roundingRule":"HALF_UP"
  },
  "offers":[{
    "offerId":"<UUID offre>",
    "productVariantId":"<UUID variante>",
    "commercialLabel":"Offre spéciale",
    "unitPrice":13000,
    "currency":"XOF",
    "active":true,
    "version":1,
    "priceRevision":1,
    "catalog":{
      "productVariantId":"<UUID variante>",
      "productName":"Chaussures",
      "productDescription":"Description produit",
      "attributes":{"color":"black","shoesize":"eu 42"},
      "saleUnit":"PAIR",
      "estimatedUnitWeightKg":0.8255,
      "estimatedUnitVolumeM3":null,
      "usable":true
    }
  }]
}
```

transportMode est regroupé dans shippingTerms. Ne pas déduire une facturation définitive des estimations de catalogue.

offers contient seulement les offres actives et utilisables au moment de la lecture. Une désactivation/archivage catalogue ou la perte d'une estimation indispensable retire l'offre de cette réponse. Aucun UUID interne de campagne/organisation, membre, acteur, motif d'annulation, historique de prix privé, client ou paiement n'est exposé.

orderable = organisation active ET présence d'une offre utilisable ET status OPEN ET (closesAt null OU now < closesAt). À l'égalité, false. Une échéance dépassée peut donc produire status OPEN avec orderable false, sans scheduler. Une prolongation autorisée peut le rendre à nouveau true.

CLOSED et CANCELLED après ouverture restent lisibles avec orderable false. Le futur frontend peut afficher leurs informations publiques et un état fermé/annulé ; il ne doit pas proposer un checkout sur la seule base d'un statut local.

Les données sont assemblées dans une transaction de lecture cohérente. Leur version n'est pas une réservation : le futur checkout devra faire revalider prix/version/disponibilité/échéance par grouping dans sa transaction. Aucun endpoint de checkout n'est implémenté ici.

## Erreurs stables

Même ProblemDetail que les tranches précédentes, sans stack trace.

| HTTP | Code | Cas |
| --- | --- | --- |
| 400 | VALIDATION_FAILED / INVALID_REQUEST | JSON, UUID privé, pagination, version manquante ou propriétés inconnues |
| 400 | INVALID_GROUPING_INFORMATION / INVALID_CANCELLATION_REASON | texte/motif invalide |
| 400 | INVALID_GROUPING_OFFER_PRICE / INVALID_GROUPING_OFFER_LABEL | prix ou libellé invalide |
| 400 | INVALID_SHIPPING_TERMS / INVALID_GROUPING_DEADLINE | conditions ou nouvelle échéance invalides |
| 401 | AUTHENTICATION_REQUIRED | accès privé sans authentification valide |
| 403 | ORGANIZATION_ACCESS_DENIED | absence d'adhésion active ou de permission |
| 404 | ORGANIZATION_NOT_FOUND | organisation privée archivée après autorisation |
| 404 | GROUPING_CAMPAIGN_NOT_FOUND | campagne privée hors périmètre, inconnue ou non publiée au public |
| 404 | GROUPING_OFFER_NOT_FOUND | offre absente de la campagne autorisée |
| 409 | GROUPING_CAMPAIGN_NOT_OPENABLE | conditions absentes ou échéance dépassée à l'ouverture |
| 409 | GROUPING_HAS_NO_ACTIVE_OFFERS | aucune offre à ouvrir ou retrait de la dernière pendant OPEN |
| 409 | GROUPING_OFFER_NOT_AVAILABLE | variante absente, étrangère, inactive ou produit archivé |
| 409 | INVALID_SHIPPING_TERMS | estimation catalogue requise mais indisponible à l'ouverture/activation |
| 409 | SHIPPING_TERMS_FROZEN | modification du transport après OPEN |
| 409 | GROUPING_OFFER_ALREADY_EXISTS | variante déjà sélectionnée |
| 409 | GROUPING_CAMPAIGN_VERSION_CONFLICT / GROUPING_OFFER_PRICE_CONFLICT | version obsolète ou course d'écriture |
| 409 | INVALID_GROUPING_TRANSITION / GROUPING_ALREADY_CLOSED / GROUPING_CANCELLED | transition ou modification interdite |

## Persistance et vérification

[005-grouping.sql](../src/main/resources/db/changelog/005-grouping.sql) crée grouping_campaigns, grouping_offers et grouping_changes. Elle ajoute aussi la clé unique de référence (organization_id, id) sur product_variants sans réécrire 004. FK scalaires/composites sans cascade, unicités et CHECK sur prix/tarifs/XOF/états, index par organisation/date/statut, campagne/activité et journal/date.

Tests PostgreSQL/Testcontainers, vérification Modulith inchangée et commandes :

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
```

Les fixtures précédentes nettoient aussi les nouvelles tables, afin de respecter leurs FK. Aucun test métier précédent n'est exclu. Aucun changement Maven nécessaire.

## Résultat de vérification du 20 septembre 2026

- `.\mvnw.cmd test` : BUILD SUCCESS, 124 tests, 0 échec, 0 erreur, 0 ignoré.
- `.\mvnw.cmd verify` : BUILD SUCCESS, 124 tests, 0 échec, 0 erreur, 0 ignoré ; JAR Spring Boot exécutable produit.
- Répartition : 47 cas d'intégration grouping, 4 cas de la règle d'admission, 35 catalogue, 34 identity/organization, 3 tests d'architecture et 1 test de contexte.
- Concurrence testée : deux transactions de campagne synchronisées après lecture, création concurrente de la même offre, changements de prix concurrents, deux désactivations et ouverture contre modification des conditions.
- L'échec réel d'une contrainte PostgreSQL sur grouping_changes annule le prix, sa révision et les versions. Les réponses publiques sont contrôlées par liste explicite de champs autorisés.
- ModularityTests reste inchangé, sans exclusion, filtre ou ouverture de module. Aucun accès interne intermodule, aucune association JPA intermodule.
- Journaux locaux non versionnés : target/grouping-test.log et target/grouping-final-verify.log.
- Aucun écart avec AGENTS.md et les décisions validées. ADR-0008 documente les choix supplémentaires ; les autres tranches fonctionnelles restent hors périmètre.
