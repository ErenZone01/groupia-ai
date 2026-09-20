# Règles métier Groupia V1

Statut : décisions validées le 17 septembre 2026. Les règles décrivent le comportement attendu, indépendamment du langage, du framework ou du stockage. Les questions encore ouvertes sont identifiées en fin de document ; elles ne doivent pas être remplacées par des hypothèses silencieuses.

## Propriété, personnes et accès

- BR-01 : une commande appartient à exactement une organisation, une campagne et une devise. Le MVP accepte uniquement XOF.
- BR-02 : un client acheteur est distinct d'un compte utilisateur organisateur. Commander publiquement ne nécessite aucun compte.
- BR-03 : un numéro de téléphone client n'est pas unique. Il ne permet ni fusion automatique sans vérification, ni récupération publique d'un historique privé.
- BR-04 : une action privée exige un utilisateur authentifié, une adhésion à l'organisation et la permission de réaliser l'opération. L'adhésion seule ne suffit pas.
- BR-05 : les rôles sont OWNER, MANAGER, OPERATOR, VIEWER. Une organisation active conserve au moins un propriétaire actif.
- BR-06 : l'organisation d'un achat public est déduite de la campagne par le système. Le navigateur ne choisit pas le propriétaire de la commande.
- BR-07 : une référence publique, même imprévisible, n'autorise pas l'accès aux coordonnées, commandes privées, paiements ou audits.
- BR-08 : le suivi privé exige un jeton aléatoire valide. Seule son empreinte est conservée. Le jeton peut expirer ou être révoqué ; il n'est pas inscrit dans les journaux.

## Catalogue et offre

- BR-09 : un produit possède au moins une variante. Un produit sans options possède une variante DEFAULT.
- BR-10 : une offre de campagne désigne exactement une variante. Une variante ne possède qu'une offre dans une campagne selon le modèle V1.
- BR-11 : le produit n'est pas la source du prix de campagne ; l'offre définit ce prix.
- BR-12 : le prix d'une offre peut évoluer pendant une campagne ouverte pour les futurs placements.
- BR-13 : tout changement de prix conserve une trace avec acteur, instant, valeurs avant/après, devise et révision.
- BR-14 : modifier ou archiver un produit, une variante ou une offre ne modifie jamais les achats déjà placés.
- BR-15 : une référence déjà utilisée commercialement est archivée plutôt que supprimée.

## Checkout et historique commercial

- BR-16 : toutes les offres d'une commande appartiennent à la même campagne et organisation. Les quantités sont positives.
- BR-17 : le système revalide l'état de la campagne, les offres, leur cohérence et leur prix courant au placement.
- BR-18 : le prix affiché au client sert à détecter un conflit ; il n'est pas une autorité de calcul.
- BR-19 : si le prix affiché diffère du prix courant, le placement échoue explicitement avec un conflit de prix. Le nouveau montant n'est jamais appliqué silencieusement.
- BR-20 : après conflit, le client doit prendre connaissance des nouvelles conditions avant un nouvel essai. Aucune commande ni obligation financière n'est créée par l'essai refusé.
- BR-21 : les placements concurrents et les modifications de prix/clôtures sont départagés : une commande est acceptée avec un ensemble cohérent de conditions, ou refusée.
- BR-22 : rejouer un placement déjà réussi ne crée pas une seconde commande et ne recalcule pas son prix. Réutiliser la même clé avec un contenu différent est rejeté.
- BR-23 : les descriptions, attributs de variante, quantités, prix, frais et conditions de transport acceptés sont conservés dans la commande. Les coordonnées utiles du destinataire sont également conservées pour cet achat.
- BR-24 : une correction ultérieure du transport préserve l'estimation et ajoute une nouvelle évaluation ; elle ne réécrit pas les conditions commerciales initiales.

## Campagne, commande et remise

- BR-25 : une campagne suit normalement préparation, ouverture, clôture, achat fournisseur, transport, arrivée, distribution et achèvement.
- BR-26 : une campagne clôturée n'accepte plus de nouvelle commande. La clôture ne constitue pas une preuve que tous les paiements en cours sont résolus.
- BR-27 : les états commerciaux sont PENDING_PAYMENT, CONFIRMED, EXPIRED, CANCELLED, COMPLETED.
- BR-28 : les états de remise sont NOT_READY, READY_FOR_PICKUP, PICKED_UP, CANCELLED. Ils ne se confondent pas avec l'état commercial.
- BR-29 : l'arrivée de la campagne ne rend pas automatiquement chaque commande récupérable. La disponibilité, la préparation et les conditions de règlement doivent être satisfaites.
- BR-30 : la V1 prévoit une remise complète, pas un retrait partiel. Une remise enregistrée ne disparaît pas lors d'un remboursement.
- BR-31 : une commande terminée correspond à une remise complète et à des obligations financières résolues. Une campagne terminée exige la résolution des commandes et régularisations concernées.
- BR-32 : les stades d'annulation autorisés, échéances et conséquences financières doivent être définis avant les parcours concernés.

