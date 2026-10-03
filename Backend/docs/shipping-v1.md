# Shipping V1 — mesures et transport final

Groupia V1 n'est pas la source physique de vérité du transitaire. Il trace :
**information reçue → source → calcul reproductible lorsque possible → montant final validé par la gérante**.

## Périmètre et coordination

La logistique Shipment/timeline reste inchangée. Le traitement est autorisé pour les commandes CONFIRMED
de campagnes SUPPLIER_ORDERED, IN_TRANSIT ou ARRIVED. Le backend sélectionne les commandes par boutique
et groupage. Une UUID ne constitue jamais une autorisation.

ordering coordonne l'accès aux snapshots de ses commandes et appelle shipping::api. shipping ne dépend
ni d'ordering, ni de grouping, ni de payment. Aucun lien JPA intermodule.
Les snapshots fournis à shipping sont construits côté serveur ; aucun DTO HTTP n'accepte ces valeurs.
Lecture : VIEW_ORDERS. Écriture et aperçu : VIEW_ORDERS + MANAGE_GROUPINGS, avec acteur issu de l'identité
authentifiée. Réemploi des permissions existantes ; pas de comparaison de rôles dans les contrôleurs.

Chaque écriture verrouille le groupage puis la commande, dans le même ordre que la commande fournisseur.
La création de l'assessment et l'ajout d'une mesure/calcul avec mise à jour du pointeur courant sont atomiques.
Le client doit fournir la version actuelle. Une deuxième finalisation avec la même version est refusée (409),
sans deuxième calcul ; après réponse perdue, relire avant de décider d'une correction. Aucun retry automatique.
Aucun événement financier n'est émis dans cette tranche : la validation s'arrête au calcul FINALIZED.

## Modèle persisté et migration

Migration additive **014-shipping-assessments.sql** :
- colonne facultative volumetric_divisor de grouping_campaigns ;
- shipping_assessments : identité boutique/groupage/commande, snapshot immuable, PENDING ou FINALIZED,
  version, pointeurs vers la dernière mesure et le dernier calcul final, dates serveur ;
- shipping_measurements : observations append-only, source et acteur distincts, dates, note privée,
  référence de la mesure remplacée et motif ;
- shipping_calculations : révision FINAL immuable, explication reproductible, dates, auteur,
  note privée, calcul remplacé et motif obligatoire après la première finalisation.

Unicité boutique/commande, révision par assessment, références composites vers la même évaluation,
FK d'appartenance de la commande, indexes de lecture par boutique/groupage et historique.
Les triggers protègent les snapshots, le chaînage des révisions et l'interdiction d'UPDATE/DELETE de l'historique.
Un assessment PENDING virtuel est retourné en lecture ; la première écriture le persiste.
Les lignes métier existantes, les snapshots et les migrations antérieures ne sont pas réécrits.

Les conditions nouvelles restent gelées dès OPEN, y compris le diviseur. Les anciens JSON sans diviseur
se désérialisent avec null. Aucune règle 5000/6000 implicite. Les commandes déjà placées gardent leurs
propres conditions même si une campagne historique ne possédait pas cette option.

## Calculs et provenance

| Méthode | Données utilisées |
|---|---|
| PER_KG sans diviseur | Poids réel communiqué × tarif |
| PER_KG, poids facturable communiqué | Ce poids est prioritaire ; aucune dimension n'est exigée |
| PER_KG avec diviseur et sans poids facturable communiqué | max(poids réel, longueur × largeur × hauteur / diviseur) × tarif ; données complètes requises |
| PER_CBM direct | Volume communiqué × tarif |
| PER_CBM dimensions | longueurCm × largeurCm × hauteurCm / 1 000 000 × tarif |
| PER_ITEM | Somme des quantités historiques × tarif |
| FIXED | Tarif historique par commande |
| PROVIDER_QUOTED | Montant entier XOF communiqué, devise exacte et source explicite, sans mesure, tarif ou formule inventée |

Les dimensions sont celles d'un volume mesuré pour la commande. Additionner plusieurs colis ou répartir
une facture collective nécessite une future tranche. Un volume direct est prioritaire sur les dimensions ;
un poids facturable direct est prioritaire sur poids réel/dimensions. L'explication indique la méthode retenue.
Les mesures présentes doivent être strictement positives, < 10^12, au plus 18 décimales significatives
après suppression des zéros finaux. Les dimensions partielles peuvent être enregistrées, mais ne suffisent
pas à un calcul dimensionnel. null signifie inconnu, jamais zéro.

