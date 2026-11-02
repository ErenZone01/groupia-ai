# Groupia · Vente directe : récupération d'une commande par code OTP

## Besoin
La cliente peut perdre son lien personnel de suivi ou fermer l'onglet avant d'avoir
récupéré le résultat d'une commande. Un numéro `DIR-...` seul ne doit jamais
donner accès au contenu ni permettre de renouveler le secret de suivi.

## Réutilisation de la sécurité existante
Cette tranche **ne crée pas de second système OTP**. Elle utilise les routes
existantes `/api/public/customer-access/otp/request`,
`/otp/verify`, le même numéro normalisé E.164, les limites par téléphone et
IP, le contrôle des tentatives, le service de livraison WhatsApp/SMS configuré,
les cookies HttpOnly et la vérification CSRF.

Une session `PHONE_VERIFIED` est obligatoire : une session
`ORDER_ACCESS` issue d'un lien de groupage ne permet pas de consulter les
commandes directes d'un numéro.

Les nouvelles routes, dans le même périmètre de sécurité cookie + CSRF :
- `GET /api/public/customer-access/direct-orders?page=0&size=20`
  renvoie une liste limitée de références, boutique, statut, mode de remise,
  total accepté et date, uniquement pour le **numéro déjà vérifié dans la session**.
  Le navigateur ne transmet aucun téléphone dans cette requête.
- `POST /api/public/customer-access/direct-orders/{orderNumber}/rotate-link`
  vérifie à nouveau le propriétaire de la commande, limite le nombre de
  renouvellements par téléphone et IP, puis remplace atomiquement le hash de
  l'ancien jeton par celui d'un nouveau secret aléatoire de 256 bits.

L'ancien lien cesse immédiatement de fonctionner après rotation. Les
en-têtes `Cache-Control: no-store` et `Referrer-Policy: no-referrer`
s'appliquent à la réponse. Les données privées (nom, téléphone, email,
adresse, GPS) ne sont pas renvoyées par la liste.

## Interface
Depuis le suivi direct ou la confirmation de commande, cliquer
« J'ai perdu mon lien ». Le parcours demande le numéro WhatsApp, puis
un OTP à six chiffres. Une session déjà vérifiée est réutilisée.
La cliente retrouve ses commandes directes et peut choisir **Créer un
nouveau lien privé**. La nouvelle adresse est du format :

`/v/{publicIdentifier}/commande#access={token}`

Ce lien est un secret d'accès : toute personne qui le possède peut consulter
le résumé de la commande. Il n'est pas enregistré dans localStorage.

## Contraintes de déploiement et limites
- Nécessite `groupia.customer-access.enabled=true` et une configuration
  OTP/WhatsApp opérationnelle, **plus** les clés et cookies sécurisés
  existants. En cas de désactivation, la récupération retourne une erreur.
- `GROUPIA_DIRECT_CHECKOUT_ENABLED=false` reste la valeur par défaut.
  La récupération des commandes déjà créées fonctionne indépendamment
  de l'activation ultérieure de nouveaux brouillons.
- Pas d'encaissement Bictorys, aucun accès aux données de groupage et aucun
  changement de la machine d'états ou du stock.
- L'OTP confirme la possession d'un numéro, pas l'identité civile du client.
  Les numéros de téléphone réattribués peuvent représenter un risque : une
  politique de durée de conservation, archivage et procédures de litige est
  nécessaire avant l'ouverture commerciale.
- L'accès au lien renouvelé reste valable tant que l'enregistrement existe,
  sous réserve de la future politique d'expiration et révocation.

## Validation
CI Security, backend Modulith/Maven/Spotless + PostgreSQL, frontend
lint/unit/build/Playwright. Scénarios : requête anonyme refusée,
`ORDER_ACCESS` refusée, cookie sans CSRF refusé à la rotation,
numéro vérifié A ne lit pas les commandes B, nouvelle capacité valide,
ancienne capacité immédiatement rejetée, limites et absence de PII.
