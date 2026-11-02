# Vente directe — raccordement transactionnel commande → obligation (V1)

## Objectif
Lier un checkout de vente directe validé à l'obligation de paiement
`DIRECT_ORDER_INITIAL` du module `payment`, sans ouvrir la passerelle Bictorys.

## Activation
Le raccordement est **désactivé par défaut** :

```properties
groupia.direct-sales.payment-obligation.auto-prepare.enabled=false
```

Il n'est exécuté que si `groupia.direct-sales.checkout.enabled=true`
et si ce nouveau flag est explicitement activé.

## Ordre des opérations
1. La boutique est verrouillée pendant le checkout et le total client est contrôlé.
2. La commande et **toutes** ses lignes sont sauvegardées.
3. Pour une nouvelle commande avec total accepté : un retrait, ou une
   livraison GPS `REGION_MATCHED`, déclenche `DirectPaymentObligations.prepare`.
   Cette vérification relit en base le total, les lignes, le tarif et la région ;
   elle refuse les données incohérentes et les régions devenues interdites.
4. La création de l'obligation utilise la même transaction : si elle échoue,
   la commande et ses lignes sont annulées.
5. Un rejeu du même `Idempotency-Key` retourne la commande existante sans
   réémettre d'obligation.
6. Les commandes historiques sans total accepté et les adresses
   `UNVERIFIED_MANUAL` continuent d'être des brouillons sans obligation.

## Sécurité et limites
- Aucun montant ne peut être fourni directement au module paiement par le client.
- L'unicité et l'isolation par boutique/organisation sont conservées.
- `paymentAvailable` reste `false` partout.
- Aucune redirection de paiement, tentative, callback, confirmation, facture
  ou réservation de stock n'est introduite.
- La garde `DIRECT_PAYMENT_NOT_ENABLED` dans
  `PaymentTransactions.claim` continue de bloquer Bictorys.
- L'activation ultérieure nécessitera un parcours client authentifié, une
  gestion explicite des statuts de commande et des webhooks vérifiés.

## Tests
- Tests unitaires du raccordement : flag off, livraison GPS validée,
  retrait, refus d'une commande non chiffrée ou manuelle, propagation d'erreur.
- Tests d'intégration existants activés pour le raccordement : obligation
  `DIRECT_ORDER_INITIAL` unique, cible et montant exacts, rejeu sans
  duplication, absence de tentative de paiement, changement de tarif.
- CI complète : Backend, migrations, Modulith, Spotless, Frontend, Security.