Tous les calculs monétaires utilisent BigDecimal. Le ratio exact numérateur/dénominateur est conservé
jusqu'à la division monétaire finale, scale 0 HALF_UP, XOF. Aucune précision de poids n'est perdue avant
le montant. Les quantités décimales d'explication sont une représentation DECIMAL128 du ratio exact :
elles peuvent être abrégées à l'écran (9 décimales). Le montant communiqué direct doit déjà être entier,
sans conversion silencieuse. Montants finaux compris entre 0 inclus et 10^18 exclu.
L'interface refuse la saisie et la finalisation d'un montant dépassant les entiers représentables exactement par JavaScript (Number.MAX_SAFE_INTEGER), même si l'API accepte une plage supérieure.
Les totaux de contrôle et pourcentages sont une aide visuelle, pas un calcul d'encaissement.

Les estimations de commande ne sont jamais modifiées. Les estimations de poids/volume somment les valeurs
unitaires historiques × quantités ; si une ligne est inconnue, l'estimation totale est inconnue.
L'estimation avant checkout reste celle du catalogue (sans dimensions) ; la règle volumétrique ne devient
applicable au final qu'avec les données reçues nécessaires.

## Aperçu et corrections

Enregistrer une mesure ne finalise rien. L'aperçu ne crée même pas un assessment en base.
Il utilise la version et la dernière mesure enregistrée. La gérante vérifie l'origine, la quantité,
le tarif et le montant avant « Finaliser le transport ».

Une mesure corrigée désigne la dernière mesure et exige un motif. Un calcul corrigé exige un motif,
ajoute une révision, référence le calcul final courant et déplace le pointeur sans écraser l'ancien.
Une mesure en cours de correction n'efface pas le dernier montant final déjà validé.
Les notes et motifs restent privés ; source et explication du dernier calcul validé sont visibles à la cliente.

## Endpoints

Préfixe privé : /api/organizations/{organizationId}/grouping-campaigns/{campaignId}
- GET /shipping-assessments : lignes des commandes confirmées, snapshots et historiques.
- POST /orders/{orderId}/shipping-measurements : version + information physique.
- POST /orders/{orderId}/shipping-preview : version, origin, measurementId ou quotedAmount/currency/source,
  occurredAt, note et correctionReason facultatifs selon le contexte.
- POST /orders/{orderId}/shipping-finalizations : même contrat que l'aperçu.

Le suivi existant par token et le suivi via accès client reçoivent shippingFinal,
un DTO construit explicitement sans acteur, note, identifiants privés ou contact prestataire.
Ni le reçu checkout ni les obligations de paiement ne changent.

Le tableau V1 charge toutes les commandes confirmées d'un groupage en une lecture cohérente.
L'historique complet est adapté aux petits lots du MVP ; pagination/historiques chargés à la demande
seront nécessaires pour de très gros groupages. Aucun total ne répartit automatiquement une facture.

## Parcours gérante et cliente

Dans Groupages → détail → Logistique → Transport des commandes :
1. Visualiser les commandes et estimations historiques ; totaliser les données renseignées avec leur couverture.
2. Renseigner les mesures connues ou choisir « Montant communiqué directement ».
3. Préciser la source ; date réelle et note privée facultatives.
4. Enregistrer la mesure si nécessaire, puis demander l'aperçu serveur.
5. Vérifier puis finaliser individuellement. Les autres commandes peuvent rester à renseigner.
6. Pour corriger, sélectionner la commande, justifier et ajouter une nouvelle révision.

L'écart estimé/communiqué est (réel − estimé), et le pourcentage est cet écart / estimé × 100
si l'estimation est strictement positive. Seuil visuel centralisé **20 % en valeur absolue**
(SHIPPING_VARIANCE_WARNING_PERCENT, surchargeable via injection Angular). Jamais un blocage backend.
Les totaux distinguent poids réel, facturable, CBM et montant des seuls calculs finaux courants.

Dans le suivi sécurisé, la cliente voit le montant final et sa provenance. CALCULATED expose quantité,
méthode, tarif et formule (avec poids volumétrique uniquement si calculé).
PROVIDER_QUOTED indique un montant reçu sans inventer de formule.

## Recette manuelle précise

Préparer une variante et un groupage avec la tarification voulue AVANT ouverture.
Placer et confirmer une commande via le flux habituel, fermer le groupage et figer la commande fournisseur.
Ne pas modifier directement les paiements en SQL. Ouvrir ensuite « Transport des commandes ».

Pour chaque ligne ci-dessous : saisir les champs, choisir « Prestataire », enregistrer la mesure si applicable,
demander l'aperçu, contrôler le résultat, finaliser et consulter le lien sécurisé de la commande.

