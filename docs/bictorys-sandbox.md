# Bictorys sandbox — parcours manuel et contrat

État : adaptateur sandbox uniquement. Les tests automatisés utilisent un serveur HTTP local et PostgreSQL/Testcontainers ; aucun paiement Bictorys distant n'a été exécuté pour cette livraison. Aucun payout, remboursement, capture carte ou frontend n'est fourni.

## Configuration

| Variable | Valeur / règle |
| --- | --- |
| BICTORYS_ENABLED | false par défaut ; true pour activer |
| BICTORYS_API_URL | https://api.test.bictorys.com ; seule origine API autorisée |
| BICTORYS_API_KEY | clé sandbox `test_public-…` du dashboard, conservée côté serveur |
| BICTORYS_WEBHOOK_SECRET | secret du webhook configuré dans le dashboard |

Configuration typée : BictorysProperties, préfixe `groupia.payment.bictorys`. Connexion : 3 s ; réponse : 10 s. Les propriétés `connect-timeout` et `read-timeout` sont configurables, bornées à 30 s et 60 s. Aucun secret de repli et aucune private key. Désactivé, le provider n'est pas enregistré et ne demande pas de secrets. Actif avec configuration incomplète ou origine de production, le démarrage échoue.

Maven Surefire force `groupia.payment.bictorys.enabled=false` avant l'évaluation des conditions Spring : une activation dans le `.env` local ne doit jamais activer le vrai client dans les tests. Les tests de configuration activent explicitement leur contexte isolé ; les tests d'intégration injectent l'adaptateur avec un serveur HTTP local. Pour lancer ces derniers dans un IDE, définir aussi cette propriété système à false.

## Contrat retenu

- Initiation : `POST /pay/v1/charges`, `X-Api-Key`, JSON, aucun `payment_type`. Montant/devise issus de PaymentRequest ; pays SN ; paymentReference = OrderNumber ; merchantReference = référence interne inchangée ; customerObject = snapshot de contact de la commande. Le navigateur fournit uniquement le nom du provider.
- 201 : `transactionId`, `link`, éventuellement merchantReference/statut. 202 : `chargeId`, `link`, éventuellement `opToken`. Les fixtures suivent les schémas ConfirmationLinkObject/CheckoutLinkObject de la référence API. `chargeId` est distinct de `transactionId`.
- Le reçu privé expose `checkoutUrl` lorsque la tentative est PENDING et sans anomalie. URL HTTPS Bictorys validée, persistée pour le rejeu d'initiation. Elle peut contenir un jeton d'opération : ne pas la journaliser ni la partager. Les redirects ne sont pas configurés ; `redirectUrl` seul n'est ni un lien de checkout fiable ni une preuve de règlement.
- Une réponse d'initiation est un accusé de réception, jamais une confirmation financière. Les champs attendus utilisés pour représenter cet accusé ne servent pas de preuve de débit.
- Vérification : `GET /pay/v1/transactions/{transactionId}/status`. Si cette réponse ne contient que le statut, lecture complémentaire documentée `GET /pay/v1/transactions/{transactionId}` pour obtenir les preuves financières. Les deux doivent être cohérentes. Aucun appel `GET /pay/v1/charges/{id}` ; aucun polling permanent.
- Réponse incomplète/contradictoire, HTTP 4xx/5xx, HTML, coupure ou timeout : état incertain, jamais FAILED déduit d'un transport. Aucun retry automatique du POST d'initiation. Claim commité avant appel, résultat dans une transaction distincte.

La documentation présente des exemples parfois divergents (notamment redirectUrl/link et statut seul/détails). Les contrats testés sont précisés dans les fixtures. Le premier essai sandbox doit confirmer les réponses réellement servies au compte ; si elles divergent, conserver l'anomalie, anonymiser un exemple et adapter explicitement le contrat, sans assouplir les vérifications financières.

## Statuts et webhooks

