# Clôture prouvée et pilote indépendant — 15 novembre 2026

## État observé, uniquement par lecture

Base de départ : `main` après la fusion de #87 (`e8f71de9`). L'instance PostgreSQL
accessible est celle du pilote réel (`backend-postgres-1`). Les requêtes d'audit ont
été exécutées en transaction `BEGIN READ ONLY`. Aucun nouveau backend n'a été lancé
sur cette base, aucune migration 060 n'y a été appliquée, aucune donnée financière
n'a été modifiée et aucun POST SasPay n'a été effectué.

Boutique : `b95a0515-1bcf-4740-8417-c594d02f211e`.
Compte SasPay : `131c3f1e-1b3c-4970-89e2-b0b79d1ee8be`.

| Élément | DIR-000000000021 | DIR-000000000024 |
| --- | --- | --- |
| ID commande | `2324053b-dd52-4078-a87d-ac8d4c6c918e` | `f1d691db-b561-4412-8149-3314b5f6955b` |
| État commercial | COMPLETED | COMPLETED |
| Encaissement Groupia | SUCCEEDED, 1 000 XOF vendeur | SUCCEEDED, 1 000 XOF vendeur |
| Transaction entrante SasPay | `14cb413e-7a11-4a38-8027-f4d0e5e9bc98` | `23b8a584-3a2d-4e9c-aff5-349da6e9eddb` |
| GET SasPay de vérification | SUCCESS, net 1 000 | SUCCESS, net 1 000 |
| Payout | `e1ecc9ae-89ab-449f-bc91-1a7e59f03a0e` | Aucun |
| Réserve | 1 000 XOF | 0 |
| Disponible des fonds concernés | 0 | 1 000 XOF |

Les deux GET confirment aussi PAIEMENT/INBOUND, XOF, marchand attendu, brut demandé
1 000, frais client ADD_ON 52, débit client 1 052 et net marchand 1 000. Ces frais
d'encaissement sont distincts des frais DEDUCTED du futur retrait.

Le payout historique est toujours `REVIEW_REQUIRED / PAYOUT_MANUAL_RECOVERY_UNCERTAIN`,
version **5**, demandé le `2026-11-14T06:23:43.311447Z`, sans référence fournisseur,
y compris dans `outgoing_provider_operations`. Aucun autre payout de la boutique,
aucun remboursement commercial et aucune revue de paiement OPEN n'ont été trouvés.
Les `provider_collections` SasPay totalisent **2 000 XOF**. Les crédits de groupage
de 238 500 XOF proviennent de **BICTORYS / sandbox** et ne financent pas SasPay.
Une réserve n'est ni une nouvelle collecte ni la preuve d'un versement sortant.

Le GET `/transactions/`, filtré RETRAIT/OUTBOUND/XOF/compte depuis le 14 novembre
à 00:00 UTC, a retourné une page vide sans page suivante. Cela concorde avec le
tableau de bord consulté par l'opérateur. **Ce n'est pas une preuve définitive de
non-exécution.** Le diagnostic ancien communiqué reste insuffisant pour savoir si
la deuxième tentative a transmis un POST. Les journaux accessibles dans le dépôt
ne contiennent pas de trace supplémentaire corrélable de cette initialisation ou
récupération ; aucune attestation fournisseur n'est disponible à ce stade.

Outil reproductible, GET uniquement, refus des redirections et pagination bornée :

```powershell
python tools/saspay_audit_payouts.py --merchant-id 131c3f1e-1b3c-4970-89e2-b0b79d1ee8be --since 2026-11-14T00:00:00Z
```

## Frontière de preuve et autorisation administrative

L'API n'accepte jamais un booléen « le fournisseur n'a pas payé » comme preuve.
Un opérateur habilité doit d'abord vérifier un dossier hors ligne, puis provisionner
un manifeste JSON et son document source sur le serveur, hors dépôt et hors
répertoire de logs. Le SHA-256 du manifeste est fixé dans la configuration du
serveur ; le manifeste fixe à son tour le SHA-256 du document `.evidence` adjacent.
Aucun chemin ni URL ne provient de la requête HTTP. Aucun téléchargement de preuve
arbitraire n'est effectué par le backend.

**Les empreintes prouvent l'intégrité, pas la véracité d'un courrier fournisseur.**
La provenance et le contenu doivent être vérifiés humainement avant de fixer
l'empreinte serveur. Un document inventé, une capture d'une liste vide, un 404 ou
un diagnostic NOT_ATTEMPTED limité à une seule tentative sont insuffisants.

Pour `CLOSE_NON_EXECUTED`, le dossier doit être l'un des suivants :

