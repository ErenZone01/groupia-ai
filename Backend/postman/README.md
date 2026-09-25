# Groupia Local — Postman E2E

La collection appelle uniquement Groupia sur localhost, avec PostgreSQL réel. Les données métier sont créées par les APIs. Aucun SQL, appel direct à Bictorys ou webhook simulé. Le paiement hébergé sandbox demande une interaction humaine.

## Premier lancement

1. À la racine du projet, démarrer Docker Desktop puis PostgreSQL :

   ```powershell
   docker compose up -d postgres
   ```

2. Préparer le fichier local `.env` déjà utilisé par Groupia : configuration JWT existante et, pour le paiement, `BICTORYS_ENABLED=true`, `BICTORYS_API_URL=https://api.test.bictorys.com`, `BICTORYS_API_KEY` sandbox, `BICTORYS_WEBHOOK_SECRET`. Le fichier est chargé automatiquement par `spring.config.import=optional:file:.env[.properties]` : ne pas le recopier dans Postman. Conserver les secrets côté serveur ; aucune private key requise.

3. Démarrer depuis cette même racine :

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

4. Dans un deuxième terminal, lancer le Quick Tunnel :

   ```powershell
   cloudflared tunnel --url http://localhost:8080
   ```

   Dans le dashboard Bictorys **sandbox**, enregistrer
   `https://<nom-affiche>.trycloudflare.com/api/payment/webhooks/bictorys`,
   avec le secret configuré côté Groupia. Chaque changement de l'URL trycloudflare impose de mettre à jour le dashboard. Garder Groupia et le tunnel actifs. Voir le [guide Bictorys](../docs/bictorys-sandbox.md) pour les conditions du compte sandbox.

5. Vérifier la documentation :

   ```powershell
   $spec = Invoke-RestMethod http://localhost:8080/v3/api-docs
   $spec.openapi
   $spec.paths.PSObject.Properties.Count
   ```

   Attendu : OpenAPI 3.0.1, 28 chemins / 33 opérations métier. Swagger UI :
   [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html).
   Seule la documentation GET est accessible sans JWT. Les APIs organisateur et Actuator restent protégés.

6. Dans Postman Desktop, **Import → Files**, importer :
   - `Groupia-local.postman_collection.json`
   - `Groupia-local.postman_environment.json`

7. Sélectionner **Groupia Local**. Dans les valeurs locales de l'environnement :
   - `phoneNumber` : numéro valide autorisé pour votre scénario sandbox, idéalement E.164 ;
   - `password` : mot de passe de test respectant la politique Groupia (15 caractères minimum, 72 octets UTF-8 maximum) ;
   - `email` : facultatif, vide pour l'omettre ;
   - `firstName` / `lastName` : Test / Groupia par défaut ;
   - `runPhase=A`, `newScenario=false`, `baseUrl=http://localhost:8080`.

   Si le téléphone est déjà inscrit, register accepte uniquement le conflit PHONE_ALREADY_REGISTERED puis login vérifie réellement le mot de passe. Aucun compte existant n'est modifié. Ne pas importer un environnement rempli dans le dépôt ni partager ses valeurs sensibles.

8. Ouvrir **Run collection** sur la collection entière. Choisir **1 itération**, délai **1000 ms**, et activer **Keep variable values / conserver les valeurs des variables**. Lancer PHASE A. Les requêtes B/N sont ignorées automatiquement. L'exécution s'arrête après le reçu financier. Il faut conserver ces variables pour la phase suivante.

9. Dans l'environnement local, copier `checkoutUrl` et l'ouvrir dans le navigateur. Le lien peut contenir un jeton : ne pas le publier. Il est aussi dans la réponse privée d'initiation. Ne pas relancer toute la phase A pour le retrouver.

10. Effectuer le paiement **sandbox** sur le checkout hébergé Bictorys, avec Wave ou Orange Money et les moyens de test disponibles sur votre compte.

11. Attendre la réception du webhook. Un retour navigateur ou HTTP 202 ne prouve pas le règlement. Groupia contrôle les références, montant/devise et vérifie le statut serveur avant de publier le règlement.

12. Dans le même environnement conservé, mettre `runPhase=B` puis relancer la collection entière, toujours 1 itération / délai 1000 ms. Cette phase renouvelle le JWT, vérifie le suivi privé et l'obligation financière. Au maximum **12 lectures de suivi**, sans appel de réconciliation provider ni polling permanent. Si le webhook tarde, le test final échoue explicitement ; attendre puis relancer B. Éviter les relances répétées dans la même minute (limite de suivi 60/minute).

