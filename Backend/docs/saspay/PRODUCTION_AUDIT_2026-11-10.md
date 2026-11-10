# Audit SasPay / Groupia — 10 novembre 2026

**État : audit technique, pilote LIVE non validé. PR #86 reste en brouillon.**
Aucun débit, transfert, remboursement ou mise à jour de l'historique n'a été lancé.

## Documents et références vérifiés

- `skills/saspay-integration/SKILL.md` et `AGENTS.MD` (bundlés)
- `Backend/docs/saspay/openapi.json`
- https://docs.saspay.me/api-reference/payments/softpay
- https://docs.saspay.me/api-reference/payments/verify
- https://docs.saspay.me/api-reference/webhooks
- https://docs.saspay.me/api-reference/transactions/list
- https://docs.saspay.me/api-reference/wallet/balances-list
- https://www.saspay.me/conditions-utilisation

**Ne pas confondre** SasPay (`saspay.me`, `sk_live_`) et
SasaPay (`sasapay.app`, autre API). Les tutoriels et générateurs
d'intégration montrent parfois des démos, pas des réconciliations
financières et marketplace de production. Un tiers
https://afro.tools/providers/saspay recense des fonctionnalités testées,
mais pas les autorisations du compte Groupia.

## Preuves de l'incident du 10 novembre

- Les réseaux Wave/Orange proposent un devis ; le payin réel n'est
  **pas** confirmé.
- Les événements ont été reçus en HTTP 202 et leur HMAC a été vérifié,
  mais les reçus sont `REVIEW_REQUIRED / UNKNOWN_REFERENCE` (5 essais).
- Tentative locale `515eb303-...` : `CREATED`,
  `PROVIDER_STATE_UNCERTAIN`, `provider_transaction_id=NULL`.
- IDs du dashboard (sans tirets) : `d6b9b3d0e4ed4341ae147d7f56909361`
  et `253c31ba9b5444f8958ea6d78d1bbe87`.
- IDs des deux **webhooks signés** : `cb8f14cd-5dad-4793-b305-e65e1365908d`
  et `bb02b65a-0622-42d4-a4fe-069ee840386a`. Ce ne sont PAS
  les mêmes identifiants. Les heures ne suffisent pas à les associer.
- La clé API `Backend/.env` renvoie HTTP 404 sur `verify` et
  `transactions/{id}` pour les IDs du dashboard **y compris Expresso**.
- L'outil Python précédent n'acceptait pas les réponses paginées
  `{"success":true,"data":{"results":[...]}}`, donc l'audit de
  visibilité était incomplet. Correctif publié, tests à rejouer.
- Prochain contrôle : les **deux IDs signés** via GET `verify` et
  la visibilité de l'identité marchande via `--audit-scope`.

## Options SasPay réellement documentées

| Option | Adéquation Groupia | Limites |
|---|---|---|
| `POST /payments/softpay/` | Choix Wave/Orange et devis dans Groupia, idempotence | `checkout_url` peut nécessiter redirection ; vérifier ensuite via `GET /payments/{id}/verify/` |
| `POST /checkout-sessions/` | Page SasPay de sélection réseau et saisie numéro | Ne bloque ni réseau ni frais exacts dans le POST ; pas d'Idempotency-Key ; nécessite reprise de session et `GET /checkout-sessions/{id}/status/` |
| `POST /payment-links/` | Liens manuels ou factures | Pas d'idempotence à la création ; association à commande et anti-doubles nécessaires |
| `POST /payouts/initialize/` | Versements autorisés | Nécessite permissions, KYC et IP whitelistée ; ne pas activer sans preuve de destinataire |
| Abonnements | Factures mensuelles Groupia + paiement payin | L'OpenAPI consultée ne décrit pas de création d'abonnement récurrent par API ; ne pas l'inventer |

Un champ `merchant` transmis avec une clé API marchande est ignoré par
`softpay`, d'après son contrat. Le `net_amount` correspond donc à
l'encaissement du marchand configuré, **pas automatiquement à une
vendeuse Groupia**. Les CGU SasPay décrivent SasPay comme une couche
technique, et non comme un établissement conservant les fonds.

### Recommandation d'architecture

**Ne pas réécrire le projet maintenant.** D'abord corriger l'identité
des transactions et la traçabilité de l'initiation, puis choisir :

1. Conserver **softpay** pour Wave/Orange si l'ID renvoyé par le POST
   est vérifiable avec le même compte, et le `checkout_url` est présenté
   au client après validation serveur de l'hôte.
2. Préparer un **checkout hébergé** séparé pour améliorer l'UX si
   `ADD_ON` est effectivement activé chez SasPay et si le client
   accepte le total sur le checkout SasPay. Groupia doit garder
   une session unique par intention, puis vérifier montant, devise,
   net et identité marchand via endpoints officiels avant livraison.
3. Ne pas faire de payout direct ni d'abonnement auto-prélevé sans
   contrat API et autorisations réelles.

## Correctifs déjà appliqués dans la PR #86

- HMAC webhooks et idempotence ; limitation LIVE à une organisation.
- Frais exacts décimaux XOF sous opt-in et migrations 052/053.
- Enregistrement de l'ID fournisseur même si le `checkout_url`
  est rejeté après création ; double allowlist frontend supprimée.
- Diagnostic `GET` uniquement, sans secrets/PII, désormais compatible
  avec enveloppes `success/data/results`.
- `SaspayClient.uuid` normalise les UUID **32 caractères hexadécimaux**
  ou **36 caractères avec tirets**, avec rejet des formats invalides.

## Matrice de validation avant toute nouvelle tentative LIVE

- Tests Java contrat : UUID compact/dash, réponses 201/200/404/409/429,
  HTTP GET sans débit, hôte de redirection validé et refusé, contrat ADD_ON.
- PostgreSQL Testcontainers : migration 052/053 ; webhook avant réponse
  POST ; duplication ; redémarrage ; succès vérifié ; échec ; ledger exact.
- Angular + Playwright : Wave/Orange, redirection, mobile,
  retour checkout, double-clic et reprise idempotente.
- Exécuter réellement `mvn spotless:check`, Maven ciblé, vérification
  complète et tests Python ; relever les résultats précis.
- Ne jamais lancer de test Maven contre une base de production.
- Ancienne tentative `DIR-000000000015` reste bloquée. Ne pas
  lier par montant, numéro ou heure, ni faire un UPDATE SQL manuel.
- Un seul futur paiement pilote réel, après **résolution explicite
  des deux opérations déjà présentes** et autorisation de l'utilisateur.
