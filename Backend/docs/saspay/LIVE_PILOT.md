# SasPay — pilote live à faible montant (préparation, non validé)

Ajouté après la validation locale du 9 novembre 2026 de la PR #86. **Les nouveaux changements de pilote n'ont pas encore été exécutés contre Maven, Angular, Playwright ni une CI verte. Ne déployer ni activer de paiement live avant validation sur la nouvelle tête de branche.**

## Ce qui change

- Les propriétés SasPay sont maintenant chargées dans tous les profils Spring, y compris `prod`. La lecture de `.env` reste réservée au profil non-`prod` ; injecter les vrais secrets via l'environnement du serveur/conteneur.
- Une clé `sk_live_` est acceptée uniquement si le profil `prod` est actif ET que `SASPAY_LIVE_PILOT_ENABLED=true`.
- Le pilote ne peut créer de nouveaux devis que pour **une seule organisation** déclarée dans `SASPAY_LIVE_PILOT_ORGANIZATION_ID`. L'initiation vérifie à nouveau cette organisation avant toute requête de paiement.
- Le **montant total débité**, frais pay-in inclus, est plafonné côté serveur à `SASPAY_LIVE_PILOT_MAX_CHARGED_XOF` (2 000 XOF par défaut, plafond de configuration absolu 5 000 XOF).
- Aucun payout SasPay réel ni remboursement API réel n'est implémenté. Les opérations marketplace restent purement simulées. L'activation d'un petit montant ne vaut pas autorisation d'encaisser pour des vendeuses tierces.

## Identifier le marchand, sans payer

Depuis la racine du dépôt, avec une clé SasPay live uniquement sur votre PC (ne jamais la communiquer à ChatGPT) :

```powershell
python tools/saspay_diagnostics.py --env-file Backend/.env
```

Le diagnostic appelle seulement `GET /merchant-balances/` et `GET /pricing/my-rates/` de `https://api.saspay.me/api/v1` ; aucun POST, payout ou débit. Il extrait `merchant` (UUID du marchand) des soldes et liste les réseaux SN/XOF actifs sans afficher ni clés, ni soldes, ni données clientes. Si la réponse ne contient pas de marchand ou en contient plusieurs, faire confirmer le bon UUID par SasPay. Ne pas copier un identifiant d'exemple de la documentation.

Sources officielles :
- https://docs.saspay.me/api-reference/wallet/balances-list
- https://docs.saspay.me/api-reference/reference/my-rates
- https://docs.saspay.me/api-reference/introduction

## Variables cibles du backend

Le bloc suivant est un **exemple de configuration cible**, pas une autorisation de mise en service :

```dotenv
SPRING_PROFILES_ACTIVE=prod
SASPAY_ENABLED=true
SASPAY_API_KEY=sk_live_REMPLACER_EN_SECRET
SASPAY_WEBHOOK_SECRET=SECRET_WEBHOOK_REEL
SASPAY_MERCHANT_ID=UUID_REEL_DU_MARCHAND
SASPAY_CHECKOUT_HOSTS=pay.saspay.me
SASPAY_LIVE_PILOT_ENABLED=true
SASPAY_LIVE_PILOT_ORGANIZATION_ID=UUID_DE_LA_BOUTIQUE_TEST
SASPAY_LIVE_PILOT_MAX_CHARGED_XOF=2000

BICTORYS_ENABLED=false
GROUPIA_BICTORYS_PAYOUTS_ENABLED=false
GROUPIA_MERCHANT_PAYOUTS_ENABLED=false
GROUPIA_MARKETPLACE_SIMULATION_ENABLED=false
GROUPIA_WEBHOOK_RETRY_ENABLED=true
GROUPIA_EVENT_RECOVERY_ENABLED=true

GROUPIA_DIRECT_CHECKOUT_ENABLED=true
GROUPIA_DIRECT_PAYMENT_AUTO_PREPARE_ENABLED=true
GROUPIA_DIRECT_PAYMENT_INITIATION_ENABLED=true
GROUPIA_SUBSCRIPTIONS_ENABLED=true
GROUPIA_SUBSCRIPTION_ENFORCEMENT_ENABLED=false
```

`SASPAY_LIVE_PILOT_ORGANIZATION_ID` est l'UUID **Groupia** de la boutique test, différent du `SASPAY_MERCHANT_ID`. Ne pas le remplacer par l'UUID SasPay. Récupérer l'ID exact de l'organisation via une vue/API Groupia autorisée ; ne pas inventer une valeur.

`GROUPIA_DIRECT_*` ne doit être activé qu'après recette du checkout et du contrôle serveur des montants, des adresses et de l'éligibilité. Pour un devis SasPay, les coordonnées du payeur doivent comporter prénom, nom, téléphone `+221...` et email. Les commandes/groupages utilisent leurs propres parcours et capacités.

Pour les paramètres en cours, exécuter hors-ligne depuis un shell ayant chargé les variables :
```powershell
python tools/check_saspay_config.py
```
Ce validateur ne contacte jamais le fournisseur et ne prouve donc pas qu'un paiement peut aboutir.

