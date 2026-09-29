# Livraison WhatsApp sortante

## Périmètre et état du raccordement

Le transport Meta est implémenté, mais reste **désactivé par défaut**. Aucun message réel n'est envoyé par les tests.
Aucune migration, règle financière, règle de confirmation ou API Bictorys n'est modifiée.

Contrats communiqués par la gérante pendant cette tranche :
- `commande_status`, français : corps « Votre commande {{1}} est confirmée », variable numéro de commande, aucun bouton/lien. **Insuffisant pour envoyer un magic link** et inadapté au renvoi.
- `commande`, français : UTILITY avec une variable OTP. **Non utilisé** : les OTP exigent AUTHENTICATION copy-code.
- Le template UTILITY contenant le lien et le template AUTHENTICATION restent à faire approuver. Aucun nom/configuration de template réel n'est présumé ou activé.

Pour un premier contrat simple, faire approuver un UTILITY neutre, par exemple « Voici le lien pour suivre votre commande {{1}} : {{2}} », avec BODY positionnel `ORDER_NUMBER,MAGIC_URL`. Il pourra être configuré pour confirmation et renvoi. C'est une **proposition à faire approuver**, pas un template existant.
Autre option : numéro dans BODY et bouton URL dynamique avec préfixe HTTPS public `https://votre-domaine/s/` et suffixe `{{1}}`.
Vérifier le code de langue exact dans Meta (français généralement `fr`, jamais la chaîne `french`).

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
| templates.otp.name | GROUPIA_WHATSAPP_OTP_TEMPLATE | vide |
| templates.otp.language | GROUPIA_WHATSAPP_OTP_LANGUAGE | vide |
| templates.otp.code-expiration-minutes | GROUPIA_WHATSAPP_OTP_EXPIRATION_MINUTES | vide |

Activation explicite : toutes les identités de templates, langues et mappings requis doivent être présents.
Le démarrage refuse un UTILITY sans MAGIC_URL dans BODY ni bouton dynamique, une expiration OTP incohérente ou un conflit avec `customer-access-local`.
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

## OTP AUTHENTICATION copy-code

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
Les publications Modulith reçoivent l'erreur générique NOTIFICATION_DELIVERY_UNAVAILABLE ; l'exception infrastructure n'a pas de cause sensible.

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

## Premier test réel, après approbation des templates

1. Dans WhatsApp Manager : relever le Phone Number ID, une version Graph supportée, le nom/langue et tous les composants des templates UTILITY (lien) et AUTHENTICATION copy-code. Vérifier les autorisations d'envoi et destinataires autorisés si numéro de test.
2. Fournir les secrets par l'environnement de lancement ou le fichier local ignoré déjà utilisé par le backend. Ne jamais coller le token dans le chat, Angular, git ou une commande journalisée.
3. Configurer :
   - GROUPIA_CUSTOMER_ACCESS_ENABLED=true
   - GROUPIA_CUSTOMER_MAGIC_KEY_BASE64 et GROUPIA_CUSTOMER_OTP_PEPPER_BASE64 : clés de l'environnement, stables entre redémarrages.
   - GROUPIA_PUBLIC_BASE_URL : origine HTTPS publique du frontend.
   - GROUPIA_WHATSAPP_ENABLED=true
   - GROUPIA_WHATSAPP_GRAPH_API_VERSION, GROUPIA_WHATSAPP_PHONE_NUMBER_ID, GROUPIA_WHATSAPP_ACCESS_TOKEN.
   - Les trois paires TEMPLATE/LANGUAGE du tableau, les mappings UTILITY exacts et GROUPIA_WHATSAPP_OTP_EXPIRATION_MINUTES (5 pour OTP=300s).
4. Ne pas activer customer-access-local. Conserver les cookies sécurisés et le déploiement frontend/API même origine avec proxy, afin que les sessions HttpOnly et CSRF fonctionnent.
5. Depuis Backend, démarrer les dépendances avec `docker compose up -d`, puis `.\mvnw.cmd spring-boot:run`. Corriger tout échec de configuration avant d'envoyer.
6. **Confirmation** : créer une nouvelle commande avec le numéro WhatsApp de test, payer Bictorys sandbox, attendre son webhook ; vérifier Order CONFIRMED indépendamment de WhatsApp ; recevoir le message, ouvrir le lien et vérifier le suivi individuel.
7. **OTP** : fermer la session, ouvrir /suivi, saisir le numéro, recevoir et valider le code, retrouver /mes-commandes.
8. **Renvoi** : depuis une commande en session PHONE_VERIFIED, demander le lien ; recevoir le template neutre ORDER_TRACKING_LINK ; vérifier que l'ancien lien et ses sessions sont refusés, et que le nouveau lien ouvre le suivi.
9. Vérifier aussi une commande COMPLETED encore autorisée par le backend. Aucun changement SQL manuel pour forcer les commandes/paiements.
10. En cas d'échec, relever uniquement deliveryId, HTTP status et codes numériques. Corriger configuration/approbation puis laisser la reprise des liens agir. Ne pas copier les payloads clients.

Rotation du token : mettre à jour sa valeur dans l'environnement protégé, redémarrer le backend, puis révoquer l'ancien token selon la procédure de l'exploitant.
Aucun stockage de credentials en base.

## Sources et limites

- [Collection officielle Meta Cloud API](https://www.postman.com/meta/whatsapp-business-platform/collection/wlk6lh4/whatsapp-cloud-api).
- [Exemple officiel de création AUTHENTICATION copy-code](https://www.postman.com/meta/whatsapp-business-platform/request/6vkv46u/create-authentication-template-w-otp-copy-code-button).
- [Contrat Meta des templates d'authentification](https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/authentication-templates/copy-code-button-authentication-templates).

Pas de webhook Meta entrant, statuts delivered/read, bot, conversation ni notification logistique automatique.
La recette réelle reste à exécuter après approbation des templates manquants. Les tests HTTP simulés valident le contrat de l'adapter, pas l'approbation du compte Meta.
