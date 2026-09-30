# Livraison WhatsApp sortante

## Périmètre et état du raccordement

Le transport Meta reste **désactivé par défaut**. Ses capacités OTP et liens sont chacune désactivées par défaut et doivent être activées explicitement.
Aucun message réel n'est envoyé par les tests. Aucune migration, règle financière, règle de confirmation ou API Bictorys n'est modifiée.

**Mode actuel de test : UTILITY OTP.** Le compte Meta dispose d'un template UTILITY approuvé dont l'unique variable positionnelle BODY contient le code OTP.
Ce contrat est sélectionné explicitement, sans bouton copy-code et sans simulation de catégorie AUTHENTICATION.

**Mode cible futur : AUTHENTICATION OTP copy-code.** Il reste supporté et pourra être sélectionné par configuration/redémarrage après approbation, sans modifier customeraccess.

Ce choix ne modifie que le transport/template : génération SecureRandom, HMAC, TTL, tentatives maximales, quotas, cooldown, consommation unique et sessions PHONE_VERIFIED restent identiques.
Les templates de lien ne sont pas requis pour tester l'OTP seul.

Les templates de confirmation et renvoi devront porter un magic link. Un template qui ne contient que le numéro de commande ne suffit pas.
Un UTILITY neutre avec BODY `ORDER_NUMBER,MAGIC_URL` peut servir aux deux types, uniquement si ce contrat est approuvé.
Vérifier le code de langue exact dans Meta (français généralement `fr`, jamais la chaîne `french`).
Aucun nom réel de template ou credential n'est préconfiguré dans Git.

## Architecture

`OrderCommerciallyAccepted → CustomerAccess.confirmed → OrderAccessLink → LinkNotificationRequested(ORDER_CONFIRMED)`
puis listener Modulith `LinkNotifications → NotificationContentSource → DeliveryGateway`.

Le renvoi PHONE_VERIFIED tourne/révoque le lien et publie **ORDER_TRACKING_LINK**. Les deux événements restent distincts même si le template neutre est commun.
Les événements persistés ne contiennent que des identifiants et le type : téléphone, lien et OTP ne sont pas sérialisés dans le registre.
`LinkContent(phone, orderNumber, url)` est matérialisé à l'envoi et son `toString()` est masqué.

L'adapter et son client HTTP restent dans `notification.infrastructure.whatsapp`. Aucun module métier ne connaît Meta.
`DeliveryResult(provider, providerMessageId)` expose l'identifiant accepté, sans nouvelle persistence. Il n'est ni loggé ni retourné à Angular.
Le résultat ne modifie jamais l'état commercial d'une commande. Les webhooks delivered/read et leur stockage sont reportés.

Le nouvel endpoint de session `GET /api/public/customer-access/orders/{orderNumber}/capabilities` retourne `canResendTrackingLink`.
Il vérifie session, scope, appartenance par téléphone et archive. CONFIRMED et COMPLETED sont éligibles selon les règles existantes.
Angular consomme cette capability ; le POST revalide toujours l'autorisation, y compris si la capability est devenue obsolète.

## Configuration

Préfixe Spring : `groupia.notification.whatsapp`. Aucun secret n'a de valeur par défaut.