13. Attendu : Order **CONFIRMED**, PaymentRequest **CLOSED**, settledPaymentId égal à paymentId, Payment **SUCCEEDED**, montant confirmé exact en XOF, aucune reconciliationRequired. La phase B capture alors le véritable providerTransactionId.

Réaliser A → paiement → B avant paymentDeadline (30 minutes par défaut). La reconnexion en B évite qu'un JWT de 15 minutes empêche la vérification financière. Une commande expirée, un authorized, un reversal ou un succès tardif peut nécessiter une revue : la collection ne force aucun succès.

## Ordre des dossiers et phases

| Dossier | Requêtes |
| --- | --- |
| 00 - Health / Setup | A : prérequis, initialisation et OpenAPI |
| 01 - Auth | A : register/login, health avec JWT |
| 02 - Organization | A : création organisation |
| 03 - Catalog | A : produit et variante DEFAULT générée par le serveur |
| 04 - Grouping | A : DRAFT, offre 1000 XOF, OPEN, storefront public |
| 05 - Public Checkout | A : quantité 1, checkout et suivi PENDING_PAYMENT |
| 06 - Payments | A : IDs internes via lecture organisateur, initiation Bictorys, reçu et arrêt |
| 07 - Order Tracking | B : login, suivi borné, vérification financière |
| 08 - Negative Tests | N : login et fixtures séparées |

La campagne utilise FIXED / AFTER_FINAL_ASSESSMENT. Aucun transport final n'est encaissé à ce stade. Le montant initial est calculé côté Groupia ; les tests comparent les valeurs retournées sans supposer que les frais configurés valent toujours 300.

Les requêtes organisateur héritent du Bearer au niveau des dossiers. Auth, storefront, checkout, initiation avec tracking token et suivi portent explicitement noauth : aucun JWT inutile. X-Secret-Key n'apparaît dans aucune requête de cette collection.

## Variables et extraction

Les valeurs sensibles et tous les identifiants temporaires sont vides dans l'environnement versionné. Les 35 variables sont :

| Variables | Origine / fonction |
| --- | --- |
| baseUrl, runPhase, newScenario | Contrôle local ; phases A, B ou N |
| email, password, firstName, lastName, phoneNumber | Saisie locale ; email facultatif |
| accessToken | login.accessToken |
| organizationId | création organisation.id |
| productId, variantId | produit.id, produit.variants[0].id |
| campaignId, campaignPublicIdentifier, campaignVersion | campagne.id, publicIdentifier, version actualisée |
| offerId, priceRevision | campagne.offers[0].id / priceRevision |
| orderNumber, trackingToken, initialAmountDue | réponse de création checkout ; token remis une seule fois |
| orderId | lecture organisateur des commandes, filtrée par campagne et numéro |
| paymentRequestId | liste organisateur, sélection par orderId et purpose=INITIAL_ORDER |
| paymentId, checkoutUrl | réponse d'initiation.id / checkoutUrl |
| providerTransactionId | détail organisateur.attempts, sélection par paymentId ; peut être vide en A |
| idempotencyKey | Alias de la dernière clé utilisée ; les headers emploient les clés dédiées |
| checkoutIdempotencyKey, paymentIdempotencyKey | GUID distincts, générés une seule fois par opération |
| checkoutBody | Payload exact conservé pour reprise et tests d'idempotence |
| trackingAttempts | Compteur remis à zéro au login B |
| negativeCampaignId, negativeCampaignPublicIdentifier, negativeCampaignVersion, negativeOfferId, negativeIdempotencyKey | Fixture DRAFT séparée et clé indépendante pour N |

Le reçu public d'initiation **ne contient pas paymentRequestId ni providerTransactionId**. Ils ne sont donc pas inventés : les lectures JWT autorisées les récupèrent. Un chargeId Bictorys n'est jamais utilisé comme transactionId.

La génération des clés utilise `pm.variables.replaceIn("{{$guid}}")`, supporté par le runtime Postman. Aucune dépendance au global crypto d'un navigateur.

## Reprise et idempotence

