# Exploitation et reprise financière

Un adaptateur Bictorys sandbox est disponible, désactivé par défaut. Aucun provider de production n'est autorisé. Ces procédures décrivent les outils applicatifs/SQL de diagnostic disponibles et les obligations d'exploitation à concrétiser avec la première intégration réelle. Ne jamais corriger un incident en mettant manuellement Payment à SUCCEEDED ou en supprimant une réception/publication.

## Ordonnanceurs

| Propriété | Défaut / effet |
| --- | --- |
| groupia.ordering.expiration.enabled | true ; GROUPIA_ORDER_EXPIRATION_ENABLED |
| groupia.ordering.expiration.delay | 60000 ms ; GROUPIA_ORDER_EXPIRATION_DELAY_MS |
| groupia.ordering.expiration.batch-size | 100, plage 1..1000 ; GROUPIA_ORDER_EXPIRATION_BATCH_SIZE |
| groupia.payment.webhook-retry.enabled | true ; GROUPIA_WEBHOOK_RETRY_ENABLED |
| groupia.payment.webhook-retry.delay | 60000 ms |
| groupia.events.recovery.enabled | true ; GROUPIA_EVENT_RECOVERY_ENABLED |
| groupia.events.recovery.delay | 60000 ms |
| spring.modulith.events.staleness.published/processing/resubmitted | 5m |

Expiration : Clock injectable, parcours par curseur, une transaction par commande. Plusieurs instances peuvent parcourir les mêmes commandes : verrous et revalidation empêchent les doubles transitions. Le redémarrage reprend les lignes dues. Une demande déjà réglée attend son événement de confirmation, même au-delà de l'échéance ; le curseur empêche ces lignes de bloquer les autres expirations.

Webhook : au plus 100 réceptions dues par passage ; délais 60, 120, 180, 240 secondes, puis arrêt automatique après cinq essais au total en REVIEW_REQUIRED. La première tentative compte. Les réceptions dupliquées pendant l'attente ne consomment pas un essai supplémentaire. Plusieurs workers peuvent effectuer le même verify, opération de lecture ; l'écriture reste idempotente et verrouillée. Après rollback du traitement, le compteur est persisté dans une transaction séparée quand PostgreSQL est disponible ; sinon la réception reste récupérable et un log ERROR identifie son UUID. Le worker continue les autres réceptions après erreur.

## Publications Spring Modulith

PaymentRequestSettled et son entrée event_publication sont écrits dans la même transaction. ApplicationModuleListener effectue la confirmation dans une transaction séparée. Le registre conserve succès/échec et compte les tentatives. Le moniteur Modulith traite les états restés PUBLISHED/PROCESSING/RESUBMITTED au-delà des seuils configurés, notamment après un crash.

PublicationRecovery utilise FailedEventPublications.resubmit avec batchSize=100, maxInFlight=20, minAge=1 minute et filtre completionAttempts<5. Les publications échouées ayant atteint cinq essais restent présentes ; un log ERROR signale leur nombre à chaque passage. Une panne persistante ne déclenche pas une boucle infinie silencieuse.