| Propriété | Variable d'environnement | Défaut |
|---|---|---|
| enabled | GROUPIA_WHATSAPP_ENABLED | false |
| otp-enabled | GROUPIA_WHATSAPP_OTP_ENABLED | false |
| links-enabled | GROUPIA_WHATSAPP_LINKS_ENABLED | false |
| graph-api-version | GROUPIA_WHATSAPP_GRAPH_API_VERSION | vide, obligatoire si activé |
| phone-number-id | GROUPIA_WHATSAPP_PHONE_NUMBER_ID | vide |
| access-token | GROUPIA_WHATSAPP_ACCESS_TOKEN | vide |
| connect-timeout | GROUPIA_WHATSAPP_CONNECT_TIMEOUT | 3s |
| read-timeout | GROUPIA_WHATSAPP_READ_TIMEOUT | 8s |
| templates.order-confirmed.name | GROUPIA_WHATSAPP_ORDER_CONFIRMED_TEMPLATE | vide |
| templates.order-confirmed.language | GROUPIA_WHATSAPP_ORDER_CONFIRMED_LANGUAGE | vide |
| templates.order-confirmed.header-parameters | GROUPIA_WHATSAPP_ORDER_CONFIRMED_HEADER_PARAMETERS | vide |
| templates.order-confirmed.body-parameters | GROUPIA_WHATSAPP_ORDER_CONFIRMED_BODY_PARAMETERS | vide |
| templates.order-confirmed.url-button-index | GROUPIA_WHATSAPP_ORDER_CONFIRMED_URL_BUTTON_INDEX | vide |
| templates.order-confirmed.url-prefix | GROUPIA_WHATSAPP_ORDER_CONFIRMED_URL_PREFIX | vide |
| templates.order-tracking-link.name | GROUPIA_WHATSAPP_TRACKING_LINK_TEMPLATE | vide |
| templates.order-tracking-link.language | GROUPIA_WHATSAPP_TRACKING_LINK_LANGUAGE | vide |
| templates.order-tracking-link.header-parameters | GROUPIA_WHATSAPP_TRACKING_LINK_HEADER_PARAMETERS | vide |
| templates.order-tracking-link.body-parameters | GROUPIA_WHATSAPP_TRACKING_LINK_BODY_PARAMETERS | vide |
| templates.order-tracking-link.url-button-index | GROUPIA_WHATSAPP_TRACKING_LINK_URL_BUTTON_INDEX | vide |
| templates.order-tracking-link.url-prefix | GROUPIA_WHATSAPP_TRACKING_LINK_URL_PREFIX | vide |
| templates.otp.mode | GROUPIA_WHATSAPP_OTP_MODE | vide, explicite si OTP activé |
| templates.otp.body-parameters | GROUPIA_WHATSAPP_OTP_BODY_PARAMETERS | vide ; OTP_CODE obligatoire en UTILITY |
| templates.otp.name | GROUPIA_WHATSAPP_OTP_TEMPLATE | vide |
| templates.otp.language | GROUPIA_WHATSAPP_OTP_LANGUAGE | vide |
| templates.otp.code-expiration-minutes | GROUPIA_WHATSAPP_OTP_EXPIRATION_MINUTES | vide |

Activation explicite : `enabled=true` exige toujours version Graph, Phone Number ID et access token.
- `otp-enabled=true` exige le nom/langue OTP et un `mode` explicite. Aucun mode par défaut.
- `links-enabled=true` exige les deux templates de confirmation/renvoi avec leurs mappings de lien.
- Les capacités désactivées n'exigent pas leur template ; `otp-enabled=true, links-enabled=false` démarre sans aucun template de lien.
- Les deux capacités ont pour défaut `false`. Les installations de la tranche précédente doivent maintenant renseigner ces deux switches et le mode OTP.
- Conflit avec `customer-access-local` : démarrage refusé si Meta est activé.

La version Graph est fournie par l'exploitant (`vN.N`, version supportée par son application Meta) et n'est pas fixée par le métier.
La validation locale ne consulte pas Meta : vérifier approbation, catégorie, langue et composants dans WhatsApp Manager avant l'activation.

## Mapping des composants

Seuls les composants **textuels positionnels** sont supportés dans cette tranche :
- HEADER optionnel : une source au maximum (`ORDER_NUMBER` ou `MAGIC_URL`).
- BODY : liste ordonnée de sources `ORDER_NUMBER` / `MAGIC_URL`.
- Bouton URL dynamique optionnel : index explicite 0 à 9 et préfixe HTTPS explicite finissant par `/s/`. Seul le suffixe du magic link est envoyé à Meta.
- Les composants absents de la configuration ne sont pas inventés.
- Headers média, variables nommées, boutons statiques additionnels avec paramètres et autres catégories nécessitent un contrat distinct.

Exemple **conditionnel**, uniquement si le template approuvé contient deux variables BODY numéro/lien :
```properties
groupia.notification.whatsapp.templates.order-confirmed.body-parameters=ORDER_NUMBER,MAGIC_URL
groupia.notification.whatsapp.templates.order-tracking-link.body-parameters=ORDER_NUMBER,MAGIC_URL
```
Exemple conditionnel BODY numéro + bouton URL en position 0 :
```properties
groupia.notification.whatsapp.templates.order-confirmed.body-parameters=ORDER_NUMBER
groupia.notification.whatsapp.templates.order-confirmed.url-button-index=0
groupia.notification.whatsapp.templates.order-confirmed.url-prefix=https://votre-domaine/s/
```
Le préfixe doit être celui du template approuvé et correspondre au `GROUPIA_PUBLIC_BASE_URL`.
Un décalage est refusé sans envoyer la requête.

