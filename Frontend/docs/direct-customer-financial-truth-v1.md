# Vente directe — lecture financière fiable côté cliente (V1)

## But

Le lien privé de suivi (#fragment `access=`) demeure une capacité de lecture, jamais un
moyen de déclencher un encaissement ou une remise. L'écran client ne doit plus annoncer
« aucun paiement n'a été effectué » après un règlement réellement vérifié.

## Contrat public

`POST /api/public/direct-orders/payment-status` reçoit uniquement
`{ "token": "<jeton privé de 256 bits>" }`. Le jeton ne figure ni dans une URL GET,
ni dans les logs, ni dans la réponse. L'ordre est résolu uniquement par son empreinte
SHA-256 séparée par domaine. Aucun `orderId`, téléphone ou montant du navigateur ne fait foi.

Réponse limitée aux champs déjà prévus plus `financialStatus` :
`orderNumber`, `currency`, `acceptedTotalXof`, `preparationStatus`,
`paymentAvailable=false`, `financialStatus`.

États : `NOT_PREPARED`, `PREPARED`, `EXPIRED`, `UNAVAILABLE`,
`SETTLEMENT_PROCESSING`, `SETTLEMENT_VERIFIED`, `REVIEW_REQUIRED`.

## Preuve et sécurité

- La lecture rapproche la commande par jeton avec son obligation
  `DIRECT_ORDER_INITIAL`, son organisation, sa cible, ses montants XOF,
  son acceptation et son échéance.
- `SETTLEMENT_VERIFIED` exige une obligation `SETTLED`, un reçu immutable
  de règlement, le même paiement, les montants exacts et une preuve fournisseur
  canonique `CLOSED/SUCCEEDED` sans reversal.
- Le fournisseur, l'identifiant transactionnel et la date de confirmation doivent
  correspondre au journal immutable. Une commande doit être au moins
  `PAYMENT_CONFIRMED` pour être affichée comme réglée.
- Reconciliation, reversal, montant discordant, reçu absent ou progression sans
  preuve donnent un état bloquant. `SETTLED` sans reçu = `SETTLEMENT_PROCESSING`.
- Le client Angular compare aussi l'identifiant public, la monnaie et le total
  entre les deux réponses indépendantes ; en cas de réponse absente/incohérente,
  la page n'affiche pas de paiement confirmé ni de préparation prête.
- Aucune remise, expédition, initiation de paiement ou reversement n'est autorisé
  par cette lecture. Les mutations physiques devront revérifier la preuve sous verrou.

## Limites

- `DIRECT_PAYMENT_NOT_ENABLED` reste actif dans le module paiement : **aucune charge
  Bictorys ne peut être initiée par cette tranche**.
- `paymentAvailable=false` est conservé jusqu'à l'intégration d'une commande
  d'initiation sécurisée avec validation, idempotence et réconciliation.
- Ne pas utiliser `financialStatus` comme autorisation de livraison ; c'est une
  vue de lecture instantanée, non un contrôle transactionnel.
- Les risques de race entre annulation, tentative de paiement et verrou financier
  seront traités dans le lot d'ouverture du paiement direct.

## Vérifications

Tests unitaires : absence de jeton, autorisation par jeton exact, absence d'obligation,
obligation préparée, `SETTLED` sans reçu, preuve canonicale, reversal,
réconciliation, incohérences de montant et de statut.

Tests Angular : même jeton pour les deux lectures, comparaison stricte,
pas de paiement simulé, absence de confirmation si une lecture échoue,
protection contre les réponses anciennes après rotation du lien.

## Qualité de la branche

Le formatage des sources et des tests Java a été appliqué avec le formatteur
`googleJavaFormat` 1.28.0, style `AOSP`, identique à la configuration Spotless
vérifiée par GitHub Actions. Aucune dérogation au contrôle financier ni à la CI.