Référence : [événements et registre Spring Modulith](https://docs.spring.io/spring-modulith/reference/events.html). Les APIs utilisées correspondent aux dépendances 2.1.1 déjà présentes dans le projet ; aucune bibliothèque supplémentaire.

## Diagnostic en lecture seule

Exécuter avec un accès d'exploitation autorisé et conserver les identifiants d'incident, jamais les secrets :

```sql
SELECT id, status, completion_attempts, publication_date, last_resubmission_date
FROM event_publication
WHERE completion_date IS NULL
ORDER BY publication_date;

SELECT id, provider, payment_id, processing_status, attempt_count,
       next_attempt_at, last_error_code, received_at
FROM payment_webhook_events
WHERE processing_status IN ('RECEIVED', 'RETRYABLE_FAILURE', 'REVIEW_REQUIRED')
ORDER BY received_at;

SELECT id, payment_request_id, provider, status, reconciliation_reason,
       initiated_at, last_checked_at
FROM payments
WHERE reconciliation_required
ORDER BY initiated_at;
```

Surveiller l'âge et le volume des publications incomplètes, celles FAILED à cinq essais, réceptions REVIEW_REQUIRED et paiements à réconcilier. Les logs signalent également les confirmations impossibles sur une commande inéligible. Le routage des alertes et la désignation d'un responsable d'exploitation restent à configurer au déploiement ; aucun service externe de monitoring n'est ajouté.

## Échecs de parsing des webhooks authentiques

Le port interne `WebhookParsingFailure` permet à chaque adaptateur de fournir un
code issu d'une énumération. L'orchestration conserve uniquement son nom validé,
jamais le message libre ou la cause d'une exception. Une erreur non classifiée
devient `WEBHOOK_PARSE_FAILED` ; un identifiant de livraison normalisé invalide
devient `WEBHOOK_INVALID_EVENT_ID`.

Codes Bictorys conservés : `BICTORYS_INVALID_JSON`, `BICTORYS_UNKNOWN_ENVELOPE`,
`BICTORYS_INCOMPLETE_EVIDENCE`, `BICTORYS_UNSUPPORTED_EVENT`,
`BICTORYS_INVALID_TIMESTAMP`, `BICTORYS_INVALID_FIELD`, `BICTORYS_INVALID_IDENTIFIER`.

Une erreur de parsing après authentification produit atomiquement une réception
`VALID` / `REVIEW_REQUIRED` avec ce `last_error_code`, `observation` et `payment_id`
nuls. Aucun paiement n'est résolu ou modifié, aucun événement de règlement n'est
publié et aucune reprise automatique n'est planifiée. Un mauvais secret conserve
`PAYMENT_AUTHENTICITY_FAILED` et ne déclenche ni parsing ni diagnostic de structure.
`UNKNOWN_REFERENCE` reste réservé au traitement d'une observation sans référence
interne connue ; il ne masque plus une exception du parser.

Le WARN `Webhook parsing failed` donne le provider, l'UUID de réception (ou null
si sa persistance échoue), le code et la structure JSON. Il contient seulement :

- le type racine et celui de `data` ;
- les noms immédiats des champs racine et de `data`, sans exploration imbriquée ;
- les types des champs `id`, `amount`, `currency`, `status`, `paymentReference`,
  `merchantReference`, `reference`, `type`, `event`, `timestamp` à ces deux niveaux.

