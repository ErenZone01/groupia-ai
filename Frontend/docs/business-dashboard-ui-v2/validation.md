# Validation et coût du dashboard

## Poids du build production

Mesure locale sur la même installation Node/Angular, avant et après la modification. Les valeurs `kB` ci-dessous sont celles du rapport Angular (base 1 000).

| Mesure | Avant | Après | Écart |
|---|---:|---:|---:|
| Bundle initial, brut (rapport Angular) | 317,32 kB | 317,39 kB | +0,07 kB |
| Bundle initial, transfert estimé Angular | 89,19 kB | 89,11 kB | −0,08 kB |
| Chunk lazy dashboard, brut | 17,33 kB | 40,74 kB | +23,41 kB |
| Chunk lazy dashboard, transfert estimé Angular | 4,59 kB | 9,89 kB | +5,30 kB |
| Tous les fichiers JavaScript, brut | 879 430 octets | 903 013 octets | +23 583 octets |
| Tous les fichiers JavaScript, gzip séparé | 267 039 octets | 273 178 octets | +6 139 octets |
| Dépendances ajoutées | — | 0 | 0 |

Le gzip est calculé par `node:zlib.gzipSync` sur chacun des fichiers `.js` du répertoire `dist/groupia/browser`, puis additionné. Il est distinct de l’estimation de transfert Angular. Les résultats bruts sont conservés dans `baseline-size.json` et `final-size.json`. Les images de revue ne sont pas des assets de l’application et ne sont pas incluses dans le build.

Le graphique est chargé avec la feature dashboard. Il n’ajoute aucune bibliothèque graphique, aucun appel par point et aucune lecture de toutes les commandes. Le coût réseau métier passe de quatre lectures opérationnelles à ces quatre lectures plus un agrégat analytique. Un changement de période ne provoque qu’un seul nouvel appel.

## Vérifications reproductibles

Depuis `Frontend/` :

```powershell
npm ci
npm audit --audit-level=high
npm run lint
npm test
npm run build
npm run test:e2e
```

Tests ciblés utiles :

```powershell
npx playwright test e2e/business-dashboard.spec.ts
npx playwright test e2e/business-dashboard.visual.spec.ts
```

La suite unitaire compte 417 tests, dont 24 nouveaux cas analytics. La suite E2E définit 252 scénarios : 214 existants et 38 nouveaux (22 interactions/régressions et 16 captures après refonte). Les 16 captures avant refonte ont été exécutées séparément sur le dashboard précédent. Les résultats des exécutions complètes et de la CI sont consignés dans la description de la PR pour identifier le SHA réellement validé.

Couverture nouvelle :

- Contrat réel généré, zéro, moyenne absente, variations positives/négatives/indéfinies, borne entière sûre et refus d’un montant imprécis.
- Périodes 3/6/12, valeurs des deux séries et du tableau, mois sélectionné initialement cohérent, absence de double requête opérationnelle.
- Annulation des requêtes et masquage immédiat au changement de boutique/période/session ; erreurs, retry, expiration, déconnexion et réponse tardive.
- Clavier (Home/End/flèches, select et tableau dépliable), tap tactile, cibles de 44 px, thèmes, contraste des séries, reduced-motion.
- Absence de débordement aux sept largeurs demandées, avec texte à 200 % et tableau ouvert. Les diagnostics incluent les éléments responsables d’un éventuel dépassement.
- Captures Playwright réelles des états populated/empty/loading/error, mobile et desktop, clair et sombre ; contrôle visuel des alignements, couleurs, axes et cohérence du sélecteur.

Depuis la racine du dépôt :

```powershell
python tools/check_migrations.py
python -m unittest discover -s tools -p "test_*.py"
python tools/scan_secrets.py
git diff --check
```

Aucun changement backend : les commandes Maven ne sont pas relancées localement pour cette tranche UI. Le workflow normal de la PR conserve son job backend, dont les tests PostgreSQL et Spring Modulith. Les avertissements Angular NG8107 préexistants de `ShippingOrderEditor` ne sont pas modifiés ; aucun nouveau dépassement de budget de composant n’est introduit.
