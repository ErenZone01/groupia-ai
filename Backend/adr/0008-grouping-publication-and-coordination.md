# ADR-0008 — Publication des groupages et coordination des offres

- Statut : retenu pour la troisième tranche demandée
- Date : 2026-09-20
- Complète ADR-0002, ADR-0003 et ADR-0005 ; aucune décision validée remplacée
- Contrat HTTP : [API groupage](../docs/grouping-api.md)

## Contexte et décisions

Campaign et Offer sont deux racines distinctes. Ouvrir/fermer une campagne, modifier les conditions ou retirer sa dernière offre active sont des décisions liées : les versions indépendantes des seules offres ne suffisent pas. ADR-0005 exige aussi que tout changement de prix laisse une trace durable.

### Coordination transactionnelle

Chaque écriture exige la version observée de la campagne. Chaque modification d'une offre exige en plus sa propre version. Toute écriture d'offre touche updatedAt de la campagne, strictement après sa valeur précédente à la précision microseconde, ce qui déclenche son contrôle @Version au flush. Les offres gardent leur propre @Version et leur priceRevision. Aucune association JPA/collection ne rattache les offres à la campagne ; leurs repositories restent internes à grouping.

Les deux racines sont écrites dans une courte transaction locale pour ces invariants explicitement atomiques. Deux changements concurrents sur une même campagne ne sont pas fusionnés silencieusement : le perdant reçoit 409 et recharge le contexte. Ce choix accepte des conflits entre offres différentes dans le MVP. Aucun verrou distribué ou retry automatique ne rejoue l'intention avec une version nouvelle.

L'ouverture revalide toutes les offres actives via catalog::api. Ajout et réactivation vérifient aussi le propriétaire et l'utilisabilité courante. Il n'y a pas de verrou global avec le catalogue : un archivage catalogue ultérieur reste possible et rend l'offre indisponible au storefront. Le futur checkout devra revalider cette disponibilité dans sa propre décision coordonnée.

### Transport : contrats et gel

Les vocabulaires et ShippingTerms appartiennent à shipping::api / api.dto, conformément au graphe validé. La tranche n'ajoute aucun calcul de charge, ShippingAssessment, repository, table ou endpoint shipping. CampaignTerms est une valeur persistée interne à grouping qui conserve les conditions sélectionnées.

Un DRAFT peut ne pas avoir encore de conditions. L'ouverture exige un bloc complet en XOF / HALF_UP. PER_KG, PER_CBM et PER_ITEM exigent un tarif positif ; FIXED accepte zéro et exige un montant XOF entier. Les tarifs par unité peuvent garder jusqu'à 18 décimales et 18 chiffres entiers. Aucun arrondi destructif n'est effectué à la saisie ; les futures charges finales appliqueront HALF_UP.

ESTIMATED_AT_CHECKOUT avec PER_KG ou PER_CBM exige que chaque variante proposée possède respectivement une estimation de poids ou de volume. PER_ITEM/FIXED ne nécessitent pas de mesure fictive. AFTER_FINAL_ASSESSMENT n'invente pas d'estimation absente.

Après OPEN, transport, pays d'origine, méthode, mode de perception, tarif, devise et règle d'arrondi sont figés. Les conditions identiques peuvent être renvoyées sans changer le contrat ; omettre shippingTerms pendant OPEN les conserve. En DRAFT, shippingTerms remplace le bloc et null le retire. Le titre, la description et closesAt restent éditables en DRAFT/OPEN uniquement.

### Prix et journal local durable

Le prix unitaire affiché est un entier XOF positif ou nul, au plus 18 chiffres, conforme au modèle validé « non négatif ». Un prix fractionnaire est rejeté plutôt qu'arrondi silencieusement. priceRevision augmente seulement lors d'un changement réel de montant ; version augmente aussi pour les autres changements. Un retour au prix précédent conserve donc une version/révision nouvelle.