**Attention :** le prix de l'abonnement vendeur par défaut est 10 000 FCFA ; avec un plafond pilote de 2 000 FCFA frais inclus, il ne peut pas être payé via SasPay live. Cela est volontaire : ne pas réduire les factures d'abonnement en production simplement pour contourner le plafond. Tester d'abord une commande propre de faible montant dans la boutique pilote.

## Tunnel Cloudflared temporaire

Créer le webhook dans le dashboard SasPay avec l'URL **publique du backend**, pas celle de l'interface Angular :
`https://ADRESSE_CLOUDFLARED_BACKEND/api/payment/webhooks/saspay`

Souscrire aux événements `transaction.created`, `transaction.success`, `transaction.failed`, en respectant le secret de signature `SASPAY_WEBHOOK_SECRET`. Vérifier avec un événement test et les journaux de livraison SasPay. Les adresses `trycloudflare.com` peuvent changer à chaque redémarrage du tunnel ; mettre immédiatement à jour l'URL du webhook ou, idéalement, utiliser un domaine stable avant toute transaction live. Ne jamais journaliser le corps brut avec des données sensibles.

- https://docs.saspay.me/api-reference/webhooks

## Points de blocage avant tout débit réel

1. Obtenir de SasPay les règles de tarification/arrondi XOF et une garantie de non-dépassement du devis accepté, ou une solution contractuelle permettant de garantir le total effectivement débité. L'API actuelle ne garantit pas le verrouillage du montant du devis : en cas d'écart constaté après débit, Groupia passe en revue au lieu de confirmer, mais l'argent peut déjà avoir été retiré.
2. Vérifier que le compte est autorisé pour le pay-in et pour **la nature exacte du paiement pilote**, idéalement un achat de test sur votre propre boutique (pas une collecte pour une tierce vendeuse). KYC validé ne suffit pas à autoriser une plateforme marketplace.
3. Exécuter les tests complets Java/Angular/E2E et la CI sur ces nouveaux commits, vérifier migrations et sauvegardes, puis tester la disponibilité du backend et le webhook HTTPS.
4. Contrôler le parcours : devis → frais et total visibles → consentement → même clé d'idempotence → push/redirection réelle → webhook signé → GET verify → montant/devise/marchand exacts → statut payé. En cas d'inconnu, NE PAS retenter manuellement avec une nouvelle clé ni délivrer la commande.
5. Documenter la procédure d'incident et de remboursement, le détenteur des fonds, l'historique financier et l'interruption immédiate du pilote (désactiver `SASPAY_ENABLED` coupe les nouvelles initiations, mais vérifier comment continuer à réconcilier les transactions en cours avant de révoquer la clé).
6. Les payouts SasPay réels nécessitent un développement distinct, une autorisation fournisseur et une IP publique sortante whitelistée. Ne pas activer les reversements juste parce qu'un paiement test réussi.

Les scénarios frontend existants n'affichent SasPay que si le serveur annonce le fournisseur disponible. Sur une commande directe, le paiement apparaît sur le **lien privé de suivi** une fois la demande de paiement préparée ; sans quote admissible ou sans email du payeur, il n'y a pas d'option exploitable. L'interface Angular n'accepte pour l'instant que les redirections hébergées sous `pay.saspay.me`.

**Statut : code pilote préparé dans PR #86, non validé en exécution et non déployé.**

## Diagnostic précis lorsque `payment-quotes` répond `[]`

Depuis la racine du projet, avec la clé seulement dans `Backend/.env` :

```powershell
python tools/saspay_diagnostics.py --env-file Backend/.env --organization-id b95a0515-1bcf-4740-8417-c594d02f211e --amount-xof 1100
```

- `Boutique Groupia autorisée pour le pilote : NON` signifie que `SASPAY_LIVE_PILOT_ORGANIZATION_ID` ne correspond pas à la boutique Groupia. L'UUID marchand SasPay n'est pas cet identifiant.
- L'outil liste les causes d'exclusion pour les réseaux SN/XOF (OTP requis, tarif incomplet, mode ADD_ON indisponible, frais XOF fractionnaires, total dépassant le plafond, etc.).
- Cet outil ne contacte que les endpoints GET documentés, n'initie aucun transfert, ne montre ni clé ni téléphone/email. Son analyse est indicative : l'application Java fait autorité.
- Le backend renvoie désormais `409 SASPAY_PAYER_REQUIRED` si le contact client est incomplet, `409 SASPAY_PILOT_ORGANIZATION_NOT_ALLOWED` si la boutique est incorrecte et `409 SASPAY_NO_COMPATIBLE_QUOTE` s'il n'existe aucun devis. Le frontend présente un message adapté.

Après avoir corrigé l'UUID de la boutique dans l'environnement, **redémarrer complètement le processus Spring**. Ne pas changer le `SASPAY_MERCHANT_ID`, ne pas supprimer des restrictions d'arrondi, ne pas tester un véritable débit pour rechercher le problème.

**Les nouveaux correctifs de diagnostic n'ont pas subi la validation Maven/Angular/E2E complète ni une CI verte.** Les changements sont dans la PR brouillon uniquement.
