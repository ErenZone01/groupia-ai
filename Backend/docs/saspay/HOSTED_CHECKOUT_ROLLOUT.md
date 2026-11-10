# Groupia — passage intégral des payins SasPay au checkout hébergé

État au 10 novembre 2026 : **code publié dans la PR #86, en brouillon ;
tests et pilote financier LIVE non validés**. Aucune opération financière
réelle n'a été exécutée lors de ce changement.

## Portée

Pour les **nouveaux paiements SasPay** : vente directe, commande initiale
de groupage, solde de transport et facture d'abonnement mensuel.

Le backend Groupia crée et conserve l'obligation financière immutable.
L'application ne calcule plus de frais SasPay par réseau : le client
choisit le moyen de paiement et voit les frais sur la page hébergée.
Le mode `ADD_ON` doit rester configuré sur le compte marchand SasPay
(comme confirmé par le propriétaire du compte) et être *revérifié*
dans les preuves financières.

**Anciennes transactions Softpay** : conservation des lignes et du code
de vérification. Aucune conversion d'ancienne transaction PENDING,
ni correction manuelle de `provider_transaction_id`.

## Architecture

1. Backend autorise la commande, son montant, son organisation,
   son état, son token ou sa session et les droits du payeur.
2. `PaymentTransactions.claim` enregistre une unique intention
   `payments` avec clé interne de reprise et statut CREATED.
   Nouveau SasPay ne demande pas de `feeQuoteId`.
3. En dehors de toute transaction SQL, le backend appelle
   `POST /checkout-sessions/` avec `amount` XOF en chaîne
   décimale, `country:SN`, `fee_charge_mode:ADD_ON`, nom,
   e-mail du payeur et `metadata` (UUID tentative Groupia +
   référence interne). **Pas d'Idempotency-Key fournisseur : cet
   endpoint ne le supporte pas.**
4. Le backend vérifie immédiatement le marchand, montant, devise,
   UUID de session et hôte HTTPS exact du `checkout_url`.
   La session est stockée comme `provider_charge_id` (réservé
   pour les sessions hébergées) et la transaction financière
   `provider_transaction_id` reste vide jusqu'à confirmation.
5. Le client est envoyé en même onglet vers
   `https://pay.saspay.me/checkout/...` ; la page SasPay
   gère le moyen de paiement et les frais. Groupia conserve
   uniquement en `sessionStorage` la destination privée locale
   du retour. SasPay ne reçoit jamais le token de suivi.
6. SasPay redirige **après succès** vers
   `GROUPIA_PUBLIC_BASE_URL/paiement/retour`, où la page restaure
   le suivi original de la commande. Le retour est un simple indice,
   **jamais une confirmation**.
7. Le poller serveur exécute au plus 25 tentatives par minute
   (GET uniquement) :
   - `GET /checkout-sessions/{id}/` : session, marchand, montant,
     devise, métadonnées d'identité de la tentative ;
   - `GET /checkout-sessions/{id}/status/` : PENDING ou PAID,
     ID de transaction financière et son statut ;
   - seulement si PAID/SUCCESS,
     `GET /payments/{transaction_id}/verify/` ;
   - valide UUIDs, identité marchand, type PAIEMENT, flux INBOUND,
     devise, prix, mode ADD_ON, frais exacts, total payé
     = prix + frais, net marchand = prix, plafond du pilote.
   Tout écart garde la commande **NON PAYÉE** et en revue.
8. Après une preuve financière SUCCESS valide, Groupia crée
   une seule allocation, l'historique de la commande et les
   encaissements deviennent cohérents via les événements existants.
   La migration `054` conserve `provider_collection_fee` et
   `provider_customer_charged` dans `payments` ; le ledger
   vendeur lit ces preuves, sans devis réseau.

Le webhook SasPay signé demeure un événement immuable et
authentifié. Son ID de transaction ne correspond pas forcément
à un ID de session. Le poller de session permet la réconciliation
sans réécrire ni apparier un webhook à partir du montant/numéro.

### Réponse perdue après création de session

SasPay ne prend pas en charge l'idempotence du POST checkout.
Une nouvelle tentative automatique ferait une deuxième session.
Groupia persiste **d'abord** la tentative, refuse la création
concurrente et peut chercher la session existante par sa paire
`groupia_payment_attempt_id` + `groupia_reference` via le
`GET /checkout-sessions/` paginé, lecture seule et bornée.
Si la liste est incomplète, si zéro ou plusieurs sessions
correspondent, l'opération reste bloquée pour revue. Aucun
nouveau POST n'est lancé sur la même intention.

