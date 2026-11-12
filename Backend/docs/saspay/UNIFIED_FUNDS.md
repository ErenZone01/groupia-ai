# Groupia — rapprochement des fonds groupage et vente directe

État : premier lot de convergence du registre financier, PR #86, non fusionnée.
Ce lot ne crée aucun nouveau versement réel, ne modifie aucune migration historique
et ne débloque pas l'adaptateur SasPay de production.

## Source de vérité retenue

- `provider_collections` conserve l'encaissement confirmé par fournisseur,
  compte marchand authentifié, cible `ORDER` ou `DIRECT_ORDER`, montant
  attribué à la vendeuse et frais de paiement. Le compte source exact reste
  la clé de cloisonnement des fonds.
- Le nouveau `GET /api/organizations/{organizationId}/provider-collections/overview`
  agrège par fournisseur et compte, en montrant séparément groupages et ventes
  directes, puis leurs cumuls historiques. Accès `VIEW_PAYMENTS`, aucun
  identifiant de compte marchand brut n'est publié (empreinte SHA-256).
- Les montants sont **des cumuls historiques d'encaissements**, **pas**
  des soldes disponibles : les annulations, remboursements, réservations et
  reversements ne sont pas soustraits de ce total brut. Ne jamais utiliser
  ce calcul pour initier un transfert.
- Le tableau de bord `/app/encaissements-fournisseurs` consomme ce récapitulatif
  tout en conservant l'historique et les simulations existantes. Si la lecture
  de synthèse échoue, l'historique reste consultable.
- Les simulations marketplace existantes restent utilisables sur les deux
  catégories de ventes et ne déplacent aucun argent.

## Ce qui reste pour **un solde unifié retirable**

1. Créer une projection d'obligations vendeuses par encaissement, avec
   identification de la cible, des remboursements commerciaux et des risques
   financiers actifs. Ne compter ni deux fois une collection ni des frais PSP
   comme fonds de vendeuse.
2. Étendre les réservations et dépenses du moteur `merchant_payouts` aux
   ventes directes, sans contourner les contrôles de preuve du fournisseur,
   la clôture commerciale, la concurrence et l'isolation fournisseur/compte.
   Le moteur actuel est lié aux campagnes (`campaign_id` obligatoire).
3. Réconcilier chaque débit et reprise incertaine, y compris après redémarrage,
   puis prouver en tests PostgreSQL qu'aucune collection ne finance deux retraits
   ou un retrait et un remboursement.
4. Exposer les **montants réellement retirables** séparément des cumuls
   historiques et réutiliser les mêmes parcours de destination, confirmation
   et historique sur les deux activités.
5. Les retraits LIVE SasPay restent interdits jusqu'à autorisations externes,
   preuve authentifiée du bénéficiaire et des frais de payout réservés à part.
   `PRODUCTION_PAYOUT_RELEASED=false` ne doit pas changer.

## Contrôles

Code ajouté : test PostgreSQL d'agrégation et d'isolation entre organisations,
test Angular de présentation sans fausse promesse de retrait.

Ces tests doivent être exécutés localement sur la nouvelle tête de branche.
Les validations antérieures à ce lot ne valent pas validation de ce lot.
La route OpenAPI ajoutée doit être exportée et le snapshot frontend régénéré
avant la fusion. GitHub Actions n'est pas déclaré vert.
