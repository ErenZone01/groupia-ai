# Commandes directes — consultation privée V1

Cette tranche permet aux gérantes **autorisées à consulter les commandes** de retrouver
les brouillons directs créés à titre de fondation backend. Les commandes des
groupages et leurs règlements restent entièrement indépendants.

## Endpoints

Authentification Bearer et permission `VIEW_ORDERS` obligatoires :

- `GET /api/organizations/{organizationId}/direct-orders?page=0&size=20`
  (pagination de 1 à 50, aucune coordonnée personnelle renvoyée dans la liste)
- `GET /api/organizations/{organizationId}/direct-orders/{orderId}`
  (snapshots historiques de contact, adresse éventuelle, articles, prix, frais)

Le serveur filtre systématiquement la lecture par `organization_id`. Une
commande appartenant à une autre organisation renvoie 404, même avec son UUID,
pour un acteur autorisé sur sa propre organisation. Les réponses sont en
`Cache-Control: no-store`, sans endpoint public, sans export, sans mutation.

## Interface

Dans `/app/vente-directe`, le lien « Consulter les commandes » ouvre
`/app/vente-directe/commandes`. Changer de boutique ou de session efface les
résultats et le détail de la boutique précédente.

Le statut `PENDING_PAYMENT_INTEGRATION` signifie **aucun paiement confirmé**.
Le montant affiché est **avant livraison**. Aucune action de remise, livraison,
encaissement ou remboursement ne peut être exécutée dans cette tranche.

## Contrôles

- Tests d'intégration des accès anonymes interdits, absence de fuite des contacts
  dans les listes, permissions et séparation de deux organisations.
- Tests du contrat HTTP Angular (liste / détail).
- À tester localement : `mvnw.cmd verify`, `npm run lint`, `npm test`,
  `npm run build`, Playwright direct-sales.

Cette tranche **ne change pas** `GROUPIA_DIRECT_CHECKOUT_ENABLED=false`, ne
créé aucune obligation Bictorys et ne transforme pas un brouillon en commande
payée. Les paiements directs nécessiteront un type de cible dédié et des
transitions de statut basées sur des preuves fournisseur vérifiées.
