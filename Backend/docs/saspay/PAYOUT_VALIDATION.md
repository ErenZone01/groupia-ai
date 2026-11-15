# Validation des corrections payout

Les résultats définitifs doivent être lus sur le SHA de la PR, dans les trois jobs
`security`, `backend` et `frontend`. Une exécution interrompue, ignorée ou encore en
cours n'est pas un succès. La PR ne doit pas être fusionnée avant leur réussite.

## Matrice de couverture

| Risque | Tests |
| --- | --- |
| 201 ACK, payload, numéro, DEDUCTED, clé persistante | `SaspayMerchantPayoutProviderTests` |
| 200/202 inattendus, 400/401/403/409/422/429/5xx, GET et POST | `SaspayMerchantPayoutProviderTests` |
| JSON/enveloppe invalides, référence absente, code non autorisé, timeout avant/après POST | `SaspayMerchantPayoutProviderTests` |
| Frais changés, portefeuille gelé/insuffisant, 27 wallets sur deux pages, pagination hostile | `SaspayMerchantPayoutProviderTests` |
| Preuve indépendante, mauvais marchand/net/réseau/numéro | `SaspayMerchantPayoutProviderTests` |
| Logs sans secrets/PII, certitude, worker incapable d'initier | `MerchantPayoutFailureTests` |
| Crash pendant récupération, refus du second claim, devis différent avec même clé | `SaspayPayoutRecoveryPolicyTests` |
| Signature payout, doublons, désordre, aucune allocation pay-in | `SaspayIntegrationTests` |
| Réservation de travail durable, concurrence, compte différent, notification pendant GET | `UnifiedMerchantPayoutIntegrationTests` |
| Dette groupage compensée avant retrait direct, fonds non réglés, remboursements | `UnifiedMerchantPayoutIntegrationTests`, `MerchantSettlementIntegrationTests` |
| Double clic/rejeu, timeout, concurrence, ID fournisseur réutilisé, autre boutique | `MerchantSettlementIntegrationTests`, `MerchantBictorysIntegrationTests`, tests frontend settlements |
| Événements durables, rejeux, échec du consommateur, succès immuable | `MerchantSettlementIntegrationTests`, `MerchantBictorysIntegrationTests` |
| Frontières des modules | `ModularityTests` |
| Migrations historiques et nouvelle migration additive | suites `*Migration*Tests`, `tools/check_migrations.py` |

Les tests de moteur commun et ceux du transport HTTP SasPay sont complémentaires.
Les simulations ne constituent pas une preuve de disponibilité ou d'autorisation LIVE.

## Commandes de recette

Backend, dans `Backend` :

```powershell
.\mvnw.cmd spotless:apply
.\mvnw.cmd spotless:check
.\mvnw.cmd test
.\mvnw.cmd verify
```

Frontend, dans `Frontend` :

```powershell
npm ci
npm run lint
npm test
npm run build
npm run test:e2e
```

Outils, depuis la racine :

```powershell
python -m unittest discover -s tools -p "test_*.py"
python tools/check_migrations.py
python tools/scan_secrets.py
```

Sous Windows, le frontend local pouvait avoir un processus Sass verrouillant
`node_modules` : la recette a été lancée dans une copie temporaire de **la même
révision frontend**. Aucun fichier frontend n'est changé par ce correctif.
Les tests PostgreSQL utilisent uniquement des conteneurs éphémères. Les serveurs
HTTP sont locaux et les clés synthétiques ; aucun endpoint SasPay LIVE n'est appelé.
