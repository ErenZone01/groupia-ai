# SasPay — contrôle manuel en lecture seule avant activation de la surveillance

## Périmètre

Ce lot ajoute un contrôle manuel **GET uniquement**, réservé aux opérateurs
Groupia autorisés sur la console financière. Il lit chez SasPay :

1. `GET /merchant-balances/?page=1&page_size=100` : soldes marchands, compte
   SasPay attendu, wallet(s) Sénégal/XOF et éventuel gel.
2. `GET /transactions/?merchant=...&created_at__gte=...&created_at__lte=...&page_size=100` :
   **uniquement la première page** des dernières 48 h (jusqu'à 5 min avant
   le contrôle), validée contre le compte marchand, la devise, le sens
   et le statut.

Aucune donnée personnelle de transaction, aucun identifiant de bénéficiaire
et aucune clé fournisseur n'est retourné à l'interface. Seuls un nombre
d'enregistrements et des montants de portefeuille marchand sont communiqués
à un opérateur explicitement autorisé.

**Important** : les soldes et une page d'historique ne prouvent pas que chaque
vendeuse dispose du montant correspondant, ni que les engagements marchands
sont intégralement couverts. Ce contrôle n'autorise **aucun décaissement**,
règlement, remboursement, déblocage de réserve ni changement de statut
d'une commande.

## Activation après revue et fusion

Ne pas partager la configuration ou les secrets en conversation, dans les
tickets GitHub ou dans les journaux.

1. Garder les clés LIVE/checkout/payout et limites actuelles **inchangées**.
2. Pour accéder à la console, définir l'activation et l'UUID d'un **compte
   Groupia actif** faisant partie des opérateurs, côté backend :

   ```dotenv
   SASPAY_INCIDENT_CONSOLE_ENABLED=true
   SASPAY_INCIDENT_OPERATOR_IDS=<UUID opérateur Groupia>
   SASPAY_HISTORY_AUDIT_ENABLED=false
   SASPAY_INCIDENT_MONITOR_ENABLED=false
   ```

   `SASPAY_ENABLED` doit déjà être configuré pour le bon environnement
   SasPay. Les `<...>` sont des exemples de documentation, pas des valeurs
   à recopier telles quelles. **Aucun besoin de déverrouiller le payout ou
   d'augmenter les plafonds.**

3. Redémarrer le backend, s'authentifier comme opérateur et ouvrir
   `/app/admin/incidents-saspay`.
4. Cliquer une fois sur **Tester la lecture SasPay**. Cette action appelle
   `GET /api/platform/saspay/incidents/provider-preflight` côté Groupia,
   puis deux GET côté fournisseur.
5. Un résultat `READ_ONLY_ACCESS_OK` signifie uniquement que la lecture
   d'une page des deux API est possible avec les données attendues.
   `INCOMPLETE_SAMPLE` interdit de considérer l'historique ou les
   soldes exhaustifs, `NO_SN_XOF_BALANCE` signale l'absence de portefeuille
   Sénégal/XOF sur la page, `WALLET_FROZEN` un portefeuille gelé.
   Un HTTP 401/403/429 ou une réponse invalide est un échec à investiguer :
   aucune tentative de POST ou de réessai automatique.
6. Un compte non opérateur doit continuer à voir `403` sur cette route
   et ne doit pas voir le lien de console.

## Après ce premier contrôle seulement

La surveillance continue existante exige une activation **séparée** et
une revue du contrat SasPay (quotas, permissions de lecture, exactitude
du mapping historique) :

```dotenv
SASPAY_HISTORY_AUDIT_ENABLED=true
SASPAY_INCIDENT_MONITOR_ENABLED=true
# Date de départ en UTC, optionnelle et utilisée à la toute première initialisation
SASPAY_INCIDENT_MONITOR_START_AT=
```

Le premier cycle peut ne couvrir que 24 h : le rattrapage progresse ensuite
par lots, avec un point de contrôle persistant. **Ne jamais modifier la
base directement pour faire avancer un checkpoint ou effacer une anomalie.**
Vérifier ensuite dans la console : progression réelle du checkpoint, absence
de `AUDIT_INCOMPLETE`, traitement des écarts et cohérence des informations
avec l'historique fournisseur.

## Restant explicitement hors de cette PR

- Rapprochement **inverse exhaustif** des transactions internes absentes de
  l'historique SasPay (dates de création fournisseur différentes des dates
  de confirmation Groupia).
- Rapprochement comptable de bout en bout entre wallet marchand, remboursements,
  encaissements, réserves et **toutes les dettes vendeuses** par organisation.
- Confirmation juridique des fonds et autorisation contractuelle marketplace,
  politiques d'accès/MFA, alerting externe et reprise d'incidents avec une
  approbation indépendante.

Ces chantiers ne sont pas rendus facultatifs par un écran de diagnostic vert.
