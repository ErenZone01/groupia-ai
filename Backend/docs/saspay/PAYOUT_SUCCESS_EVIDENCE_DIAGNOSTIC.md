# Incident 2026-11-17 : retrait LIVE reçu sur Wave, Groupia REVIEW_REQUIRED

Pour le nouveau retrait DIR-24, SasPay a enregistré une transaction GET
\`489b02d1-fe47-4e8a-a65c-f62e57d15fa0\` et le vendeur confirme
963 XOF crédités sur Wave pour 1000 XOF brut et 37 XOF de frais.
Groupia a conservé \`REVIEW_REQUIRED / PAYOUT_PROVIDER_UNCERTAIN\`
après un \`GET /transactions/{id}/\` HTTP 200 rejeté avec
\`SASPAY_PAYOUT_EVIDENCE_MISMATCH\`.

Le test actuel exige six corrélations exactes sur chaque GET :
transaction ID, marchand, type RETRAIT, flow OUTBOUND, currency XOF,
fee_charge_mode DEDUCTED. Une erreur indique une divergence
dans l'une de ces valeurs mais le log ne dit pas laquelle.

## Diagnostic sans effet financier

Sur une machine disposant de la clé LIVE du marchand, lancer :

\`\`\`powershell
python tools/saspay_inspect_payout.py --env-file Backend/.env --payout-id 489b02d1-fe47-4e8a-a65c-f62e57d15fa0 --expected-amount-xof 1000 --expected-fee-xof 37
\`\`\`

L'outil :
- effectue **exclusivement deux GET HTTPS**, avec UUID validé, sans redirection ;
- inspecte uniquement la réponse de \`/payouts/{id}/verify/\` et
  \`/transactions/{id}/\` et affiche les contrôles OK/ECHEC ;
- ne publie ni clés, ni identifiants marchand, ni téléphone,
  ni référence métier, ni réponse brute ;
- ne touche jamais PostgreSQL, n'initie aucune transaction et
  ne déduit jamais lui-même que le paiement est confirmé.

Partager les lignes VERIFY/DETAILS sans valeurs sensibles afin d'identifier
l'écart de contrat fournisseur. **Ne pas remplacer les vérifications par
un simple statut SUCCESS** : conserver les contrôles croisés d'identité,
montant, frais, réseau et destination.

Le webhook SasPay reçu à 09:15:56 puis 09:16:18 montre un format
\`{event, data}\`, mais son \`data.type\` exact n'est pas exposé par
le log sécurisé. Le routage doit être revu séparément sur preuve
de la valeur du type, sans faire entrer des sorties dans
l'allocation des encaissements. Aucun webhook de sortie ne doit à lui seul
déclencher \`SUCCEEDED\` sans rapprochement GET complet.

**Ne pas rejouer le POST : le paiement Wave a déjà été reçu.**
Le payout DIR-21 reste distinct et réservé.

## Résultat LIVE du diagnostic et correctif de validation

Le 17 novembre 2026, les deux GET ont confirmé `SUCCESS`, avec
`transaction_type=TRANSFERT`, `flow_direction=OUTBOUND`,
`fee_charge_mode=DEDUCTED`, et les contrôles d'identité et de montant
tous OK (1000 XOF brut, 37 de frais, 963 net). Le validateur historique
exigeait `RETRAIT`, d'où `SASPAY_PAYOUT_EVIDENCE_MISMATCH`.

La correction accepte uniquement `RETRAIT` ou `TRANSFERT` côté
**sorties SasPay**, avec cohérence du type entre les deux GET, montant,
frais, devise, mode, compte, identifiant, réseau et bénéficiaire
toujours stricts. `PAIEMENT`, `RECHARGE` et `INBOUND` restent refusés.

Les notifications de type `TRANSFERT` sont maintenant classées parmi
les sorties, mais elles n'autorisent JAMAIS une confirmation directe :
elles déclenchent seulement un GET de rapprochement lié à un identifiant
déjà enregistré dans Groupia. Le type précis des anciens webhooks
n'étant pas loggé, vérifier la disparition des warnings après déploiement.

## Exception de sécurité du frontend, 18 novembre 2026

Le job frontend de la PR #92 était bloqué sur
\`npm audit --audit-level=high\`. La vulnérabilité
\`GHSA-fc5q-39xc-vjpf\` (ReDoS dans picomatch jusqu'à 4.0.7)
affecte indirectement des outils **de développement/build**
Angular/Vite/ESLint et n'a pas de version corrigée dans l'avis
publié. La vulnérabilité \`GHSA-g3hv-75wg-m249\` de
\`minimatch\` est modérée et une version corrigée existe ;
elle doit faire l'objet d'une mise à jour de maintenance distincte.

La CI exécute désormais l'outil \`tools/check_frontend_audit.py\`,
qui réalise *deux audits npm réels* :

1. Audit des dépendances de production avec tolérance zéro sur
   toute vulnérabilité haute ou critique.
2. Audit complet, avec échec pour tout nouveau problème haut/critique.
   La **seule** exception temporaire possible est l'avis exact
   \`GHSA-fc5q-39xc-vjpf\` (racine picomatch) et ses dépendants
   affectés **uniquement par cet avis**, à condition qu'aucun
   correctif ne soit publié et que la production reste saine.

Les exceptions sont affichées dans les logs CI. Un correctif
disponible rend automatiquement le job rouge pour exiger sa mise
à jour ; les audits JSON malformés ou indisponibles sont bloquants.
Aucun \`npm audit --force\`, changement de version majeur à
l'aveugle ou désactivation générique du scan n'est autorisé.

Cette exception est une **acceptation de risque résiduel** pour
les outils exécutés sur les fichiers du dépôt : éviter de
compiler/tester des fichiers glob malveillants et suivre l'avis
amont jusqu'à publication d'un correctif. Retirer l'exception
lorsqu'un correctif vérifié est disponible.
