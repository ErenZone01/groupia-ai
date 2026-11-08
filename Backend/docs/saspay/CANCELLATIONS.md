# Annulations commerciales et remboursements

Lot de la PR #86, 8 novembre 2026. Aucun appel de transfert ou remboursement SasPay n’est ajouté.

## Parcours

La vendeuse ouvre « Annulation et remboursement » depuis le détail d’une commande de groupage ou directe. Le serveur exige MANAGE_FULFILLMENT, une confirmation, un motif, la version attendue et une clé UUID persistante. Les lectures exigent VIEW_ORDERS ; les détails financiers exigent VIEW_PAYMENTS. MANAGE_REFUNDS est nécessaire pour déclarer un remboursement déjà effectué hors Groupia. Ces opérations ne demandent pas un abonnement actif : elles concernent des obligations existantes.

Une commande directe payée peut être annulée avant expédition ou remise (PAYMENT_CONFIRMED, PREPARING, READY_FOR_PICKUP, READY_FOR_DISPATCH). Une commande de groupage payée peut l’être avant commande fournisseur, ou lors de l’annulation de sa campagne. Une commande déjà remise exige un traitement de retour distinct ; le présent flux ne la transforme pas en commande jamais exécutée.

L’annulation d’une campagne DRAFT/OPEN/CLOSED émet un événement Modulith persistant. Chaque commande est traitée dans sa propre transaction durable : un paiement incertain ne doit pas bloquer les autres commandes. Un règlement vérifié tardivement réévalue l’annulation, même après épuisement des reprises initiales. Les publications échouées restent consultables selon la procédure de récupération existante ; les anciennes campagnes annulées avant la migration, sans acteur enregistré, nécessitent une annulation individuelle autorisée pour leur dossier.

Le verrouillage campagne → commande → obligations protège la concurrence entre paiement, annulation et remise. L’API interne de lecture/verrouillage ne modifie pas la campagne ; les routes commerciales et les capacités publiques assurent l’autorisation avant son utilisation. Les consommateurs durables n’utilisent pas une session HTTP de gérante.

## Argent et preuves

Le dossier conserve les obligations réglées et leurs identifiants fournisseur/compte/transaction, le principal encaissé, les remboursements de transport déjà réussis et le montant restant. Un paiement ou remboursement incertain bloque la création ou la déclaration. Les obligations non réglées sont fermées à de nouvelles tentatives. Le paiement d’origine reste SUCCEEDED ; les snapshots de commande et les sources sont immuables.

REFUND_PENDING interdit la préparation et la remise. La déclaration externe exige la référence du remboursement (ou une note pour les espèces) et une confirmation du montant serveur. Une même référence et une même intention ne peuvent créer plusieurs déclarations. EXTERNALLY_RECORDED signifie « remboursement externe déclaré », jamais « vérifié par SasPay ». Son événement idempotent place la commande en REFUNDED. Aucun montant fourni par le navigateur n’est accepté.

Le montant à restituer ne comprend pas des frais pay-in dont le remboursement n’est pas documenté ; cette limite est visible avant la déclaration. Les simulations marketplace restent séparées de ce dossier et ne modifient pas les statuts réels.

Après restitution sur les fonds propres de la vendeuse, le principal retenu peut redevenir disponible dans le compte fournisseur d’origine, selon les protections existantes. Les fonds SasPay restent exclus des payouts réels. Les anciens frais Groupia sont affichés comme une compensation distincte due par Groupia ; tant que cette compensation n’est pas réglée par un processus financier validé, ce dossier historique reste bloquant pour le reversement et la clôture financière. Aucune perte vendeuse n’est masquée. Le flux ne crée pas automatiquement une écriture de compensation non justifiée.

Le consommateur de remboursement externe rattrape aussi une projection d’encaissement absente : il relit le dossier canonique et ses sources immuables, puis écrit le crédit d’origine avec la même identité COLLECTION. Les doublons et la reprise des anciens événements ne peuvent donc pas créditer la vendeuse deux fois. Cette reprise conserve le fournisseur, les montants et la date du règlement d’origine.

La cliente authentifiée par téléphone conserve la possibilité de recevoir un lien privé en REFUND_PENDING ou REFUNDED, dans la durée d’archivage existante. Une confirmation de commande retardée ne produit pas un nouveau message « commande confirmée » après annulation. Aucun de ces états ne rouvre le paiement.

## Migrations et audit

049 : dossiers, sources et journal immuables. 050 : états de remboursement, référence de dossier sur les commandes et acteur de l’annulation de campagne. Les migrations 001–022 gelées restent inchangées. Le démarrage avec le profil prod refuse toute commission de commande non nulle ; les fixtures historiques hors production conservent leurs montants de 300 XOF.

L’audit confirme le contrôle des dates de campagne dans le prédicat commun d’admission et à nouveau sous verrou de checkout. Aucun objectif minimum de quantité ou de montant n’existe dans le modèle actuel : aucune annulation automatique sur un seuil supposé n’a été inventée. La vendeuse décide de clôturer ou d’annuler la campagne. Les messages obsolètes de vente directe annonçant un checkout futur ont été corrigés, sans refonte graphique.

Les tests locaux de ce lot et les vérifications globales sont consignés dans FOLLOWUP.md. Les simulations ne prouvent ni un transfert réel ni une autorisation marketplace.