- Une reprise de checkout réutilise checkoutIdempotencyKey et checkoutBody inchangés. Un 200 rejoué ne rend pas le token ; la collection exige que celui-ci ait déjà été conservé pour ce numéro.
- Si la première réponse checkout a été perdue avec son trackingToken, ce parcours ne permet pas de récupérer le secret. Ne pas créer une nouvelle commande pour contourner cette situation.
- Une reprise d'initiation réutilise paymentIdempotencyKey. Si la réponse est incertaine ou sans URL, le test s'arrête ; ne pas générer une autre clé. Après correction de configuration, reprendre la requête concernée, avec un JWT renouvelé si une lecture organisateur est nécessaire.
- Une relance complète A avec un checkout précédent est bloquée. Pour un **nouvel achat de test volontaire**, définir newScenario=true puis lancer A. Les anciennes données restent dans PostgreSQL ; les variables du scénario sont réinitialisées. Cette option ne répare pas une tentative incertaine.
- Les scripts arrêtent le chaînage sur une assertion obligatoire échouée. Les tests affichent des noms explicites, sans consigner les tokens, mots de passe ou clés. Le Runner peut conserver requêtes/réponses contenant ces données : garder les résultats locaux et ne pas les exporter/partager sans expurgation.

## Tests négatifs

Après A, mettre runPhase=N et lancer la collection entière. Ce parcours est indépendant du paiement manuel et renouvelle le JWT.

1. Organisation sans JWT → 401 AUTHENTICATION_REQUIRED.
2. Mauvais tracking token → 404 ORDER_TRACKING_INVALID.
3. Même checkout, même clé et même contenu → 200, même numéro, replayed=true, aucun nouveau token.
4. Même clé, quantité différente → 409 IDEMPOTENCY_KEY_REUSED.
5. Création d'une campagne DRAFT et de sa propre offre via API, sans l'ouvrir ; checkout → 409 GROUPING_NOT_ORDERABLE.

La campagne principale reste OPEN. Aucun test négatif ne déclenche de paiement ni ne ferme sa campagne. Les fixtures créées demeurent en base ; aucune suppression SQL n'est prévue.

## Recréer une collection depuis OpenAPI

Postman **Import → Link** : `http://localhost:8080/v3/api-docs`, puis importer en collection. Avec un agent cloud incapable d'atteindre localhost, utiliser Postman Desktop/Desktop Agent ou télécharger la spec :

```powershell
Invoke-WebRequest http://localhost:8080/v3/api-docs -OutFile target/groupia-openapi.json
```

Importer ce fichier dans Postman. Cette collection générée couvre les 33 opérations mais ne recrée pas les scripts, snapshots de payload, phases et garde-fous d'idempotence de la collection optimisée du dépôt. Pour le scénario E2E, utiliser les fichiers fournis.

Springdoc **3.1.0** était déjà présent, compatible avec la famille Spring Boot 4. La sortie est explicitement **OpenAPI 3.0.1** pour l'import portable et pour éviter les avertissements de conversion de schémas numériques observés avec la sortie 3.1 de cette version. Les modèles restent issus des DTO Java ; le checkout distingue création et rejeu. Le test compare toutes les routes REST métier réellement enregistrées avec la spec et vérifie les références de schémas.

## Validation reproductible

Depuis PowerShell, Java 21 et Docker Desktop actifs :

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
```

Le parcours local sans paiement peut aussi être exécuté avec Newman, en utilisant un **environnement local non versionné et renseigné** :

```powershell
npm.cmd exec --yes --package=newman -- newman run postman/Groupia-local.postman_collection.json -e target/mon-environnement-local.json --folder "00 - Health / Setup" --folder "01 - Auth" --folder "02 - Organization" --folder "03 - Catalog" --folder "04 - Grouping" --folder "05 - Public Checkout" --export-environment target/mon-resultat-local.json
```

Cette commande s'arrête avant 06 ; elle ne réalise pas le paiement. Les fichiers exportés contiennent des secrets de test : conserver uniquement sous target ignoré par Git. Newman est un outil de vérification, pas une dépendance du backend.

Pour les résultats de livraison, voir [VALIDATION.md](VALIDATION.md). Le paiement distant et sa confirmation restent une validation manuelle explicite ; les mocks Bictorys appartiennent aux tests Java.

## Références

- [Springdoc et Spring Boot](https://springdoc.org/).
- [Collection Runner et conservation des variables](https://learning.postman.com/docs/tests-and-scripts/running-collections/intro-to-collection-runs/).
- [Chaînage et arrêt de requêtes](https://learning.postman.com/v11/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-execution).
- [Variables dynamiques Postman](https://learning.postman.com/v11/docs/use/send-requests/variables/use-variables/).