## Obligations, tentatives et restitutions

- BR-33 : une demande de paiement exprime une obligation précise ; une tentative représente une opération auprès d'un prestataire. Une demande peut avoir plusieurs tentatives.
- BR-34 : les objets de paiement sont INITIAL_ORDER et SHIPPING_BALANCE. Le paiement fractionné volontaire n'est pas proposé.
- BR-35 : le montant, la devise, la destination et la ventilation d'une obligation proviennent des faits validés par le système.
- BR-36 : une demande réglée conserve le montant convenu. Une correction produit un ajustement ou une restitution traçable.
- BR-37 : un succès exige la vérification de toutes les informations pertinentes : référence interne, prestataire, transaction, statut, montant, devise, commande et objet du paiement.
- BR-38 : une signature authentique ne suffit pas à prouver qu'une commande est correctement payée. Une confirmation côté client ne suffit jamais.
- BR-39 : une incohérence ou référence inconnue est conservée pour investigation et ne modifie pas automatiquement la commande.
- BR-40 : un deuxième succès réel est enregistré même si la demande est déjà réglée. Il provoque une réconciliation ; il n'est ni perdu, ni affecté silencieusement à une autre dette.
- BR-41 : un succès ne régresse pas à cause d'un ancien événement en attente, échoué, annulé ou expiré.
- BR-42 : une réponse réseau absente ne prouve pas l'absence de débit. La situation doit être réconciliée avant de relancer un débit risquant de faire doublon.
- BR-43 : un paiement tardif après EXPIRED ou CANCELLED reste enregistré ; il ne réactive jamais automatiquement la commande.
- BR-44 : une notification prestataire répétée produit le même résultat métier qu'une seule réception. Les réceptions et leurs suites sont traçables.
- BR-45 : un remboursement est une opération distincte avec montant, motif et avancement. Il ne remplace pas le succès initial du paiement par REFUNDED.
- BR-46 : une restitution demandée n'est pas une restitution réalisée. Les remboursements réussis et ceux encore réservés ne dépassent pas la somme encaissée.
- BR-47 : l'annulation commerciale ne prouve ni annulation prestataire ni remboursement. Une exécution manuelle d'une restitution conserve une référence vérifiable.

## Transport

- BR-48 : une seule évaluation de transport appartient à chaque commande dans le MVP.
- BR-49 : les mesures peuvent porter sur poids ou volume. Une mesure réelle et une estimation sont des faits distincts.
- BR-50 : les observations et calculs sont conservés avec leurs révisions. Une correction ajoute une nouvelle observation ou un nouveau calcul, avec motif.
- BR-51 : les méthodes prévues sont PER_KG, PER_CBM, PER_ITEM et FIXED. PER_ITEM se rapporte à la somme des quantités ; FIXED est par commande.
- BR-52 : une quantité facturable est déterminable pour chaque commande. Répartir un coût, poids ou volume collectif entre commandes est hors MVP.
- BR-53 : les modes de perception sont AFTER_FINAL_ASSESSMENT et ESTIMATED_AT_CHECKOUT.
- BR-54 : une estimation de transport payée n'établit pas que le transport définitif est réglé.
- BR-55 : le calcul du transport et le suivi des encaissements sont deux responsabilités distinctes. La gestion de commande rapproche leurs résultats.
- BR-56 : le solde est le prix final diminué des règlements nets affectés au transport, y compris la part transport du paiement initial. Les remboursements affectés au transport diminuent ces règlements nets.
- BR-57 : un solde positif est à percevoir, un solde nul est réglé, un solde négatif est un trop-perçu à régulariser. Aucun portefeuille client implicite n'est créé.
- BR-58 : une nouvelle révision ne provoque pas une deuxième obligation pour une somme déjà couverte par une obligation en cours. Les demandes anciennes non résolues sont traitées explicitement.
- BR-59 : l'absence de mesure ou de calcul final n'est pas assimilée à un montant nul ou à une dette réglée.

## Précision, répétition et audit

- BR-60 : tous les montants ont une devise et utilisent une précision décimale exacte. Les charges finales XOF sont entières, arrondies HALF_UP sauf exigence prestataire explicite.
- BR-61 : le poids n'est pas arrondi avant calcul. Les résultats intermédiaires conservent leur précision ; l'arrondi intervient à la charge finale.
- BR-62 : rejouer un fait ne duplique ni commande, ni obligation, ni charge, ni remboursement, ni notification dommageable.
- BR-63 : un traitement échoué reste identifiable et reprenable. Les reprises répétées sont bornées, visibles et investigables.
- BR-64 : une panne de notification ne transforme pas une commande placée ou un paiement réussi en échec.
- BR-65 : les actions sensibles sont auditables sans exposer mots de passe, jetons, secrets de paiement ou documents personnels sensibles.