| Bictorys | Traitement Groupia |
| --- | --- |
| success, succeeded ; réussi dans l'exemple historique | Candidat SUCCEEDED, vérification serveur et contrôles financiers obligatoires |
| pending, processing | PENDING |
| failed | FAILED ; ne régresse pas un succès |
| cancelled | CANCELLED ; ne régresse pas un succès |
| authorized | Observation PENDING avec AUTHORIZATION_NOT_CAPTURED ; état financier conservé, revue |
| reversed | PROVIDER_REVERSAL, état financier conservé ; SUCCEEDED reste SUCCEEDED |
| inconnu | PROVIDER_STATUS_UNKNOWN, aucune confirmation |

**Décision sur authorized :** le guide recommande succeeded tout en décrivant une pré-capture carte. Cette tranche ne capture pas les cartes et ne peut assimiler une autorisation à un encaissement mobile money. Elle choisit donc explicitement une revue conservatrice, contrairement à cette recommandation générale. Un succès explicite et entièrement vérifié peut ensuite confirmer. Cette différence doit être validée avec Bictorys avant d'élargir le parcours aux cartes.

Route : `POST /api/payment/webhooks/bictorys`. `X-Secret-Key` comparé avec MessageDigest.isEqual sur les octets UTF-8, avant parsing. Header absent/incorrect : 401 PAYMENT_AUTHENTICITY_FAILED ; seule l'empreinte de réception est conservée, aucun effet financier. Une réception authentique persistée répond 202 : cela ne signifie pas que le paiement est confirmé. Secrets, payload brut et customerObject ne sont pas stockés dans les réceptions ni journalisés.

Forme racine : id, merchantReference, paymentReference, amount numérique, currency, status, timestamp ISO facultatif ; type payment lorsqu'il est présent. Variante historique : event=charge.successful et objet data, avec id numérique ou texte, amount, currency, status et reference. Le type historique mobile_money est accepté. data.reference est une référence lisible, jamais un remplacement de merchantReference. L'exemple historique dépourvu de merchantReference est conservé en UNKNOWN_REFERENCE, sans confirmation automatique. Un objet data arbitraire est rejeté.

`id` est l'identifiant de transaction, `event` un type d'événement : aucun des deux n'est un identifiant unique de livraison. providerEventId reste null pour ces contrats. Déduplication par empreinte puis idempotence financière existante. Même secret valide : montant, devise, référence, cible et transaction doivent correspondre ; les incohérences sont enregistrées pour investigation.

Un reversal reste visible même après un succès/échec tardif ou une vérification indisponible. Aucun remboursement ni changement de commande automatique. Les reprises et limites à cinq essais des réceptions restent celles du [runbook financier](payment-operations.md).

## Premier paiement sandbox de bout en bout

Prérequis : Java 21, Docker Desktop actif, cloudflared installé, compte Bictorys sandbox avec checkout Wave/Orange Money activé. Utiliser les identifiants et moyens de test fournis par Bictorys ; ne pas supposer qu'un numéro personnel déclenche un paiement simulé. La disponibilité et les scénarios dépendent du compte sandbox.

### 1. Lancer Groupia

Dans PowerShell à la racine du dépôt, avec la configuration JWT locale existante :

```powershell
$env:BICTORYS_ENABLED = 'true'
$env:BICTORYS_API_URL = 'https://api.test.bictorys.com'
function Read-LocalSecret([string]$label) {
    $secure = Read-Host $label -AsSecureString
    return [System.Net.NetworkCredential]::new('', $secure).Password
}
$env:BICTORYS_API_KEY = Read-LocalSecret 'Cle API sandbox test_public-...'
$env:BICTORYS_WEBHOOK_SECRET = Read-LocalSecret 'Secret du webhook sandbox'
docker compose up -d postgres
.\mvnw.cmd spring-boot:run
```

Ne pas partager ces variables, ni démarrer avec un logger HTTP de corps/headers. Liquibase applique la migration additive 009 ; aucune réinitialisation de base requise.

### 2. Connecter le webhook

Dans un deuxième terminal :

```powershell
cloudflared tunnel --url http://localhost:8080
```

Copier l'origine HTTPS `https://<nom-aleatoire>.trycloudflare.com` affichée. Dans le dashboard Bictorys sandbox, configurer l'URL `https://<nom-aleatoire>.trycloudflare.com/api/payment/webhooks/bictorys` et le même secret que BICTORYS_WEBHOOK_SECRET. Garder les deux processus actifs. Chaque nouveau Quick Tunnel peut changer l'URL : mettre alors le dashboard à jour avant de payer. Il ne faut désactiver ni JWT ni contrôle par jeton sur les autres routes. Quick Tunnel est temporaire, sans garantie de disponibilité. Si une configuration cloudflared existante empêche Quick Tunnel, suivre sa documentation ; ne pas effacer une configuration de tunnel existante.