- `PROVIDER_ATTESTATION` : confirmation explicite SasPay corrélée au compte, à
  l'intention, à la clé originale et aux **deux tentatives**, attestant l'absence
  définitive de débit/versement et l'impossibilité d'une exécution différée.
- `COMPLETE_PRE_DISPATCH_AUDIT` : traces techniques complètes et indépendamment
  vérifiées prouvant que **toutes** les tentatives se sont arrêtées avant transport,
  qu'aucune requête n'est encore en vol ou en file, et qu'aucun autre émetteur n'a
  utilisé la clé. Les journaux historiques actuellement disponibles ne le prouvent pas.

Le manifeste suit `PayoutAdministrativeApproval` et contient :

- `reference`, `action`, `organizationId`, `payoutId`, `payoutVersion`, `operatorId` ;
- `providerAccount`, `originalKeySha256`, `businessReferenceSha256`, `amount=1000`, `currency=XOF` ;
- `evidenceType`, `evidenceReference`, `evidenceSha256` ;
- `allAttemptsCovered`, `definitiveNonExecution`, `futureExecutionImpossible` tous
  vrais pour une clôture, après vérification réelle ;
- `reviewedAt`, `expiresAt` (au plus 24 h après revue) ;
- pour `INDEPENDENT_PILOT` : `directOrderId` de la deuxième commande et
  `destinationId` de la destination Wave immuable approuvée.

Calculer l'empreinte de la clé originale dans le processus autorisé à la lire,
sans jamais afficher la clé ni la copier dans une requête, une commande shell,
une PR ou un log. Ne jamais fabriquer un dossier LIVE à partir des fixtures de tests.

Paramètres administratifs, tous désactivés/vides par défaut :

```properties
GROUPIA_PAYOUT_ADMINISTRATION_ENABLED=false
GROUPIA_PAYOUT_ADMINISTRATION_OPERATOR_IDS=
GROUPIA_PAYOUT_APPROVAL_FILE=
GROUPIA_PAYOUT_APPROVAL_SHA256=
```

Après accord explicite sur l'opération administrative, activer temporairement pour
l'UUID opérateur désigné. Cet acteur doit aussi être membre de la boutique avec
`EXECUTE_PAYOUTS` et `MANAGE_SETTLEMENTS`. La seule appartenance ou un UUID deviné
ne suffisent pas. Le manifeste doit désigner exactement cet acteur.

API Groupia, **à ne pas exécuter avant cet accord** :

```http
POST /api/organizations/{org}/merchant-payouts/{historicalId}/administration
Authorization: Bearer <session opérateur en mémoire>
Content-Type: application/json

{
  "action": "CLOSE_NON_EXECUTED",
  "approvalReference": "<référence du manifeste réellement revu>",
  "expectedVersion": 5,
  "reason": "<motif technique précis, sans secret ni coordonnées>",
  "confirmed": true
}
```

L'opération verrouille la boutique, recontrôle état/version/corrélation, écrit un
audit immuable et clôture dans **une seule transaction**. Tout échec annule les
deux écritures. Le rejeu strict retourne la décision initiale ; un motif, acteur,
document ou contenu différent provoque un conflit. La clôture n'est possible que
depuis REVIEW_REQUIRED sans référence fournisseur. La clé, l'identité, le devis et
les snapshots restent inchangés. NON_EXECUTE ne peut plus être réinitialisé, envoyé
ou converti automatiquement vers un autre statut. La réserve disparaît du calcul
normal ; aucun crédit artificiel n'est créé.

## Option actuellement préparée : deuxième commande indépendante

En l'absence de preuve définitive sur DIR-21, **ne pas la clôturer**. Un dossier
`INDEPENDENT_FUNDS_RISK_REVIEW` peut autoriser `action=INDEPENDENT_PILOT` sur le même
endpoint, avec une référence de revue des risques vérifiable et un manifeste fixé
côté serveur. Le résultat laisse le payout historique strictement inchangé.

La revue documente deux collectes distinctes et vérifiées, une réserve historique
toujours couverte, aucun remboursement/litige/déficit concurrent, la destination,
le plafond brut 1 000 et le risque restant de l'ancien envoi. Le backend recalcule
les fonds propres de DIR-24 et les fonds globaux après toutes les réservations.
Il exige une autre commande, la même destination Wave approuvée, le même compte,
une seule ancienne demande incertaine et aucun incident de clôture contradictoire.

Le grant est borné dans le temps, enregistré immuablement et consommé par **une
seule nouvelle intention** dans la transaction de réservation. Son utilisation
concurrente est protégée par le verrou boutique et une contrainte UNIQUE. La nouvelle
intention reçoit une nouvelle clé fournisseur persistée. Elle ne reprend jamais
la clé historique. Après consommation, une troisième intention non résolue est
refusée. Une autorisation expirée interdit également le dispatch après réservation.