Les types distinguent notamment `missing` et `null`. Un JSON malformé ou avec des
clés dupliquées donne `rootType=invalid`, sans tentative d'affichage du contenu.
Les listes sont limitées à 40 noms par niveau ; les noms non composés de lettres
ASCII/underscores (1 à 64 caractères) sont remplacés par un marqueur. Les clés
sensibles connues, dont `customerObject`, `checkoutUrl`, les secrets et les headers,
sont exclues. Aucune valeur, exception, URL de checkout, coordonnée client ou
payload brut ne figure dans ce diagnostic. Ce contrôle séparé n'alimente jamais
la validation financière. La tolérance du timestamp des webhooks `SUCCEEDED`
est décrite dans le [guide Bictorys](bictorys-sandbox.md#timestamp-du-webhook-succeeded) :
elle conserve l'obligation de preuve serveur et ne modifie pas ces diagnostics
pour les autres erreurs de parsing.

Une redélivrance identique conserve le même receipt et ne multiplie pas les essais.
Décision de compatibilité : elle peut préciser un ancien `UNKNOWN_REFERENCE`
uniquement si le receipt est authentique, en revue, sans observation et sans
paiement associé. Les diagnostics déjà explicites restent inchangés. Les anciennes
lignes ne sont pas réécrites en masse : sans redélivrance, le payload non conservé
ne permet pas de retrouver leur erreur exacte. Aucune migration n'est nécessaire.

## Reprise après correction

1. Identifier la réception, tentative, demande et/ou publication concernée. Vérifier disponibilité provider, cohérence de références, logs et état courant ; résoudre la cause avant toute reprise.
2. Pour une tentative incertaine, PaymentService.reconciliationQueue(limit 1..100) permet un inventaire borné ; PaymentService.reconcile(paymentId) appelle uniquement verify hors transaction puis valide/enregistre idempotemment. Pas d'appel automatique initiate, pas de nouveau débit. Ces méthodes sont internes à payment, sans endpoint public/admin dans cette tranche. Un futur outil d'exploitation doit passer par un contrat autorisé, sans contourner les frontières Modulith.
3. PaymentWebhooks.retry(id)/retryPending reprend les réceptions encore RECEIVED/RETRYABLE_FAILURE arrivées à échéance. REVIEW_REQUIRED n'est pas automatiquement rejoué. Pour une réception épuisée liée à un paiement connu, reconcile(paymentId) peut résoudre l'état financier après correction ; la réception conserve son historique d'investigation.
4. Après correction d'un consumer, l'API FailedEventPublications.resubmit accepte ResubmissionOptions avec un filtre sur l'identifiant exact de publication, une taille bornée et l'âge adapté. Une resoumission manuelle explicitement ciblée peut dépasser le plafond automatique après investigation ; ne pas réinitialiser le compteur en SQL. Ce mécanisme ne fabrique pas de nouvel événement ni de nouveau débit. Vérifier ensuite COMPLETED, confirmedAt et absence de deuxième affectation.
5. LATE_SUCCESS/DUPLICATE_SUCCESS restent SUCCEEDED, avec leurs motifs, même après un verify confirmant le débit. Leur résolution commerciale/remboursement reste une tranche future ; aucune affectation silencieuse ni réactivation.

Si une panne DB empêchait la toute première réception, le provider doit redélivrer. Si le claim d'initiation existe mais que l'appel n'a peut-être pas eu lieu, investiguer à partir de merchantReference ; aucun endpoint Bictorys de recherche par cette référence n'est documenté. Sans transactionId, attendre le webhook/redélivrance et vérifier le dashboard ; un résultat inconnu reste incertain. Ne pas considérer une absence temporaire chez le provider comme un échec définitif sans garantie contractuelle.

## Validation et démarrage

Docker Desktop et Java 21 sont nécessaires aux tests PostgreSQL/Testcontainers, sans H2 :

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
```

Démarrage local, avec configuration JWT existante :

```powershell
docker compose up -d postgres
.\mvnw.cmd spring-boot:run
```

007-payments est appliquée par Liquibase ; aucun changelog antérieur n'est modifié. Les anciennes commandes reçoivent leurs obligations initiales à partir des snapshots, sans preuve financière inventée. Hibernate conserve ddl-auto=validate. Sans activation Bictorys, l'initiation reste indisponible. Voir le [guide sandbox](bictorys-sandbox.md) pour la migration 009 et le parcours de test.

## Incident de checksum de 006-ordering

Pour le checksum historique `9:aa0b0f88a758f6300ff30ca7ca1365aa`, la correction
008 fait partie du changelog : démarrer normalement après mise à jour du code.
Ne pas effacer DATABASECHANGELOG ou exécuter clear-checksums globalement.
Un autre checksum inattendu exige une comparaison avant toute acceptation.
Si 008 signale des snapshots invalides, conserver les lignes et investiguer ;
aucune réparation automatique de l'historique financier n'est prévue.
Voir [ADR-0011](../adr/0011-ordering-migration-compatibility.md).




## Bictorys sandbox

PROVIDER_REVERSAL est persistant : événements ordinaires et erreurs réseau ne
l'effacent pas. AUTHORIZATION_NOT_CAPTURED exige une preuve de succès explicite ;
PROVIDER_STATUS_UNKNOWN conserve les états inconnus pour investigation.
PAYMENT_REFERENCE_MISMATCH indique un numéro de commande incohérent. Aucun de ces
motifs ne justifie un force-success ou une nouvelle initiation.
Voir [ADR-0012](../adr/0012-bictorys-sandbox-adapter.md) et le [parcours manuel](bictorys-sandbox.md).