### 3. Créer une commande de test

Dans un troisième terminal PowerShell, se connecter avec un organisateur déjà inscrit. S'il n'existe pas encore, envoyer une seule fois le même corps à `/api/auth/register` avant login (voir [inscription](identity-organization-api.md)). Le mot de passe doit respecter la politique du projet.

```powershell
$base = 'http://localhost:8080'
$phone = Read-Host 'Telephone organisateur'
$securePassword = Read-Host 'Mot de passe organisateur' -AsSecureString
$loginBody = @{
    phoneNumber = $phone
    password = [System.Net.NetworkCredential]::new('', $securePassword).Password
} | ConvertTo-Json
$session = Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json' -Body $loginBody
$auth = @{ Authorization = "Bearer $($session.accessToken)" }
$loginBody = $null
function Send-GroupiaJson([string]$path, $body, [hashtable]$headers) {
    Invoke-RestMethod -Method Post -Uri "$base$path" -Headers $headers -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 10)
}
$org = Send-GroupiaJson '/api/organizations' @{name='Essai sandbox Bictorys'} $auth
$product = Send-GroupiaJson "/api/organizations/$($org.id)/products" @{name='Article sandbox'} $auth
$campaignPath = "/api/organizations/$($org.id)/grouping-campaigns"
$campaign = Send-GroupiaJson $campaignPath @{
    title='Campagne sandbox'
    shippingTerms=@{transportMode='AIR';shippingPricingMethod='FIXED';shippingCollectionMode='AFTER_FINAL_ASSESSMENT';shippingRate=1000}
} $auth
$campaign = Send-GroupiaJson "$campaignPath/$($campaign.id)/offers" @{
    campaignVersion=$campaign.version;productVariantId=$product.variants[0].id;unitPrice=1000
} $auth
$campaign = Send-GroupiaJson "$campaignPath/$($campaign.id)/open" @{version=$campaign.version} $auth
$checkoutKey = [Guid]::NewGuid().ToString()
$buyerPhone = Read-Host 'Numero acheteur autorise pour le sandbox'
$order = Send-GroupiaJson "/api/public/groupings/$($campaign.publicIdentifier)/checkout" @{
    contact=@{firstName='Essai';lastName='Sandbox';phone=$buyerPhone}
    lines=@(@{offerId=$campaign.offers[0].id;quantity=2;expectedUnitPrice=1000;expectedPriceRevision=0})
} @{'Idempotency-Key'=$checkoutKey}
```

Conserver `$order.trackingToken` confidentiellement dans cette session. Il est remis une seule fois : ne pas répéter le checkout avec une nouvelle clé si la réponse a été perdue. Avec les frais locaux par défaut, le montant initial est 2300 XOF ; le tarif de transport final sera collecté ultérieurement, hors de cette tranche. Les montants effectifs viennent toujours de la réponse serveur.

### 4. Initier et ouvrir le checkout hébergé

```powershell
$paymentKey = [Guid]::NewGuid().ToString()
$privateHeaders = @{'X-Order-Tracking-Token'=$order.trackingToken;'Idempotency-Key'=$paymentKey}
$payment = Send-GroupiaJson "/api/public/orders/$($order.orderNumber)/payments" @{provider='bictorys'} $privateHeaders
if ($payment.reconciliationRequired -or [string]::IsNullOrWhiteSpace($payment.checkoutUrl)) {
    throw 'Paiement a investiguer : ne pas creer une nouvelle tentative.'
}
Start-Process $payment.checkoutUrl
```

Choisir Wave ou Orange Money sur la page Bictorys et suivre son scénario sandbox. Ne pas fabriquer un webhook de succès pour valider ce parcours réel. Aucun callback navigateur n'est une preuve de paiement. Si une réponse d'initiation a été perdue, rejouer avec **la même** `$paymentKey` et le même numéro/jeton ; aucun second POST Bictorys ne sera envoyé. Après confirmation ou expiration, cette route peut refuser le rejeu : utiliser le suivi.