grouping_changes conserve, dans la même transaction, les changements de prix (offre/campagne/organisation, acteur, avant/après, devise, révision et instant) et les changements d'échéance. L'acteur provient de OrganizationAccess.requireActorPermission, jamais du JSON client. Le journal est append-only dans le modèle et son port de persistance ; aucune route ne permet de le modifier ou supprimer. La contrainte unique de révision protège contre une double trace du même changement.

Une panne de cette écriture annule le prix, la révision et la version de campagne. Ce comportement est testé avec un rejet PostgreSQL réel. Aucun événement sans consommateur n'est présenté comme une preuve durable d'audit. On ne crée pas le module AuditLog ni de consommateur intermodule : la publication persistante Modulith reste réservée aux futurs flux qui en auront besoin. Les reprises/idempotences de ces futurs consommateurs restent à implémenter avant leur activation. Le journal local ne remplace pas une future politique d'audit plateforme.

### Publication et disponibilité

publicIdentifier est un UUID v4 aléatoire, distinct de l'UUID interne et immuable. C'est une référence de contenu public, pas une autorisation privée. Le slug est dérivé du titre (minuscules ASCII, accents décomposés retirés, séparateurs réduits en tirets ; fallback groupage), recalculé lors d'un changement de titre. Il n'est ni unique ni utilisé dans la résolution du endpoint.

Une campagne n'est publique que si elle a déjà été ouverte et si son organisation est encore ACTIVE. DRAFT et CANCELLED avant première ouverture donnent le même 404 qu'une référence inconnue. CLOSED et CANCELLED après publication restent lisibles, sans commande possible. Le motif d'annulation reste privé.

Le serveur résout publicIdentifier → campagne → organisation. CatalogVariants.findByIds fournit en lot les seuls libellés/attributs/unités/estimations nécessaires, filtrés par organisation, sans exposer les entités catalog. OrganizationAvailability expose seulement un booléen d'activité. Ces APIs Java internes n'ouvrent aucun endpoint catalogue/organisation anonyme.

Le public exclut offres inactives, variantes désactivées, produits archivés et offres dont une estimation requise est devenue indisponible. L'indicateur orderable utilise GroupingCampaign.isOrderableAt : organisation active, au moins une offre utilisable, statut OPEN et (closesAt absent ou now < closesAt). L'égalité à l'échéance interdit l'admission. Aucun scheduler ne modifie le statut. Une prolongation est possible même si OPEN a dépassé son échéance ; une nouvelle échéance non nulle doit être future, une échéance dépassée inchangée n'empêche pas de corriger le titre.

La lecture publique utilise une transaction PostgreSQL REPEATABLE_READ pour assembler campagne, offres et catalogue depuis un instantané cohérent. Cache-Control: no-store limite les réponses périmées côté navigateur. Cela ne réserve ni prix ni commande : le futur checkout devra appeler le contrôle grouping dans sa transaction de placement, jamais se fier à orderable ou au prix transmis par le navigateur.

### Intégrité et frontière

La migration 005 crée grouping_campaigns, grouping_offers et le seul journal local nécessaire grouping_changes. Une contrainte unique (organization_id, id) est ajoutée sur product_variants, sans modifier la migration 004 déjà appliquée, afin de permettre la FK scalaire composée des offres. Toutes les FK intermodules sont sans cascade. Aucun repository, entité ou service interne voisin n'est importé.

Dépendances directes grouping : organization::api, catalog::api, shipping::api, shared::api. L'obtention de l'acteur passe par organization ; grouping n'ajoute pas de dépendance directe à identity. Aucun cycle, module ouvert ou exclusion de test.

## Limites assumées

Pas de checkout, garantie de réservation, commande, paiement, calcul de transport, stock, capacité, upload, notification ou frontend. Les états logistiques futurs existent uniquement dans l'enum et aucune commande HTTP ne les simule. Les écritures SQL administratives directes doivent respecter les invariants applicatifs : pas de triggers métier ou de droits PostgreSQL séparés dans cette tranche.