Types logistiques : aucune émission automatique ajoutée. Des mappings supplémentaires peuvent être renseignés explicitement sous
`groupia.notification.whatsapp.templates.additional.ARRIVED.*` (ou `IN_TRANSIT`, `SUPPLIER_ORDERED`), avec le même schéma UTILITY.
Sans mapping, aucun fallback vers ORDER_CONFIRMED. Les types confirmation/renvoi ne peuvent pas être écrasés par additional.

Exemple de payload UTILITY pour le contrat BODY numéro/lien ci-dessus, **à utiliser uniquement après approbation de ce contrat** :
```json
{
  "messaging_product": "whatsapp",
  "recipient_type": "individual",
  "to": "<E164_sans_plus>",
  "type": "template",
  "template": {
    "name": "<nom_UTILITY_neutre_approuve>",
    "language": { "code": "<langue_approuvee>" },
    "components": [{
      "type": "body",
      "parameters": [
        { "type": "text", "text": "<numero_commande>" },
        { "type": "text", "text": "<magic_link_de_cet_evenement>" }
      ]
    }]
  }
}
```

## Liens désactivés

`links-enabled=false` laisse la création, rotation et révocation de OrderAccessLink fonctionner normalement.
L'adapter retourne `DeliveryResult("DISABLED", null)` pour les notifications de lien, avant de lire leurs templates ou de préparer une requête HTTP.
Ce résultat désigne une suppression volontaire, **pas une acceptation Meta**. Le listener termine normalement la publication Modulith : aucune boucle FAILED due aux templates manquants, aucune file d'attente différée ajoutée.

Les commandes de test créées pendant cette désactivation ne recevront pas automatiquement leur ancien message lors de l'activation ultérieure.
Les publications déjà traitées et terminées ne sont pas rejouées. Pour envoyer un lien ensuite, demander un nouveau renvoi depuis la session PHONE_VERIFIED.
La décision utilise la configuration en vigueur au traitement asynchrone : laisser les publications se terminer avant de changer de capacité au redémarrage.
L'accès par OTP reste disponible lorsque `otp-enabled=true`.

`otp-enabled=false` interdit tout appel Meta pour un OTP : erreur interne fixe `WHATSAPP_OTP_DISABLED`, réponse HTTP cliente uniforme et cooldown existants conservés.
Cette désactivation ne sélectionne pas implicitement l'inbox locale.

## OTP UTILITY : mode actuel de test

Configurer explicitement :
```properties
groupia.notification.whatsapp.templates.otp.mode=UTILITY
groupia.notification.whatsapp.templates.otp.body-parameters=OTP_CODE
```
Un seul paramètre BODY `OTP_CODE` est actuellement supporté. Aucun HEADER ou bouton n'est ajouté.
Le code à six chiffres est exactement celui fourni par customeraccess, y compris ses éventuels zéros initiaux.
`code-expiration-minutes` n'est pas requis ni utilisé dans ce mode : l'expiration serveur reste celle du challenge customeraccess.

Composants envoyés dans le message template configuré :
```json
{
  "components": [{
    "type": "body",
    "parameters": [{ "type": "text", "text": "<code_existant>" }]
  }]
}
```

## OTP AUTHENTICATION copy-code : mode cible futur

Sélectionner `mode=AUTHENTICATION`, retirer/vider `body-parameters` et renseigner `code-expiration-minutes`.
Un mapping UTILITY laissé dans ce mode est refusé pour éviter une configuration ambiguë.


Le code à six chiffres est celui du challenge customeraccess existant, sans seconde génération ni stockage supplémentaire.
Le corps et le bouton copy-code reçoivent exactement ce même code. La forme d'envoi Meta utilise `type=button, sub_type=url, index=0`, distincte de la forme de création du template `otp_type=COPY_CODE`.

```json
{
  "messaging_product": "whatsapp",
  "recipient_type": "individual",
  "to": "<E164_sans_plus>",
  "type": "template",
  "template": {
    "name": "<nom_AUTHENTICATION_approuve>",
    "language": { "code": "<langue_approuvee>" },
    "components": [
      { "type": "body", "parameters": [{ "type": "text", "text": "<code_existant>" }] },
      { "type": "button", "sub_type": "url", "index": "0",
        "parameters": [{ "type": "text", "text": "<meme_code_existant>" }] }
    ]
  }
}
```

`templates.otp.code-expiration-minutes * 60` doit égaler exactement `groupia.customer-access.otp-seconds` (300 secondes par défaut, donc 5 minutes).
Le template approuvé doit afficher cette durée ; la configuration ne change pas son footer chez Meta. Les durées non entières en minutes sont refusées pour ce transport.
Configurer également une durée de validité d'envoi du template chez Meta adaptée au challenge. Les délais de transport ne prolongent jamais l'expiration serveur.