## Questions ouvertes et blocage par slice

Ces questions ne remettent pas en cause les décisions validées. Le premier vertical slice est désormais identity/authentification/organization/OWNER, livré sans les autres fonctionnalités. Téléphone/mot de passe, JWT et création atomique du premier OWNER résolvent les choix d'authentification et de bootstrap. Les questions restantes bloquent seulement les parcours indiqués.

| Question restant à trancher | Slice réellement bloqué |
| --- | --- |
| Premier parcours et critères | Résolu : inscription, login, création atomique avec OWNER, liste et lecture autorisées, refus inter-organisation |
| Authentification et premier OWNER | Résolu : téléphone normalisé + mot de passe, JWT ; le créateur authentifié devient OWNER dans la transaction de création |
| Qui encaisse et pour quel compte marchand ? Frais fixes configurables et total initial sont définis dans la quatrième tranche. | Intégration prestataire |
| Date retenue pour INITIAL_ORDER : validation Groupia avant échéance stricte (ADR-0010). La fenêtre reste configurable, 30 minutes par défaut. Le traitement commercial des paiements en vol à la clôture de campagne reste à préciser. | Clôture opérationnelle |
| Quels tarifs/unités et quelle définition du poids ou volume facturable sont proposés au lancement ? Le gel des conditions de transport après OPEN est désormais fixé par la troisième tranche. | Devis et facturation transport ; les snapshots historiques restent acquis |
| Quelles annulations sont permises, à quels stades et avec quels frais conservés/restitués ? | Annulation et remboursement |
| Existe-t-il un plafond de capacité ou un seuil minimal de groupage ? | Vente d'offres concernées si une telle contrainte existe |
| Comment reconnaître la disponibilité physique et autoriser concrètement le retrait ? | Distribution/retrait |
| Un montant initial nul est-il autorisé ? Si oui, comment confirmer sans tentative artificielle ? | Confirmation future des commandes concernées |
| Comment renouveler/récupérer l'accès privé après perte de jeton, avec quelle preuve ? | Récupération du suivi privé ; pas son principe de jeton hashé |

Avant activation d'un flux événementiel : fixer aussi les paramètres de reprise, l'alerte et le responsable opérationnel. Les détails du fournisseur de paiement et les règles d'authenticité doivent être établis avant son intégration.

## Hors MVP

Multi-devise ; panier multicampagne ; paiement fractionné volontaire ; portefeuille client ; retrait partiel ; multi-entrepôt ; répartition collective du fret ; gestion fournisseur complète.

Les paiements tardifs, doubles encaissements, réconciliations et traces de remboursement ne sont pas des extensions facultatives du MVP dès que des fonds réels sont engagés.

Contrats et politiques techniques de la tranche livrée : [API identity/organization](identity-organization-api.md) et [ADR-0006](../adr/0006-identity-organization-slice.md).

## Précisions catalogue livrées le 20 septembre 2026

- Pour BR-09, tout produit ACTIVE conserve au moins une variante active ; la désactivation de la dernière est refusée même en concurrence. DEFAULT est créé par le serveur sans variantes explicites et peut être désactivé après ajout d'une autre variante active.
- Les combinaisons normalisées et SKU restent uniques même après désactivation ou archivage. Un SKU absent est null ; son unicité est limitée à l'organisation.
- Les variantes restent sous le contrôle de Product : toute écriture exige la version observée de la racine et s'exécute dans sa transaction.
- Product ARCHIVED reste consultable, avec ses variantes, mais ne peut plus être édité. Aucun hard delete ou endpoint de réactivation.
- VIEW_CATALOG : les quatre rôles ; MANAGE_CATALOG : OWNER et MANAGER. Compte actif, adhésion ACTIVE et organisation ACTIVE restent requis.
- Les attributs sont des libellés textuels normalisés, les estimations sont décimales positives sans arrondi de stockage. Aucun prix de campagne dans le catalogue.

Contrat et limites : [API catalogue](catalog-api.md), [ADR-0007](../adr/0007-catalog-variant-identity-and-concurrency.md). Les autres parcours restent non implémentés.

## Précisions groupage livrées le 20 septembre 2026