### 5. Constater le résultat

Après réception du webhook et traitement asynchrone de l'événement :

```powershell
$tracking = Invoke-RestMethod -Uri "$base/api/public/orders/track" -Headers @{'X-Order-Tracking-Token'=$order.trackingToken}
$tracking | Select-Object orderNumber,status,initialAmountDue,currency
$requests = Invoke-RestMethod -Uri "$base/api/organizations/$($org.id)/payment-requests" -Headers $auth
$financial = Invoke-RestMethod -Uri "$base/api/organizations/$($org.id)/payment-requests/$($requests.items[0].id)" -Headers $auth
$financial
```

Attendu : Order CONFIRMED ; demande CLOSED avec settledPaymentId ; tentative SUCCEEDED, montant/devise cohérents, aucune anomalie. Un 202 de webhook seul ne suffit pas. Un événement incomplet, reversed ou authorized peut légitimement laisser la commande en attente et imposer une revue. Effectuer le parcours avant paymentDeadline (30 minutes par défaut) ; un succès reçu après cette limite reste LATE_SUCCESS sans réactivation.

### 6. Diagnostic et vérification ponctuelle

Un opérateur habilité peut appeler la méthode interne PaymentService.reconcile(paymentId) depuis un outil d'exploitation contrôlé ; elle effectue uniquement les GET documentés, hors transaction, et repasse par les invariants financiers. Aucun endpoint public de force-success ou de réconciliation administrative n'a été ajouté. Les requêtes SQL de diagnostic en lecture seule sont dans le [runbook](payment-operations.md).

Si aucun transactionId n'est connu (réponse 202 avec chargeId seul, timeout avant réponse), aucun GET de statut n'est inventé à partir de la charge ou de merchantReference. Attendre la livraison/redélivrance du webhook authentique et investiguer dans le dashboard. La recherche API par merchantReference promise comme souhait général dans ADR-0010 n'est pas documentée pour ce provider ; cette limite est explicitée par ADR-0012. Ne jamais conclure FAILED ni renvoyer une charge pour contourner cette incertitude.

## Sources vérifiées le 22 septembre 2026

- [Guide d'intégration Bictorys](https://docs.bictorys.com/docs/integration) : environnement, statuts, route status et webhook.
- [Référence createCharge et schémas 201/202](https://docs.bictorys.com/reference/createcharge).
- [Détails d'une transaction](https://docs.bictorys.com/reference/gettransactiondetails).
- [Exemple historique sous data](https://docs.bictorys.com/docs/status-responses-and-their-meanings).
- [Validation des webhooks](https://docs.bictorys.com/docs/how-to-validate-webhooks).
- [Cloudflare Quick Tunnels](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/).

## Fichiers de cette tranche

Adaptateur ajouté :

```text
src/main/java/sn/groupia/payment/infrastructure/provider/bictorys/
  BictorysPaymentProvider.java
  BictorysClient.java
  BictorysProperties.java
  BictorysConfiguration.java
  BictorysChargeRequest.java
  BictorysWebhookParser.java
  BictorysStatusMapper.java
  BictorysException.java
```

Autres ajouts : PaymentCheckoutContext dans payment/api/dto, ProviderMetadata dans
payment/domain/service, migration 009-provider-checkout.sql, ADR-0012 et ce guide.
Payment/ProviderInstruction/ProviderObservation, PaymentService/PaymentTransactions,
ProviderRegistry, PaymentOperations/PaymentAttemptView et le passage de contexte
dans OrderPaymentGate/OrderPayments ont été adaptés pour le checkout hébergé.

Tests ajoutés : BictorysContractTests, BictorysIntegrationTests, BictorysHttpStub,
BictorysTestServerConfiguration, cinq fixtures JSON et leur README sourcé.
Configuration : application.properties, inclusion de 009 dans le changelog maître,
isolation Surefire dans pom.xml et TestcontainersConfiguration. Aucune dépendance
ajoutée ou version modifiée ; les bibliothèques Spring HTTP/Jackson existantes
suffisent. Documents architecture, dépendances, API, exploitation et validation mis
à jour. Aucun secret ou fichier .env modifié.