## HTTP, confidentialité et reprise

POST HTTPS sur `https://graph.facebook.com/{version}/{phoneNumberId}/messages`.
Le host ne peut pas être redirigé par configuration. Bearer exclusivement dans Authorization.
Le numéro E.164 canonique fourni par PhoneNumbers est vérifié syntaxiquement puis seul le `+` est retiré ; aucun numéro métier n'est modifié.
Timeouts configurables, bornés à 10s connexion / 30s lecture. Aucun retry applicatif HTTP, aucune redirection. Réponse bornée à 64 KiB.
HTTP 2xx avec `messages[0].id` non vide signifie **accepté**, pas livré/lu. Une réponse inutilisable échoue et reste récupérable.

Logs WARN autorisés : code fixe WHATSAPP_DELIVERY_FAILED, deliveryId, HTTP status (0 si transport), codes/sous-codes Meta numériques.
Les messages Meta, fbtrace, payloads, headers et exceptions originales sont délibérément exclus. Ne pas activer de traces réseau/HTTP contenant corps ou Authorization.
Les diagnostics de configuration sont des codes fixes, sans afficher les valeurs.
Les échecs de livraison des capacités activées font recevoir aux publications Modulith l'erreur générique NOTIFICATION_DELIVERY_UNAVAILABLE ; l'exception infrastructure n'a pas de cause sensible.

La reprise persistante existante est conservée : ordonnanceur PublicationRecovery, publications échouées âgées d'au moins une minute, lots de 100, concurrence plafonnée à 20, moins de 5 tentatives.
Les échecs épuisés doivent être surveillés puis repris explicitement après correction via les outils Modulith existants.
Pas d'outbox supplémentaire, pas de Kafka/Redis/RabbitMQ.
La livraison externe est **at-least-once**, sans garantie exactly-once : un timeout après acceptation peut produire un doublon à la reprise.
Le même événement référence le même linkId et reconstruit le même lien tant qu'il reste valide. Un lien révoqué/archivé n'est plus envoyé et n'est jamais remplacé par le nouveau lien à la reprise.
L'id interne n'est pas envoyé comme fausse clé d'idempotence Meta.

Pour OTP : challenge commité avant livraison, réponse cliente uniforme en cas d'échec, cooldown inchangé ; aucun OTP brut dans une file persistante et aucune reprise Modulith OTP.
La cliente peut redemander un challenge après cooldown.

## Procédure locale

1. Garder GROUPIA_WHATSAPP_ENABLED=false.
2. Démarrer le backend avec le profil `customer-access-local`, les clés customeraccess de développement et les dépendances Docker habituelles (voir customer-access.md).
3. Lire l'inbox locale avec les accès gérante existants. OTP et liens restent uniquement en mémoire, bornés en nombre et durée.
4. Répéter confirmation, OTP et renvoi : inbox ORDER_CONFIRMED puis ORDER_TRACKING_LINK.
5. Aucun appel Meta. Le profil local avec Meta enabled échoue explicitement ; prod/production n'expose jamais l'inbox.

## Premier test réel : OTP UTILITY seul

Ne pas activer `customer-access-local`. Préparer le frontend HTTPS et son proxy API même origine pour les cookies HttpOnly Secure et la protection CSRF existants.
Réutiliser les clés customeraccess de l'environnement sans les régénérer à chaque lancement.

Dans le fichier local ignoré / l'environnement de lancement, remplacer les placeholders :
```dotenv
GROUPIA_CUSTOMER_ACCESS_ENABLED=true
GROUPIA_CUSTOMER_MAGIC_KEY_BASE64=<cle_locale_existante>
GROUPIA_CUSTOMER_OTP_PEPPER_BASE64=<cle_locale_existante>
GROUPIA_PUBLIC_BASE_URL=https://<domaine_public_du_frontend>

GROUPIA_WHATSAPP_ENABLED=true
GROUPIA_WHATSAPP_OTP_ENABLED=true
GROUPIA_WHATSAPP_LINKS_ENABLED=false
GROUPIA_WHATSAPP_GRAPH_API_VERSION=<version_supportee_vN.N>
GROUPIA_WHATSAPP_PHONE_NUMBER_ID=<id_du_numero_meta>
GROUPIA_WHATSAPP_OTP_MODE=UTILITY
GROUPIA_WHATSAPP_OTP_TEMPLATE=<nom_exact_du_template_utility_approuve>
GROUPIA_WHATSAPP_OTP_LANGUAGE=<code_langue_exact>
GROUPIA_WHATSAPP_OTP_BODY_PARAMETERS=OTP_CODE
```
Renseigner également `GROUPIA_WHATSAPP_ACCESS_TOKEN` **uniquement dans l'environnement protégé ou le fichier local ignoré**, sans l'afficher dans le terminal ni le transmettre dans le chat.
Pas de template de lien ni de `GROUPIA_WHATSAPP_OTP_EXPIRATION_MINUTES` à fournir pour ce test.
Les noms abrégés `WHATSAPP_ENABLED` ne sont pas des aliases : utiliser les variables complètes `GROUPIA_WHATSAPP_*`.

