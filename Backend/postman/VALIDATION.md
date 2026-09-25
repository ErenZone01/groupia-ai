# Validation de la collection locale
Date : 22 septembre 2026.

## Artefacts
27 requêtes, 9 dossiers, 35 variables. Aucun secret réel dans les exports versionnés.
47 scripts validés syntaxiquement, y compris la détection des anciens noms globaux
réservés du runtime Postman :

```powershell
node postman/validate-scripts.cjs
```

Springdoc 3.1.0 déjà installé, sortie OpenAPI 3.0.1. GET réellement effectué sur
http://localhost:8080/v3/api-docs : HTTP 200, 28 chemins / 33 opérations métier.
Validation locale par swagger-cli 4.0.4 : spec valide. Aucune spec envoyée à un
service externe. Copie ignorée par Git : target/groupia-openapi-local.json.

## Exécutions Newman 6.2.2 contre Groupia local/PostgreSQL
Toutes les données ont été créées par API avec un compte de test temporaire.

| Parcours réellement exécuté | Requêtes | Assertions | Échecs |
| --- | ---: | ---: | ---: |
| A : dossiers 00 à 05, jusqu'au checkout et suivi PENDING_PAYMENT | 12 | 51 | 0 |
| Lectures organisateur orderId / paymentRequestId | 2 | 9 | 0 |
| N : login, autorisation, mauvais token, idempotence et campagne DRAFT | 8 | 28 | 0 |

Le passage N a utilisé la collection entière avec runPhase=N : les requêtes A/B
ont été ignorées. Une collision avec le global réservé request a été détectée
dans un script financier, corrigée, puis ces deux lectures ont été réexécutées
avec succès. Les données créées sont laissées en base ; aucun nettoyage SQL.

Les fichiers d'environnement temporaires et résultats sont uniquement sous target,
ignoré par Git. Ils contiennent des credentials/tokens de test et ne doivent pas
être partagés tels quels. Journaux : postman-smoke.log, postman-financial-reads.log,
postman-negative.log et postman-spec-validation.log.

## Limites de cette validation
Aucune initiation Bictorys distante ni paiement humain exécuté. Le contrôle complet
A → paiement sandbox → B reste à effectuer avec le compte sandbox et son webhook.
La syntaxe de toutes les étapes, les routes et les DTO ont été vérifiés ; cela ne
remplace pas le passage réel des étapes de paiement. Aucun webhook falsifié pour
faire passer la phase B. Les scénarios provider simulés restent dans les tests Java.

## Maven
Le résultat final des commandes complètes est consigné ci-dessous après exécution.


- `.\mvnw.cmd test` : BUILD SUCCESS, **329 tests**, 0 échec/erreur/ignoré, 3 min 43 s.
- `.\mvnw.cmd verify` : BUILD SUCCESS, **329 tests**, 0 échec/erreur/ignoré, 3 min 41 s ; JAR produit.
- Les 324 tests antérieurs sont conservés ; 5 nouveaux tests OpenAPI/collection.
- Les 3 tests Modulith passent sans modification ni exclusion.
- `git diff --check` : aucune erreur.

Logs complets locaux : target/postman-test.log et target/postman-verify.log.
Au total, Newman a exécuté 22 requêtes et 88 assertions avec succès, hors paiement distant.