| Cas | Conditions avant OPEN | Saisie | Résultat attendu |
|---|---|---|---|
| Poids réel | PER_KG, 3 500, aucun diviseur | réel 2,8 kg | 9 800 XOF |
| Poids facturable direct | PER_KG, 3 500 | facturable 8,4 kg ; dimensions vides | 29 400 XOF |
| Volumétrique | PER_KG, 3 500, diviseur 5 000 | réel 2,4 kg ; 60 × 35 × 20 cm | 8,4 kg retenus ; 29 400 XOF |
| Autre règle volumétrique | PER_KG, 3 500, diviseur 7 000 | mêmes mesures | 6 kg ; 21 000 XOF |
| Volume direct | PER_CBM, 100 000 | volume 0,165 m³ | 16 500 XOF |
| Volume dimensions | PER_CBM, 100 000 | 100 × 55 × 30 cm | 0,165 m³ ; 16 500 XOF |
| Par article | PER_ITEM, 1 000 ; commander 4 articles | aucune mesure | 4 000 XOF |
| Forfait | FIXED, 2 500 | aucune mesure | 2 500 XOF |
| Montant reçu | n'importe quelle méthode | origine directe, 12 500 XOF, prestataire | 12 500 XOF sans formule |

Correction : sur le cas poids réel, corriger 2,8 en 2,6 kg avec un motif, enregistrer puis finaliser.
Attendre révision 2 = 9 100 XOF, révision 1 toujours à 9 800. Le suivi expose la révision 2 uniquement.
Concurrence : deux onglets ouverts sur la même version ; finaliser dans le premier, vérifier le refus
dans le second et l'obligation d'actualiser.
Essayer une mesure zéro : refus ; laisser tous les champs vides : pas de mesure artificielle.
Pour tester l'alerte, utiliser une estimation catalogue 1,2 kg et un réel 2,8 kg :
écart +1,6 kg / +133,3 %, sans blocage. Tester à 360 px et sur desktop, clair/sombre, au clavier.
Contrôler que la commande reste CONFIRMED et fulfillment inchangé ; aucun SHIPPING_BALANCE n'est créé.

## Hors tranche

Répartition de facture globale, compte/import/API transitaire, documents, SHIPPING_BALANCE,
Bictorys du solde, remboursements, préparation au retrait et retrait. Aucun changement WhatsApp.

## Validation exécutée le 3 octobre 2026

| Commande | Résultat final |
|---|---|
| Backend : mvnw.cmd test | 586 tests, 0 échec, 0 erreur, 0 ignoré |
| Backend : mvnw.cmd verify | BUILD SUCCESS ; 586 tests, 0 échec, 0 erreur, 0 ignoré |
| Spring Modulith | ApplicationModules.of(GroupiaApplication.class).verify() exécuté ; 3 tests d'architecture verts |
| Frontend : npm test | 214 tests verts dans 26 fichiers |
| Frontend : npm run build | Build production réussi |
| Frontend : npm run test:e2e | 100 tests verts, dont les 8 nouveaux parcours shipping à 360 px clair/sombre |
| git diff --check | Réussi |

Les 33 nouveaux tests backend comprennent 23 cas de calcul/validation, 9 intégrations
et 1 migration de données historiques. Les nouveaux tests frontend couvrent aussi
la séparation aperçu/finalisation, les corrections, la confidentialité et la limite
de précision des montants JavaScript. Les contrôles des anciens parcours restent actifs.
Aucune dépendance ajoutée. Les preuves d'exécution sont dans Backend/target/shipping-test.log,
Backend/target/shipping-verify.log et Frontend/artifacts/shipping-{unit,build,e2e-all}.log.

## Compatibilité de la migration 014 déjà appliquée

Une instance locale a appliqué la première version de 014 avant l'ajout du contrôle
de structure JSON de l'explication. Son checksum exact est
`9:055b3c7d7a68b80dc68d25a3ebcf7c71`. Cette seule version historique est déclarée
valide dans 014 ; les autres modifications inconnues restent refusées.
La migration additive 015 installe le contrôle manquant et valide les lignes existantes.
Sur la version récente de 014, le contrôle nommé est équivalent au contrôle déjà présent.
Aucune donnée métier ni valeur de checksum stockée n'est réécrite.
Ne pas lancer clearCheckSums ni réinitialiser la base pour cette erreur. Au prochain
démarrage, Liquibase applique 015 automatiquement. Si des explications historiques
sont invalides, la migration échoue sans les altérer : elles doivent être examinées.

ShippingChecksumUpgradeTests reproduit les deux historiques à partir des vrais SQL,
vérifie leurs checksums et la conservation des commandes, mesures et calculs,
l'idempotence, le refus d'une version inconnue et celui de données historiques invalides.

Validation du correctif : `mvnw.cmd test` et `mvnw.cmd verify` réussis, chacun
avec 590 tests, 0 échec, 0 erreur et 0 ignoré, dont les 4 nouveaux cas de compatibilité.
Journaux : `target/shipping-checksum-full-test.log` et `target/shipping-checksum-verify.log`.
Le démarrage sur la base locale reste à effectuer ; les migrations ont été validées
sur PostgreSQL Testcontainers avec les deux historiques connus.
