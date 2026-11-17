# Retraits SasPay standardisés

Le mode normal `SASPAY_PAYOUT_GENERAL_ENABLED=true` (OFF par défaut) supprime les
limitations exceptionnelles du pilote : commande unique, boutique unique et 1 000 XOF.
La destination active, le montant disponible de chaque vente/groupage, le devis et ses
frais, la provenance SasPay des fonds, les remboursements, les réservations, les
permissions de la vendeuse et les clés d'idempotence sont toujours contrôlés côté serveur.
L'activation exige aussi les interrupteurs habituels `GROUPIA_MERCHANT_PAYOUTS_ENABLED`,
`GROUPIA_DIRECT_MERCHANT_PAYOUTS_ENABLED` pour la vente directe,
`GROUPIA_MERCHANT_PAYOUT_PROVIDER=SASPAY`, `SASPAY_PAYOUTS_ENABLED`,
`SASPAY_PAYOUTS_MARKETPLACE_AUTHORIZED` et
`SASPAY_PAYOUTS_BENEFICIARY_PROOF_APPROVED`. Aucune clé de production ne doit se trouver
dans Git. La politique du fournisseur (montant minimal, pays, réseau) reste applicable :
ce code ne permet pas de contourner une restriction côté SasPay.

Un échec avant le POST (prévol GET ou validation locale) passe à FAILED et libère sa
réserve, puisqu'aucune initialisation n'a été tentée. Dans PostgreSQL, une
intention terminale FAILED conserve `reconciliation_required=false` et
`reconciliation_reason=NULL` conformément à la contrainte historique
`reconciliation_required=(reconciliation_reason IS NOT NULL)`. Les codes de
refus reçus sont enregistrés dans les diagnostics serveur expurgés des secrets ;
ils ne doivent pas être placés dans le champ réservé au rapprochement. Un HTTP 403 avec réponse JSON
reconnue `payout_not_enabled`, `ip_not_whitelisted` ou `merchant_suspended`
est traité comme un rejet de l'initialisation : la tentative passe à FAILED.
Le commerçant peut alors demander un **nouveau devis** et une **nouvelle intention**
avec une nouvelle clé d'idempotence. L'ancienne reste tracée ; aucun POST automatique.

En cas de timeout sur le POST, de HTTP 409/429/5xx, de réponse sans code
reconnu ou de résultat financier en attente, la réserve est conservée jusqu'au
rapprochement fournisseur. Un statut Groupia HTTP 200 ne signifie jamais que le
transfert est réussi : lire le statut métier FAILED / REVIEW_REQUIRED / PENDING /
SUCCEEDED et vérifier le règlement par des preuves authentifiées.

**Historique** : DIR-21 et DIR-24 ont été créées avant ces corrections. Leur
statut REVIEW_REQUIRED et leurs réserves ne sont pas effacés automatiquement.
Pour l'ancien POST de DIR-24 explicitement refusé en HTTP 403, l'opérateur peut
utiliser le dispositif existant `CLOSE_NON_EXECUTED` avec le dossier
`PROVIDER_HTTP_REJECTION` **uniquement si** le motif enregistré de l'intention
est exactement `SASPAY_PAYOUT_HTTP_REJECTED` et si un dossier revu, fixé par
SHA-256 côté serveur, reproduit la réponse négative reçue par le backend et
couvre toutes les tentatives pour cette clé, excluant une exécution différée.
La capture du tableau de bord vide seule n'est pas une preuve. Le dossier,
l'identité de la demande et la version sont revérifiés et l'opération auditée
dans une transaction. Si le motif a été remplacé par un état moins précis,
ou si la provenance manque, ne pas contourner ces exigences : demander une
confirmation corrélée de SasPay. L'ancien DIR-21, resté incertain après
récupération, n'est pas éligible sur la seule base du HTTP 403 de DIR-24.
Une procédure de clôture auditée est nécessaire sur preuve suffisamment corrélée.
Ne pas modifier les soldes par SQL, supprimer la ligne, réemployer l'ancienne
clé fournisseur ni rejouer son POST. La clé et la référence historiques doivent
rester dans le registre des opérations.

Déployer d'abord avec `SASPAY_PAYOUT_GENERAL_ENABLED=false`, effectuer les
tests PostgreSQL, sécurité, backend et frontend, valider le support fournisseur et
les preuves sur les anciennes opérations, sauvegarder la base, puis procéder à
une activation maîtrisée. Les GET de rapprochement historiques restent autorisés.
