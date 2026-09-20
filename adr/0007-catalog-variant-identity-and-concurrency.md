# ADR-0007 — Identité des variantes et concurrence de l'agrégat catalogue

- Statut : retenu pour la tranche Product / ProductVariant demandée
- Date : 2026-09-20
- Complète ADR-0003 et le modèle catalogue validé ; ne remplace aucune décision
- Contrat : [API catalogue](../docs/catalog-api.md)

## Contexte

Les combinaisons JSON doivent avoir une identité stable malgré la casse, les espaces et l'ordre des propriétés. Les modifications de deux variantes distinctes peuvent également violer l'invariant du dernier élément actif si seule chaque variante est verrouillée.

## Identité persistante des variantes

Le contrat MVP est un objet de chaînes, sans valeurs numériques, nulles, tableaux ou objets imbriqués. Au plus 16 attributs. Les clés sont normalisées Unicode NFKC, espaces externes supprimés, casse abaissée avec Locale.ROOT, puis limitées à [a-z][a-z0-9]{0,31}. Les clés aboutissant au même nom sont rejetées. shoeSize devient shoesize.

Les valeurs passent aussi par NFKC, réduction des suites d'espaces en un espace, trim et minuscules Locale.ROOT. Une valeur doit être non vide, sans caractère de contrôle restant, avec 120 unités UTF-16 maximum. Ces valeurs sont des libellés, pas des quantités converties : « 256GB » équivaut à « 256gb », pas automatiquement à « 256 GB ». Pour une taille ambiguë, le libellé doit préciser le système, par exemple « EU 42 ». Aucun moteur de taxonomie ou EAV.

Les couples normalisés sont triés par clé (ordre Java String). Le préimage est la concaténation des longueurs UTF-16 décimales et du contenu : longueurClé:clélongueurValeur:valeur. Par exemple color=black et size=m donnent 5:color5:black4:size1:m. Les longueurs empêchent une ambiguïté liée aux séparateurs contenus dans les valeurs. Le SHA-256 des octets UTF-8 de ce préimage, en hexadécimal minuscule, est préfixé par v1:. Cela borne la taille de la clé et de son index PostgreSQL. Le risque de collision cryptographique est accepté ; toute collision serait refusée par unicité, jamais fusionnée.

Les attributs vides donnent exactement DEFAULT, stocké avec {}. La clé est recalculée exclusivement côté serveur. Une évolution des règles de normalisation exige une migration explicite et une analyse des collisions ; elle ne doit pas réinterpréter silencieusement les clés v1 existantes.

SKU : Unicode NFKC, trim, majuscules Locale.ROOT ; absent/blanc devient null. Le format normalisé est [A-Z0-9][A-Z0-9._/-]{0,63}. Les espaces internes sont refusés. Unicité (organizationId, sku), y compris variantes désactivées et produits archivés ; plusieurs null sont autorisés. Une modification explicite de SKU libère l'ancien SKU, sans gestion d'alias historique dans cette tranche. Les futurs achats devront conserver leurs snapshots.

## Frontière de l'agrégat et transactions

Product possède ses variantes par une association JPA interne, sans orphanRemoval ni cascade REMOVE. Pas de repository de variante exposant des écritures indépendantes. Les identifiants d'organisation sont scalaires. Une FK SQL composée (organizationId, productId) garantit la cohérence du propriétaire ; Product référence Organization sans cascade conformément à ADR-0003.

Toute écriture sur un produit existant exige la version observée par le client. L'agrégat porte @Version. Chaque mutation d'une variante touche aussi updatedAt du produit à la précision microseconde, strictement après la valeur précédente même si l'horloge n'avance pas. Cela garantit une écriture/version de la racine lors du flush ; la version elle-même reste gérée exclusivement par Hibernate. Le DTO retourné est construit après ce flush.

Une transaction concurrente perdante reçoit 409 PRODUCT_VERSION_CONFLICT et toutes ses modifications sont annulées. Il n'y a pas de retry automatique qui rejouerait une intention sur une version inconnue du client. Deux désactivations simultanées des dernières variantes sont testées avec une barrière après le chargement des deux agrégats.

L'invariant « au moins une variante active pour un produit ACTIVE » est garanti par l'agrégat et sa concurrence, pas par un CHECK SQL impossible à exprimer sur une seule ligne. Les écritures SQL administratives directes doivent respecter ce protocole ; aucun trigger métier ni verrou distribué n'est introduit.

## Cycle de vie et autorisation

Un produit sans liste de variantes (ou avec liste vide) reçoit DEFAULT / UNIT, actif, sans SKU ni estimation. DEFAULT peut coexister avec des variantes à options pour permettre d'ajouter une option avant de désactiver DEFAULT. Une combinaison désactivée reste réservée et consultable ; pas de réactivation dans cette tranche.

Un produit ARCHIVED reste lisible, avec ses variantes conservées. Toutes ses éditions et ajouts sont bloqués ; ses indicateurs de variante ne sont pas réécrits. L'achat futur exigera à la fois Product.ACTIVE et ProductVariant.active. L'archivage ne supprime rien.

VIEW_CATALOG est accordé aux quatre rôles, MANAGE_CATALOG à OWNER et MANAGER. Ces permissions sont évaluées dans OrganizationAuthorization, avec compte actif, adhésion ACTIVE et organisation ACTIVE. Le bootstrap existant est inchangé. catalogue dépend seulement de organization::api et shared::api ; media reste une cible future, sans import anticipé.

Le refus de permission utilise le code existant ORGANIZATION_ACCESS_DENIED (403). Après autorisation de l'organisation, un UUID de produit absent ou étranger donne PRODUCT_NOT_FOUND (404) via une recherche filtrée par organisation. L'UUID de variante doit appartenir au produit chargé.

## Conséquences et périmètre

Les estimations sont BigDecimal, strictement positives si fournies, stockées en NUMERIC sans typmod imposant un arrondi. La frontière d'entrée accepte au plus 18 chiffres entiers et 18 décimales significatives, sans arrondir ; PostgreSQL interdit aussi zéro, négatif et non-fini. Ces valeurs sont uniquement des estimations catalogue.

Aucun prix, stock, image, upload, offre, achat ou calcul de transport. Aucun consommateur ou workflow événementiel intermodule n'est nécessaire à cette tranche : l'autorisation est une lecture synchrone justifiée. Le registre Modulith existant n'est pas modifié.

Le test de modularité demeure inchangé, sans exclusion. La validation utilise exclusivement PostgreSQL/Testcontainers. Aucune dépendance Maven supplémentaire n'est nécessaire.

Référence du mécanisme ORM : [Hibernate, versioning et verrouillage optimiste](https://docs.hibernate.org/orm/7.2/introduction/html_single/#optimistic-locking).
