# Registre fournisseur et simulations marketplace

État du lot du 8 novembre 2026, PR #86. Aucun transfert réel SasPay n'est implémenté ni activable par ce lot.

## Contrat et limites externes

Sources : [OpenAPI officiel](https://docs.saspay.me/api-reference/openapi.json), [index officiel](https://docs.saspay.me/llms.txt), copie et empreinte dans README.md. La dernière comparaison du 7 novembre est inchangée. La relecture web du 8 novembre n'a pas abouti ; aucune nouvelle capacité fournisseur n'est supposée.

`POST /payouts/initialize/` et `GET /payouts/{payout_id}/verify/` sont documentés. Une autorisation de compte et une IP whitelistée sont requises. Le champ marchand ne permet pas, avec une clé API, de présumer une affectation à des sous-marchands : l'identité dépend du compte authentifié. Aucun contrat de comptes vendeuses connectés, split marketplace ou remboursement de paiement n'a été trouvé dans le snapshot. Ces fonctions ne sont pas inventées.

Le contrat ne démontre pas encore un plafond de débit verrouillé après devis, ni la conservation du net bénéficiaire lors d'un payout financé avec tous ses frais. Les frais d'encaissement ne sont pas une preuve du coût du reversement. L'activation réelle reste bloquée jusqu'à confirmation écrite des permissions marketplace, de la tarification, des arrondis, de la provenance des fonds et du net effectivement reçu.

## Encaissements

`provider_collections` est un registre immuable alimenté par l'événement Modulith persistant `PaymentObligationSettled`. Les relectures sont idempotentes, un changement d'une preuve déjà enregistrée est refusé. Le rattrapage paginé `POST /provider-collections/refresh` utilise uniquement les obligations internes réglées ; il n'appelle aucun fournisseur. La page `GET /provider-collections` exige VIEW_PAYMENTS et relit les risques courants avant d'afficher VERIFIED ou REVIEW_REQUIRED.

Chaque entrée conserve organisation, cible (ORDER ou DIRECT_ORDER), finalité, obligation, paiement, fournisseur, compte source, transaction, devise, principal, montant attribué à la vendeuse, ancienne commission éventuelle, frais cliente et total débité lorsqu'ils sont vérifiables. Les abonnements sont exclus. Les nouvelles commandes gardent une commission nulle ; les anciennes valeurs restent historiques. Les références de compte exposées sont des empreintes, jamais des clés ou des coordonnées clientes.

Les frais SasPay proviennent du devis attaché au paiement effectivement réglé, dont le net, les frais et le débit ont déjà été vérifiés par l'adaptateur. On ne prend pas le devis de la dernière tentative. Les frais Bictorys absents des preuves restent null et sont affichés « Non documentés ».

Ce registre ne constitue pas un deuxième solde disponible. Il ne soustrait pas des versements déjà comptabilisés ailleurs. L'ancien parcours Bictorys reste en place ; il ne peut désormais financer un payout qu'avec le fournisseur ET le compte source exacts de son adaptateur. SasPay reste exclu de ce parcours, même après désactivation de son encaissement.

## Simulations de reversement et remboursement

Ces opérations sont un protocole interne, pas des endpoints SasPay. Aucun client HTTP, clé fournisseur ou numéro bénéficiaire n'est utilisé par `LocalMarketplaceSimulator`. Le mode SIMULATION est imposé en base et dans les réponses. Les tables de simulation ne modifient ni les commandes, ni les soldes réels, ni les tables des payouts/remboursements existants.

Le serveur calcule le devis. Un payout simulé reçoit l'intégralité du montant attribué à la vendeuse. Un remboursement simulé restitue le principal encaissé ; il ne prétend pas restituer les frais pay-in, non documentés par le contrat. Les frais fictifs sont financés par un budget fictif distinct de Groupia. Si ce budget est insuffisant, la réservation est refusée ; les frais ne réduisent jamais le net.

Les réservations sont isolées par organisation et encaissement, avec clé d'idempotence persistante. Une même source ne peut financer simultanément un reversement et un remboursement simulés. Une opération incertaine conserve sa réservation. Un échec vérifié la libère. Les preuves doivent correspondre à l'opération, à la cible, au fournisseur/compte, à la devise, au net, au débit total et à la référence ; l'initiation seule ne suffit pas. Une reprise consulte d'abord la preuve durable. L'adaptateur réutilise l'identifiant de la même opération, et les états terminaux ne régressent pas.

L'appel à l'adaptateur est séparé des transactions métier. Son stockage local simule la persistance d'un prestataire indépendant. EXECUTE_PAYOUTS est requis pour une simulation de versement, MANAGE_REFUNDS pour un remboursement ; VIEW_PAYMENTS pour l'historique. Le navigateur conserve une clé par session, boutique, encaissement, type et devis, sans secret. Un changement de session ou boutique invalide les réponses tardives.

Configuration Spring (désactivée par défaut) :

| Variable | Défaut | Sens |
|---|---|---|
| GROUPIA_MARKETPLACE_SIMULATION_ENABLED | false | Autorise uniquement les opérations fictives |
| GROUPIA_MARKETPLACE_SIMULATION_FEE_XOF | 200 | Frais fictifs entiers, sans lien avec un tarif SasPay |
| GROUPIA_MARKETPLACE_SIMULATION_COMPANY_BUDGET_XOF | 100000 | Budget fictif de frais, par boutique |

Même avec ces variables activées, `realOperationsEnabled` reste false. Les opérations sont visibles dans `/app/encaissements-fournisseurs`, accessible depuis les versements. Les montants fictifs ne constituent pas une validation du fournisseur ou une promesse tarifaire.

## Migrations et suites

047 ajoute le registre immuable ; 048 ajoute les intentions, réservations, reçus et événements simulés. Ces migrations sont additives et conservent l'historique financier. Les 22 migrations gelées ne sont pas modifiées.

Tests ciblés : PostgreSQL, idempotence, comptes/fournisseurs distincts, reprise après coupure, budget séparé, concurrence, preuves altérées, permissions, immutabilité, encaissements SasPay sous stub HTTP et non-régression Bictorys. Tests Angular et E2E pour devis/confirmation/reprise des deux types de simulation. Les résultats sont consignés dans FOLLOWUP.md après exécution ; la validation globale finale reste nécessaire.

## Suite métier encore nécessaire

L'audit a confirmé que l'annulation d'une campagne n'annule pas encore ses commandes payées et ne crée pas de dossier de remboursement. Ce parcours est le lot suivant : il doit conserver le règlement original, bloquer la remise, isoler les montants déjà restitués, gérer les paiements encore incertains et distinguer remboursement externe attesté, simulation et preuve fournisseur. Aucun seuil minimum d'objectif de campagne n'est actuellement défini dans le modèle ; un seuil financier ou de quantité ne doit pas être inventé implicitement.