## Config de déploiement pilote

Configurer les variables dans **le processus du backend** :
```text
SPRING_PROFILES_ACTIVE=prod
SASPAY_ENABLED=true
SASPAY_API_KEY=<SECRET_LIVE_CÔTÉ_SERVEUR>
SASPAY_WEBHOOK_SECRET=<SECRET_DU_WEBHOOK_LIVE>
SASPAY_MERCHANT_ID=<ID_MARCHAND>
SASPAY_CHECKOUT_HOSTS=pay.saspay.me
SASPAY_LIVE_PILOT_ENABLED=true
SASPAY_LIVE_PILOT_ORGANIZATION_ID=<UUID_BOUTIQUE_PILOTE>
SASPAY_LIVE_PILOT_MAX_CHARGED_XOF=2000
GROUPIA_PUBLIC_BASE_URL=https://<DOMAINE_HTTPS_DU_FRONTEND>
```
`GROUPIA_PUBLIC_BASE_URL` est l'URL **du frontend public** qui sert
`/paiement/retour`, pas celle du webhook backend. Il faut que
Angular/Cloudflare route réellement les chemins profonds
vers `index.html`. Ne jamais utiliser une URL localhost ou un
sous-domaine Cloudflare éphémère en production réelle.

Les anciens paramètres `SASPAY_ALLOW_FRACTIONAL_XOF_FEES` et
`/payment-quotes` sont réservés aux anciens diagnostics/régressions
et **ne sont plus nécessaires pour ouvrir le checkout hébergé**.

### Risque lié au plafond LIVE

Les frais réels ne sont connus qu'après le choix du réseau chez SasPay.
Le plafonnement `2000 XOF frais compris` est alors contrôlé
après la vérification du paiement et ne peut pas empêcher SasPay
d'avoir débité un montant supérieur. Toute divergence doit rester
**en revue financière**. Limiter manuellement le pilote à une petite
commande et ne pas augmenter les plafonds sans nouvelle analyse.

### Courriel des payeurs

SasPay demande `customer_email` et `customer_name`, mais
Groupia autorise une commande sans adresse email.
Les interfaces de commande directe et de groupage peuvent
demander un **e-mail de paiement** facultatif lors du checkout,
et la commande reste inchangée.
Si aucune adresse valide n'est disponible, le backend renvoie
`SASPAY_HOSTED_CONTACT_REQUIRED` avant d'appeler SasPay.
Pas de fausse adresse automatiquement générée.

## Tests obligatoires AVANT tout smoke LIVE

Depuis la racine, après avoir nettoyé les anciennes modifications
locales de Codex, récupérer la dernière version de PR #86 :
```powershell
git pull --ff-only origin saspay-payin-v1
python -m unittest discover -s tools -p "test_saspay_*.py"

cd Backend
$env:SPRING_PROFILES_ACTIVE = "test"
.\mvnw.cmd spotless:apply
.\mvnw.cmd spotless:check
.\mvnw.cmd "-Dtest=SaspayHostedContractTests,SaspayContractTests,SaspayIntegrationTests" test
```
**Les tests backend ont des TRUNCATE** : ils ne doivent être exécutés
qu'avec profil `test` et Testcontainers, JAMAIS avec la BDD prod.
Si les tests ci-dessus passent, exécuter les vérifications globales
du dépôt (migrations, tous les tests Spring, Modulith et secrets).

Frontend :
```powershell
cd ../Frontend
npm run lint
npm test
npm run build
```
Pour Playwright, reprendre les commandes/scripts `package.json`
actuels du projet et simuler le fournisseur localement : pas de
paiement réel dans les tests E2E.

**Résultats de ces tests dans ce lot : non exécutés par l'assistant**,
puisque les changements ont été appliqués via le connecteur GitHub
sans accès à la machine locale de développement.

## Ne pas mélanger avec la production financière

- Les anciennes tentatives Wave du 10/11 restent PENDING/UNKNOWN_REFERENCE
  et ne sont pas rattachées automatiquement aux commandes.
- Le checkout hébergé n'est pas une preuve d'encaissement chez la
  vendeuse : le `net_amount` vise le compte marchand SasPay configuré.
- Les payouts restent désactivés sans KYC/permission/frais/cible prouvés.
- L'abonnement mensuel nécessite une nouvelle session à chaque facture
  (pas d'API de prélèvement récurrent démontrée).
- Un retour `return_url`, une réponse 201 de session ou HTTP 202
  de webhook ne doit JAMAIS marquer la commande payée.
