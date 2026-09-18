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

Ces questions ne remettent pas en cause les décisions validées. Le premier vertical slice n'a pas encore été choisi. Elles ne bloquent pas toutes une première livraison limitée au catalogue.

| Question restant à trancher | Slice réellement bloqué |
| --- | --- |
| Quel premier parcours complet doit être livré, avec quels critères d'acceptation ? | Planification du premier slice |
| Quel mode d'authentification organisateur et comment provisionner le premier OWNER ? | Tout parcours organisateur réellement accessible |
| Quels frais et montants composent le total initial ; qui encaisse et pour quel compte marchand ? | Checkout payant et intégration prestataire |
| Quelle durée de paiement, quelle date fait foi et comment traiter les paiements en vol à la clôture ? | Expiration, confirmation et clôture opérationnelle |
| Quels tarifs/unités et quelle définition du poids ou volume facturable sont proposés au lancement ? Les tarifs changent-ils aussi pendant OPEN, avec quelle règle pour les commandes existantes ? | Devis et facturation transport ; les snapshots historiques restent acquis |
| Quelles annulations sont permises, à quels stades et avec quels frais conservés/restitués ? | Annulation et remboursement |
| Existe-t-il un plafond de capacité ou un seuil minimal de groupage ? | Vente d'offres concernées si une telle contrainte existe |
| Comment reconnaître la disponibilité physique et autoriser concrètement le retrait ? | Distribution/retrait |
| Un montant initial nul est-il autorisé ? Si oui, comment confirmer sans tentative artificielle ? | Checkout des commandes concernées |
| Comment renouveler/récupérer l'accès privé après perte de jeton, avec quelle preuve ? | Récupération du suivi privé ; pas son principe de jeton hashé |

Avant activation d'un flux événementiel : fixer aussi les paramètres de reprise, l'alerte et le responsable opérationnel. Les détails du fournisseur de paiement et les règles d'authenticité doivent être établis avant son intégration.

## Hors MVP

Multi-devise ; panier multicampagne ; paiement fractionné volontaire ; portefeuille client ; retrait partiel ; multi-entrepôt ; répartition collective du fret ; gestion fournisseur complète.

Les paiements tardifs, doubles encaissements, réconciliations et traces de remboursement ne sont pas des extensions facultatives du MVP dès que des fonds réels sont engagés.