- Les seules transitions exécutables sont DRAFT → OPEN, OPEN → CLOSED et DRAFT/OPEN/CLOSED → CANCELLED ; les étapes logistiques futures ne sont pas simulées.
- L'ouverture exige au moins une offre active, ses variantes utilisables et des conditions de transport cohérentes. Les références catalogue sont vérifiées via son API publique Java.
- Les conditions de transport sont figées à OPEN. Titre, description et échéance restent modifiables en DRAFT/OPEN ; une nouvelle échéance OPEN doit être future ou absente.
- Les prix XOF sont entiers et non négatifs. Les changements de prix incrémentent priceRevision et laissent une trace durable locale avec acteur, avant/après et instant. Les changements d'échéance sont aussi journalisés.
- En OPEN, la dernière offre active ne peut pas être désactivée, y compris en concurrence. Les offres inactives restent persistées et peuvent être réactivées si leur variante est utilisable.
- Une campagne jamais ouverte n'est pas publique. Après publication, CLOSED/CANCELLED restent consultables si l'organisation est active ; le motif privé d'annulation reste exclu.
- orderable est une donnée serveur dérivée : organisation active, présence d'offre utilisable, statut OPEN et now strictement avant closesAt lorsqu'il existe. À échéance dépassée, aucun scheduler n'est requis.
- Les prix et versions vus publiquement restent des témoins pour le futur checkout, jamais une autorité client. Cette tranche ne place aucune commande.

Voir [API groupage](grouping-api.md) et [ADR-0008](../adr/0008-grouping-publication-and-coordination.md).

## Précisions checkout livrées le 20 septembre 2026

- Customer : prénom, nom, téléphone obligatoire E.164, email facultatif ; aucun compte ni fusion téléphonique.
- Une nouvelle commande est uniquement PENDING_PAYMENT / NOT_READY. Le pays SN vient de l'organisation, conformément au périmètre actuel.
- L'admission exige OPEN, organisation active, échéance exclusive non dépassée, offres de la campagne et catalogue utilisable. Prix attendu et priceRevision doivent correspondre ; sinon 409 sans placement.
- Les offres dupliquées sont refusées ; les lignes sont triées pour l'empreinte. Les montants sont exclusivement recalculés côté serveur.
- Frais fixes XOF externes (300 par défaut de développement), règle snapshotée. Fenêtre de paiement configurable, 30 minutes par défaut.
- Les quatre méthodes de transport sont calculées sans arrondi intermédiaire. Le transport initial vaut zéro en AFTER_FINAL_ASSESSMENT ; ESTIMATED_AT_CHECKOUT exige une estimation connue.
- Une clé d'idempotence dans l'organisation crée au plus une commande et un contact. Le rejeu retourne une référence sans secret ou données privées ; les détails nécessitent le secret de suivi.
- Le secret aléatoire est remis une fois, hashé seulement en base, limité en durée et révocable. Suivi protégé par limitation locale des essais ; aucun droit par UUID, référence ou téléphone.
- Les coordonnées, descriptions, attributs, SKU, estimations, quantités, prix, frais/règle et conditions de transport sont historiques.
- VIEW_ORDERS est accordé aux quatre rôles, avec compte, adhésion et organisation actifs.
- Les commandes à total nul restent PENDING_PAYMENT comme les autres dans cette tranche ; aucune confirmation artificielle. Leur résolution future demeure une décision de paiement.
- Aucun événement aval ni obligation financière ne sont créés ici.

Contrat : [API commandes](ordering-api.md). Coordination/rejeu :
[ADR-0009](../adr/0009-checkout-admission-idempotency-and-tracking.md).

## Règles concrétisées par la cinquième tranche

- Checkout et obligation INITIAL_ORDER sont atomiques, y compris en cas d'échec de création de l'obligation.
- Une clé d'initiation retrouve une tentative ; une tentative incertaine interdit un nouveau débit automatique. FAILED/CANCELLED/EXPIRED certain autorise une nouvelle clé si l'obligation est encore ouverte.
- Un succès immédiat ou un webhook SUCCEEDED exige une vérification prestataire cohérente. Un timeout ne devient jamais un faux FAILED.
- À la date de validation Groupia >= expiresAt, un succès réel devient LATE_SUCCESS sans règlement ni réactivation. Un second succès reste SUCCEEDED/DUPLICATE_SUCCESS, sans remplacer l'affectation initiale.
- La confirmation Order est une conséquence idempotente du règlement de la demande, pas du seul statut d'une tentative.
- Expiration et fermeture d'acceptation sont atomiques ; un règlement déjà accepté empêche l'expiration pendant une reprise de confirmation.
- XOF entier validé, ventilation et montants immuables ; aucun remboursement/solde transport opérationnel.
- VIEW_PAYMENTS est accordé à OWNER/MANAGER/VIEWER ; OPERATOR ne reçoit pas cette lecture financière par défaut.
- Obligation nulle : stockage autorisé, aucune initiation de débit ni confirmation artificielle ; décision commerciale ultérieure nécessaire si cette configuration est utilisée.

[ADR-0010](../adr/0010-financial-core-and-settlement.md) et [procédures de reprise](payment-operations.md).
