# Vente directe — confirmation et historisation du total accepté (V1)

## But
Une estimation de livraison n'est pas un prix accepté. Cette tranche ajoute une
validation **côté backend et dans la transaction checkout** du total accepté par
la cliente, puis un snapshot immuable des frais de livraison, du total final et
de la version du tarif régional utilisé au moment de la création du brouillon.

Le paiement reste entièrement indisponible : les commandes portent le statut
`PENDING_PAYMENT_INTEGRATION`, `paymentAvailable=false`, aucun appel Bictorys.

## Contrat évolutif
Les anciennes requêtes `POST /api/public/direct-stores/{publicIdentifier}/checkout`
restent acceptées pour les brouillons non payables. Elles conservent leurs
empreintes d'idempotence historiques et n'enregistrent **pas** de total accepté.

Pour une nouvelle commande, le client peut transmettre, en plus du panier, du
contact et du mode de remise :
```json
{
  "fulfillmentMethod": "DELIVERY",
  "deliveryPoint": {"latitude": 14.6937, "longitude": -17.4474},
  "acceptedTotalXof": 3800
}
```
C'est un extrait partiel du corps de requête : `contact` et `lines` sont
également nécessaires, les lignes contenant les prix attendus et révisions
du catalogue, et la requête nécessite `Idempotency-Key`.

Sous le verrou de la boutique et dans la transaction :
1. vérifier les offres publiées, les quantités et révisions/prix actuels ;
2. recalculer la région GPS (ou utiliser la région manuelle **non vérifiée**),
   puis appliquer les zones de livraison configurées ;
3. relire les frais de livraison régionaux **actuels** (0 F autorisé seulement
   si configuré explicitement). Pour retrait : frais de livraison de 0 F ;
4. recalculer `articles + plateforme + livraison` avec contrôles d'overflow ;
5. si le total accepté est différent, refuser avec HTTP 409
   `DIRECT_TOTAL_CHANGED` avant la création d'une commande.

Une livraison sans tarif configuré est refusée en HTTP 409
`DIRECT_SHIPPING_FEE_UNCONFIGURED`, pas considérée gratuite. Le snapshot
enregistre `shipping_fee_xof`, `accepted_total_xof` et
`shipping_rate_version` (version nulle pour retrait).

L'empreinte idempotente des nouveaux brouillons intègre le total accepté ;
le même rejeu retrouve le prix d'origine même si les tarifs du vendeur ont
changé depuis. Une réutilisation de la clé avec un autre montant est refusée.

## Migration 037
Une migration additive PostgreSQL ajoute trois colonnes **nullable** à
`direct_orders` et met à jour la contrainte de devis pour permettre les
brouillons de livraison cotés. Les anciennes commandes restent conservées,
sans fausse rétrofacturation ni réinterprétation des montants.

## Limites importantes
Les adresses manuelles restent `UNVERIFIED_MANUAL`. Même une commande
avec `shippingQuoted=true` et un total accepté **ne peut pas être payée** tant
que le contrôle d'adresse, l'état de commande, la réservation et les flux
Bictorys ne sont pas développés. Un GPS de livraison n'atteste pas la présence
physique de la cliente.

**Le frontend public ne soumet encore aucune commande** dans cette tranche :
il faudra lui ajouter une étape explicite d'acceptation du total et un écran
de confirmation/reprise, avant d'activer le checkout public.

Tests : changement de tarifs après devis, refus d'un ancien prix, snapshot
d'une commande cotée, version tarifaire, rejeu idempotent et absence de
requête de paiement.
