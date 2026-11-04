# Pilote Bictorys distant — ventes directes (mode sandbox uniquement)

## Périmètre et décision de lancement

Ce protocole prépare une **validation manuelle sur le véritable environnement
sandbox Bictorys**, contrairement aux tests automatisés du dépôt qui
utilisent un serveur HTTP local synthétique.

Le passage des tests CI ne prouve ni l'accès aux identifiants du prestataire,
ni la réception d'un webhook réel, ni la bonne orientation financière
des commissions, remboursements et reversements. Ne pas autoriser
les paiements de clientes réelles avant une revue opérationnelle.

La valeur par défaut reste **false** pour tous les flags d'encaissement,
y compris en production. Les essais doivent se dérouler sur une
**instance locale isolée**, jamais sur la base de production.

## 1. Préparer un environnement local

Conserver les secrets uniquement dans le gestionnaire d'identifiants local
ou dans la session PowerShell, **jamais** dans Git, un ticket, un log ou
une capture d'écran. Ne pas recopier les exemples ci-dessous dans un
fichier suivi par Git.

Variables explicites pour une instance locale **dédiée au test** :

| Variable | Valeur pour test local | Valeur par défaut |
| --- | --- | --- |
| `GROUPIA_DIRECT_CHECKOUT_ENABLED` | `true` | `false` |
| `GROUPIA_DIRECT_PAYMENT_AUTO_PREPARE_ENABLED` | `true` | `false` |
| `GROUPIA_DIRECT_PAYMENT_INITIATION_ENABLED` | `true` | `false` |
| `BICTORYS_ENABLED` | `true` | `false` |
| `BICTORYS_API_URL` | `https://api.test.bictorys.com` | sandbox uniquement |
| `BICTORYS_API_KEY` | clé **sandbox** `test_public-...` | vide |
| `BICTORYS_WEBHOOK_SECRET` | secret webhook sandbox | vide |
| `GROUPIA_BICTORYS_PAYOUTS_ENABLED` | `false` | `false` |
| `GROUPIA_MERCHANT_PAYOUTS_ENABLED` | `false` | `false` |

L'adaptateur bloque toute origine autre que l'API HTTPS de test.
Pour alimenter une session PowerShell sans afficher les secrets :

```powershell
$env:GROUPIA_DIRECT_CHECKOUT_ENABLED = 'true'
$env:GROUPIA_DIRECT_PAYMENT_AUTO_PREPARE_ENABLED = 'true'
$env:GROUPIA_DIRECT_PAYMENT_INITIATION_ENABLED = 'true'
$env:BICTORYS_ENABLED = 'true'
$env:BICTORYS_API_URL = 'https://api.test.bictorys.com'
$env:GROUPIA_BICTORYS_PAYOUTS_ENABLED = 'false'
$env:GROUPIA_MERCHANT_PAYOUTS_ENABLED = 'false'
function Read-Secret([string]$question) {
    $v = Read-Host $question -AsSecureString
    [System.Net.NetworkCredential]::new('', $v).Password
}
$env:BICTORYS_API_KEY = Read-Secret 'Clé sandbox Bictorys'
$env:BICTORYS_WEBHOOK_SECRET = Read-Secret 'Secret webhook sandbox'
```

Démarrer le backend avec la configuration JWT/PostgreSQL locale déjà
fonctionnelle, puis le frontend Angular. Le backend écoute généralement
sur `http://127.0.0.1:8080`.

## 2. Webhook HTTPS

En sandbox Bictorys, configurer le webhook de **cette instance de test** sur
`https://<tunnel-test>/api/payment/webhooks/bictorys`, en utilisant
le secret configuré localement. Un tunnel Cloudflare HTTPS fonctionne
si son URL reste stable pendant le scénario ; en cas de changement
d'URL, mettre à jour Bictorys avant tout essai.

Le webhook doit être envoyé **par Bictorys**, pas fabriqué
par le navigateur. Ne pas désactiver JWT, contrôles de session,
isolation des organisations ou protection des jetons de commandes.