Une preuve authentifiée tardive concernant un payout NON_EXECUTE ouvre un incident
immuable, conserve sa clôture et suspend les nouveaux retraits de la boutique ;
elle exige une investigation séparée. Elle n'efface pas l'audit et ne déclenche
aucun paiement de compensation. Une ancienne intention restée incertaine conserve
quant à elle sa réserve jusqu'à vérification normale.

## Procédure future : un seul retrait Wave, 1 000 XOF brut

1. Revue et fusion de la nouvelle PR, tests verts ; déploiement autorisé de la
   migration 060 et du backend. Ne pas utiliser l'ancien endpoint recover-pilot.
   Désactiver `SASPAY_PAYOUT_RECOVERY_ENABLED` dans la configuration du déploiement
   (le fichier actuellement observé le contient encore à true ; il n'a pas été modifié).
2. Conserver les POST LIVE désactivés pendant préparation. Refaire les GET et
   le contrôle des réservations juste avant l'opération. Obtenir l'accord distinct
   pour la décision administrative et le transfert réel. Aucun des deux n'est
   couvert par les simulations de cette PR.
3. Choisir **un seul** chemin : clôture justifiée puis nouveau payout de DIR-21,
   ou grant indépendant pour DIR-24 en conservant les 1 000 XOF réservés de DIR-21.
   Sans preuve nouvelle, utiliser seulement la deuxième option après autorisation.
4. Paramètres du second pilote : organisation ci-dessus ; montant maximum 1 000 ;
   fournisseur SASPAY ; réseau `wave_sn` ;
   `SASPAY_PAYOUT_LIVE_PILOT_DIRECT_ORDER_ID=f1d691db-b561-4412-8149-3314b5f6955b`.
   Destination observée : `a1cc14e7-adef-4fc1-9583-440f47aa5bec`, WAVE ACTIVE,
   version 0. Revérifier sa révision et le titulaire hors logs avant accord.
   Les autres portes d'autorisation marketplace/bénéficiaire et pilote restent
   obligatoires ; ne jamais les contourner pour réussir un test.
5. Provisionner le dossier approuvé et effectuer la seule opération administrative
   autorisée. Désactiver ensuite l'API administrative. Le grant durable reste
   utilisable jusqu'à son expiration par le parcours normal, une seule fois.
6. Après autorisation du transfert :
   `GET /api/organizations/{org}/direct-orders/{DIR24_ID}/merchant-payout-quote?destinationId={destinationId}&amount=1000`.
   Faire accepter le devis retourné (`quoteId`, frais, net, expiration). **1 000 XOF
   désigne le brut réservé/débité** : avec 37 XOF de frais DEDUCTED, Wave reçoit 963,
   pas 1 000. Ne jamais réutiliser un devis expiré ni présumer que les frais sont inchangés.
7. Choisir une nouvelle clé HTTP d'idempotence pour cette nouvelle intention et la
   conserver pour éviter un double clic. Envoyer **une fois** :
   `POST /api/organizations/{org}/direct-orders/{DIR24_ID}/merchant-payouts`, avec
   `Idempotency-Key`, `destinationId`, `feeQuoteId=quoteId` et `confirmed=true`.
   Le backend crée et persiste sa propre nouvelle clé fournisseur avant le POST SasPay.
8. Vérifier uniquement par rapprochement GET après ACK, avec preuves indépendantes
   marchand/devise/brut/frais/net/bénéficiaire. Une réponse d'initialisation n'est
   pas un succès financier. En cas de timeout ou d'ambiguïté, conserver la réserve,
   arrêter les mutations et demander la corrélation fournisseur. Ne pas recréer une
   intention, ne pas réutiliser le grant et ne pas rappeler recover-pilot.
9. Confirmer le résultat dans l'historique, les fonds et la réception Wave. Désactiver
   les portes d'initiation du pilote après l'essai. Conserver les GET de vérification
   nécessaires ; aucune suppression d'historique ni remise à zéro de quota.

Le présent document est une procédure préparée, **pas une autorisation d'exécution**.

## Dernière revue du 16 novembre 2026

Le contrôle GET à 03:18 UTC confirme un portefeuille marchand SN/XOF unique,
non gelé, disponible **2 000 XOF**, trouvé après lecture de 27 portefeuilles sur
deux pages. Les deux encaissements restent SUCCESS, net marchand 1 000 chacun.
Wave `wave_sn` annonce payout.available=true ; à 1 000 brut, les frais calculés
sont **37 XOF** et le net attendu **963 XOF** avec DEDUCTED explicite. Le mode
tarifaire publié par défaut est ADD_ON : le POST devra demander DEDUCTED, et le
rapprochement devra prouver le débit brut et le net. Aucun devis Groupia n'a été
créé : le devis serveur sera valable **180 secondes**, après le grant autorisé.
La liste des retraits est toujours vide, sans attestation de non-exécution.

Le contrôle PostgreSQL READ ONLY confirme l'ancien payout à la version 5, son
état incertain inchangé, aucune intention DIR-24 et aucune migration 060 appliquée.
La destination Wave existante est ACTIVE, version 0, numéro masqué **\*\*\*8577** ;
son identité et son numéro correspondent au snapshot de l'ancien payout.

Le fichier `.env` contient maintenant `SASPAY_PAYOUT_RECOVERY_ENABLED=false`, mais
il a été modifié après le démarrage de la JVM observée. **Ce constat ne prouve pas
la valeur effectivement injectée dans le bean actif.** L'instance ne publie pas
de diagnostic de cette propriété. Au déploiement, fixer explicitement
`--groupia.settlement.saspay-payout-recovery.enabled=false` en argument de
démarrage, contrôler le processus neuf et conserver le journal de démarrage.
Ne jamais tester la désactivation en appelant `recover-pilot` sur l'ancien payout.

La revue a identifié une restriction incorrecte : les GET de rapprochement
étaient soumis à la commande pilote courante et à l'ouverture des envois. Le
correctif permet de vérifier une intention persistée de la boutique et du compte
autorisés après ciblage de DIR-24 ou fermeture des envois. Il conserve les contrôles
de marchand, devise, montant, référence, frais et bénéficiaire. La clé et les
autorisations fournisseur restent nécessaires. Les POST gardent toutes leurs
restrictions. Sans ID fournisseur, DIR-21 demeure en revue : cette correction
n'invente ni référence ni preuve, et ne déclenche aucune relance.

Ordre de déploiement préparé :

1. Attendre la CI du **nouveau SHA contenant ce correctif**, puis revoir/fusionner
   ce SHA exact. Les jobs verts de `318889fd` ne valident pas le correctif ajouté.
2. Sauvegarder la base et la configuration, archiver le binaire précédent et
   arrêter proprement l'ancien processus avant le déploiement autorisé. Ne pas
   compiler dans les classes chargées par le serveur actif. Utiliser le JAR
   vérifié, sans DevTools, et laisser Liquibase appliquer 060 une seule fois.
3. Démarrer en préparation avec les arguments explicites ci-dessous. Ils
   désactivent les envois et l'administration malgré toute ancienne valeur `.env` :

   ```text
   --groupia.settlement.saspay-payout-recovery.enabled=false
   --groupia.settlement.saspay-payout-live-pilot.enabled=false
   --groupia.settlement.payout-administration.enabled=false
   --groupia.settlement.saspay-payout-live-pilot.direct-order-id=f1d691db-b561-4412-8149-3314b5f6955b
   ```

4. Contrôler la santé, la version déployée, les arguments du nouveau PID, les
   changements Liquibase et les réservations. Garder les GET de rapprochement
   disponibles ; ne modifier aucun statut historique à la main.
5. Après accord sur l'administration, revoir le dossier INDEPENDENT_PILOT avec
   les lectures actualisées, dates et empreintes. Enregistrer une seule autorisation
   via l'API métier. Cette étape exige temporairement les portes du pilote ouvertes,
   mais ne doit appeler aucun endpoint de demande de retrait. Fermer ensuite l'API
   administrative et obtenir un devis frais, dans une fenêtre opérateur contrôlée.
6. Obtenir l'accord explicite sur **1 000 brut, frais du devis courant, net et
   destination Wave enregistrée**, puis exécuter une seule demande avec une nouvelle
   clé. Si le devis expire pendant l'accord, refaire un devis et revalider ses
   conditions avant l'envoi. Réconcilier Groupia/SasPay/Wave comme décrit plus haut.

La capacité PAYOUT/BOTH et l'IP autorisée ont été rapportées par l'opérateur ;
les ressources GET documentées ne fournissent pas de relecture de ces permissions.
Leur validité pour la clé et l'IP effectives du déploiement reste un contrôle du
dashboard ou du support, à conserver dans le dossier. Aucun POST exploratoire
n'est permis pour tester ces droits. Aucun dossier de non-exécution n'a été créé.

## Recette

Tests PostgreSQL Testcontainers et fournisseur synthétique dans
`PayoutAdministrationIntegrationTests`, politique pilote dans
`SaspayPayoutPilotPolicyTests`, transport de diagnostic GET dans
`tools/test_saspay_audit_payouts.py`. Les résultats définitifs sont portés dans la PR,
sur son SHA final ; un test en cours ou interrompu n'est pas présenté comme réussi.