1. Vérifier dans WhatsApp Manager que le template sélectionné est approuvé, de catégorie UTILITY et comporte exactement une variable BODY pour l'OTP, sans composant dynamique additionnel. Vérifier les destinataires autorisés si numéro Meta de test.
2. Depuis Backend, démarrer les dépendances habituelles (`docker compose up -d` si nécessaire), puis `.\mvnw.cmd spring-boot:run` avec cet environnement, sans profil local.
3. Ouvrir `/suivi` sur l'origine HTTPS publique, fermer une éventuelle session cliente existante, saisir le numéro WhatsApp et choisir « Recevoir mon code ».
4. Lire le code dans WhatsApp, le saisir avant expiration, vérifier la session PHONE_VERIFIED puis `/mes-commandes`. Un numéro sans commande doit voir une liste vide.
5. Vérifier qu'un code déjà consommé ne fonctionne plus. En cas d'échec de livraison, attendre le cooldown avant de demander un nouveau challenge.
6. Les confirmations de commande ne provoquent aucun envoi de lien avec cette configuration. Elles ne sont pas mises en attente pour une future activation.
7. En cas d'erreur Meta, relever seulement deliveryId, HTTP status et codes numériques. Aucun OTP, téléphone, token ni payload brut à copier.

Aucun envoi réel n'est déclenché par Codex dans cette tranche : cette recette est à exécuter avec les credentials et le compte de l'exploitant.

## Bascules ultérieures, uniquement par configuration

Pour AUTHENTICATION après approbation :
- garder `GROUPIA_WHATSAPP_OTP_ENABLED=true` ;
- sélectionner `GROUPIA_WHATSAPP_OTP_MODE=AUTHENTICATION` ;
- renseigner le nom et la langue du template AUTHENTICATION copy-code approuvé ;
- retirer ou vider `GROUPIA_WHATSAPP_OTP_BODY_PARAMETERS` ;
- définir `GROUPIA_WHATSAPP_OTP_EXPIRATION_MINUTES=5` pour un challenge de 300 secondes, adapter ensemble si sa durée change ;
- redémarrer. Aucun changement customeraccess ni frontend.

Pour les liens après approbation :
- définir `GROUPIA_WHATSAPP_LINKS_ENABLED=true` ;
- renseigner les deux templates nom/langue, leurs mappings exacts et le préfixe URL si bouton dynamique ;
- redémarrer puis tester une **nouvelle** confirmation et un **nouveau** renvoi PHONE_VERIFIED ;
- vérifier réception, ouverture du magic link et révocation de l'ancien lien lors du renvoi.

Rotation du token : mettre à jour sa valeur dans l'environnement protégé, redémarrer le backend, puis révoquer l'ancien token selon la procédure de l'exploitant.
Aucun stockage de credentials en base.

## Sources et limites

- [Collection officielle Meta Cloud API](https://www.postman.com/meta/whatsapp-business-platform/collection/wlk6lh4/whatsapp-cloud-api).
- [Exemple officiel de création AUTHENTICATION copy-code](https://www.postman.com/meta/whatsapp-business-platform/request/6vkv46u/create-authentication-template-w-otp-copy-code-button).
- [Contrat Meta des templates d'authentification](https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/authentication-templates/copy-code-button-authentication-templates).

Pas de webhook Meta entrant, statuts delivered/read, bot, conversation ni notification logistique automatique.
La recette OTP UTILITY peut être exécutée avec le template déjà approuvé. L'activation des liens et le passage à AUTHENTICATION attendent leurs propres templates approuvés. Les tests HTTP simulés valident le contrat de l'adapter, pas l'approbation du compte Meta.