## 3. Essai unique sur une commande fictive

1. Créer dans l'interface Groupia une boutique d'essai et publier un
   produit fictif. Privilégier le **retrait** pour le premier essai,
   afin de tester le montant avant les scénarios géographiques.
2. Passer une commande directe depuis la vitrine avec un **contact
   de test autorisé par Bictorys** et un total connu. Conserver le
   lien privé de suivi sans le diffuser.
3. Avant de payer, vérifier le statut via le navigateur ou le script
   en lecture seule, exécuté **depuis la racine du dépôt** :

   ```powershell
   python tools/direct_bictorys_sandbox_preflight.py --base-url http://127.0.0.1:8080
   ```

   Le script demande le jeton en saisie masquée ; il ne l'affiche pas,
   ne l'envoie jamais dans une URL et **n'appelle jamais l'API
   d'initiation de paiement**. L'état attendu est `PREPARED` et
   `Paiement proposé : oui` si la commande a été préparée.
4. Ouvrir **une seule fois** le paiement depuis le suivi client Groupia.
   Confirmer la présence d'une URL Bictorys de sandbox.
   Un retour `PENDING` et une URL de paiement ne constituent pas
   une preuve de règlement.
5. Avant la validation sandbox, le script peut afficher
   `PAYMENT_IN_PROGRESS` et `Paiement proposé : non`.
   La gérante ne doit pas pouvoir annuler, préparer ou remettre la commande.
6. Effectuer le scénario de paiement de test **fourni par Bictorys**
   (Wave ou Orange Money uniquement si l'environnement sandbox
   l'autorise). Aucun numéro privé ou moyen de paiement réel ne doit
   être employé sans vérification du caractère simulé.
7. Après webhook authentifié et traitement asynchrone, relancer le
   script. Accepter seulement `SETTLEMENT_VERIFIED`, un
   état de commande confirmé correspondant et un montant XOF identique.
   Vérifier dans l'espace gérante : une seule preuve de règlement,
   aucun risque financier, possibilité de préparer la commande **après**
   la confirmation, mais aucune remise effective automatique.

## 4. Scénarios négatifs obligatoires

- Retour navigateur ou rechargement : ne crée pas un second paiement.
- Même clé d'idempotence : aucun deuxième POST fournisseur.
- Jeton révoqué : une nouvelle tentative doit être refusée.
- Annulation de la commande avant initiation : aucun débit possible.
- Webhook absent/retardé/dupliqué : pas de faux « payé » ;
  les traces financières conservées permettent la reprise ou la revue.
- Incohérence montant, devise, référence ou état : statut
  `REVIEW_REQUIRED`, sans préparation ni remise.
- Expiration et interruption réseau : ne jamais forcer le
  statut à `FAILED` ou relancer un débit au hasard.

Les tests automatisés couvrent déjà plusieurs de ces risques avec
un faux fournisseur local, mais **ne remplacent pas le test distant**.

## 5. Éléments à consigner sans secrets

Consigner : horodatage, environnement *sandbox*, référence de
commande **fictive**, montant attendu XOF, états observés,
code de réponse HTTP et verdict. Ne jamais copier de jeton de
récupération, d'URL de checkout privée, de clé API, de secret webhook,
de numéro personnel ou de payload brut dans la PR.

Arrêter l'essai si les champs de réponse Bictorys divergent
du contrat prévu ; demander une validation du prestataire avant
d'adapter le parseur. Un `200` webhook ne prouve pas à lui seul
le règlement.

## 6. Retour à l'état sûr

Après les essais, arrêter le tunnel et le backend local et remettre
les trois flags directs ainsi que `BICTORYS_ENABLED` à `false`.
Laisser tous les flags de payout à `false`. Révoquer les secrets
temporaires éventuellement exposés.

**Aucun encaissement réel, split, remboursement ou reversement
marchand n'est activé par cette PR.**
