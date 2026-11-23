# Finalisation financière — 23 novembre 2026

Base auditée : `c43ae2d6` (PR #105 incluse). Branche : `codex/saspay-financial-finalization`.
Ce rapport distingue le code, les simulations et les preuves LIVE. Aucune opération financière réelle n'est autorisée par cette mission.

## Lot A — matrice issue de l'inspection

| Fonctionnalité | État existant | Problème réel / limite | Correction prévue | Validation nécessaire |
| --- | --- | --- | --- | --- |
| Encaissement hosted | Existant | Session distincte de transaction ; POST sans garantie d'idempotence ; récupération bornée à 3 pages | Conserver les vérifications et interdire tout POST de récupération | Contrats HTTP, timeout, reprise |
| Récupération legacy | Partielle | Poller exclut devis + CREATED sans ID et PENDING avec transaction seule ; ordre ancien peut affamer les suivants | Sélection exhaustive des états actifs, bail DB, temporisation, diagnostic explicite sans ID | PostgreSQL, concurrence, crash, legacy GET |
| Authentification et parsing webhook | Existant / partiel | HMAC, reçus, retry et diagnostics de forme existent ; SaspayException ne fournit pas le code de parsing typé | Codes fermés, JSON ambigu rejeté, diagnostic sans valeur personnelle | Contrats, déduplication, faux succès |
| Corrélation tardive webhook | Partielle | Corrélation exacte par compte/transaction ; cinq essais puis revue | Préserver les dossiers clôturés ; documenter la revue après épuisement | Webhook avant référence, historique inchangé |
| Surveillance | Existante | Checkpoint, incidents et décisions append-only, empreintes matérielles PR #105 | Ne pas incorporer les compteurs techniques de récupération aux empreintes financières | Tests PR #103–105 |
| Solde / réserves | Existant | Compte source, org, priorité des dettes et réservations vérifiés | Réutiliser le registre et les verrous | Payout concurrent, fonds insuffisants |
| Devis de retrait | Partiel | Expiration à réservation mais pas immédiatement avant POST après précontrôles réseau | Contrôler expiration avant transmission | Horloge contrôlée, aucun POST sur devis expiré |
| Ancienne récupération pilote payout | Partielle | POST possible avec résultat antérieur inconnu, même clé et devis expiré | Retirer ce chemin de réémission ; conserver vérification GET et revue administrative avec preuves | Aucun appel provider, réserve inchangée |
| Annulations / remboursements | Existant avec limites | Dossier externe audité ; aucune API SasPay native de remboursement dans la copie OpenAPI ; remboursement partiel universel non implémenté | Conserver REFUND_DUE tant que remboursement externe non déclaré ; documenter les limites | Annulation, doublon, fonds déjà attribués |
| Abonnement | Existant, plan unique configuré | Facture, montant serveur, période et activation prouvée ; expiration peut permettre nouvelle facture malgré tentative incertaine | Bloquer remplacement tant que paiement précédent incertain/en cours | Expiration, double création, événement tardif |
| Plans multiples / changement de plan | Absent | Seul STANDARD_MONTHLY et tarif configurable existent ; aucune règle de prorata produit | Ne pas inventer de tarifs ni de prélèvement récurrent | Décision produit nécessaire |
| Isolation / événements | Existant | Permissions centralisées, verrous, contraintes uniques, preuves, Modulith persistant | Maintenir frontières et contrôles existants | ModularityTests, intégrations et autorisations |

Inspection : payment (adaptateur, client, transactions, webhooks, remboursements, modèles), settlement (soldes directs/groupages, réservations, provider, administration), subscription (facturation, activation, droits), ordering/directsales (annulations et événements), grouping, organization, notification et audit. Le package audit est essentiellement structurel : les preuves concrètes résident dans les registres de chaque module. Migrations 001–063, CI, suites Java, scripts de sécurité et configuration Playwright examinés.

Contrats : copie OpenAPI versionnée et documentation officielle consultée le 23 novembre : [webhooks](https://docs.saspay.me/api-reference/webhooks), [payout initialize](https://docs.saspay.me/api-reference/payouts/initialize). Les événements settlement ont un payload annoncé non stabilisé ; ils ne peuvent pas constituer une preuve de règlement. Aucun réseau supplémentaire n'est activé par cette mission.

## Données historiques et limites d'investigation

Les trois CREATED avec devis sans référence ne prouvent ni envoi ni absence d'envoi. L'expiration du devis ne prouve pas l'échec du paiement. Sans référence fournisseur fiable, conserver l'incertitude et obtenir des preuves opérateur/fournisseur. Aucun POST, rapprochement par montant seul ou mise à jour SQL corrective.

Le PENDING avec transaction seule doit être vérifié via GET legacy ; une correction du code ne constitue pas la vérification de son état LIVE. Les identifiants réels et les deux payloads du 17 novembre ne sont pas fournis dans la mission. Leur sens ne sera pas reconstruit arbitrairement. Les 14 UNKNOWN_REFERENCE clôturés, les 84 AUDIT_INCOMPLETE et toutes les décisions restent conservés.

## Validation

Résultats et couverture à compléter après les lots d'implémentation. Aucun succès LIVE supplémentaire revendiqué. Les deux retraits de 1 000 XOF (963 nets / 37 frais) sont des preuves rapportées par l'utilisateur, limitées au parcours Wave concerné.
