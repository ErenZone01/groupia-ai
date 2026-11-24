# Recette métier et financière Groupia V1 — 24 novembre 2026

Base : `6727a5244ab19ec7975bcb92085f5b34c4c13980` (main, PR #107). PR unique : [#108](https://github.com/ErenZone01/groupia-ai/pull/108), non fusionnée. Dépôt initial propre ; branche `codex/groupia-final-business-financial-readiness`. Aucun déploiement, changement de flags LIVE, POST financier ou modification SQL de statut autorisé par cette recette.

## Audit initial avant corrections

| Fonctionnalité | État constaté | Problème / action | Validation nécessaire |
| --- | --- | --- | --- |
| Vente directe, catalogue, variantes, médias, checkout, remise et clôture | Implémenté | Réutiliser snapshots, verrous, suivi privé et preuve serveur | Suites directes, concurrence et tenant A/B |
| Stock physique fini | Absent : publication/disponibilité de variante seulement | Ne pas revendiquer de protection contre survente ; examiner un stock borné avant publication commerciale | Concurrence, réservation, abandon, annulation |
| Groupage de bout en bout | Implémenté | Vérifier fournisseur, transport, distribution et dettes ; la collecte d'une campagne déjà ouverte reste autorisée après expiration, règle explicitement testée ; création/ouverture de nouvelles campagnes bloquée | Régression checkout + parcours existants |
| Abonnement manuel/J−7 | Implémenté en #107 | Rejouer aux bornes ; préserver paiements existants et accès aux engagements antérieurs | PostgreSQL, UI, deux boutiques |
| Paiements/retraits SasPay | Implémenté et pilotes LIVE rapportés | Conserver GET, devis, réserves et refus de rejeu de #106 | Contrats et tests complets, aucun nouveau LIVE |
| Historique fournisseur vers Groupia | Implémenté #100–105 | Lecture bornée, checkpoints, incidents conservés | Régressions de reprise et empreintes |
| Rapprochement inverse et global | Partiel | Références internes absentes des listes et ventilation wallet/obligations non consolidées | Lectures exactes, bornes, aucune preuve de solvabilité inventée |
| Annulations/remboursement externe | Implémenté, déclaration auditée | Vérifier allocation et autorisations ; preuve indépendante/double approbation opérationnelles restent à démontrer | Direct/groupage, doublon, succès tardif |
| Console financière | Implémenté | Préserver historique, pagination, fraîcheur et accès plateforme | Diagnostics locaux READ ONLY puis tests isolés |
| Script diagnostic payout | Défectueux | Deux blocs exécutables ; CLI utilise l'ancien contrat et URL LIVE même avec clé TEST | Exécuter réellement le point d'entrée sous transport simulé |
| Artefact public du pilote | Défectueux | Page administrative spécifique à une ancienne opération embarquée dans les assets publics | Retirer de la distribution V1 |
| Identité/autorisations | Implémenté, renforcement opérationnel partiel | Vérifier BOLA/BFLA ; MFA opérateur et double approbation ne sont pas acquis | Tenant A/B, permissions et revue externe |
| UI/UX globale | Hors périmètre | Corrections fonctionnelles uniquement ; ticket #96 conservé | Parcours E2E existants |

## Sources examinées

PR #86–95, #97, #99–107 (les numéros #96 et #98 sont des tickets), tickets #84/#96/#98, instructions Backend/AGENTS.md, graphe Modulith, modèles et migrations, configuration CI, rapport SasPay du 23 novembre et procédures de reversement. La #95 contient notamment une page opérateur temporaire et la duplication du diagnostic. Les procédures historiques retirées par #106 restent retirées.

Les réussites LIVE citées dans la mission sont des preuves rapportées par l'utilisateur : payin Wave direct de 1 000 XOF, retrait de 1 000 brut/37 frais/963 net, abonnement payé. Elles ne couvrent pas tous les réseaux, le groupage complet ni le lancement public.

## Corrections livrées dans cette branche

1. **Stock direct par offre/variante.** Un stock fini peut être configuré ; vide signifie sans limite, zéro signifie épuisé. Les commandes réservent leurs quantités dans la même transaction que leurs snapshots, sous le verrou de la boutique. Une deuxième commande concurrente sur la dernière unité reçoit `DIRECT_STOCK_UNAVAILABLE`. Le rejeu idempotent ne consomme rien de plus. Une version de stock distincte de la révision tarifaire empêche un écran commerçant périmé de réinjecter les unités qui viennent d'être réservées.
2. **Restitution contrôlée.** L'annulation impayée autorisée par le module paiement restitue une seule fois les unités engagées. Une commande payée, une tentative incertaine ou un remboursement déclaré n'entraîne pas une remise en stock automatique. Un retour physique doit être constaté avant un ajustement manuel. Un brouillon abandonné conserve sa réservation jusqu'à son annulation sûre par le commerçant ; aucune expiration temporelle ne prouve la non-exécution d'un paiement.
3. **Historique dans les deux sens.** Après parcours complet des pages fournisseur, comparaison avec les références internes capturées au début de la fenêtre. Les absences produisent `INTERNAL_REFERENCE_NOT_OBSERVED`. Un historique incomplet, un curseur invalide ou plus de 3 000 références internes empêche de déclarer le contrôle complet. L'empreinte inclut l'état financier local ; une absence inchangée ne rouvre pas à chaque passage un dossier déjà revu. Une clôture ne confirme toujours aucun règlement.
4. **Synthèse financière opérateur.** Nouveau GET `/api/platform/saspay/incidents/accounting`, avec la même autorisation opérateur explicite, lecture transactionnelle cohérente, réponse `no-store`, aucun POST fournisseur. Ventilation par organisation et par compte SasPay configuré : principal marchand, abonnements affectés, encaissements non affectés, retraits payés/réservés, frais, remboursements dus et déclarations externes. Les sources sont agrégées avant jointure pour éviter les multiplications de montants. Les déficits d'une boutique ne réduisent pas les fonds conservés pour une autre.
5. **Comparaison portefeuille explicite.** La console rapproche uniquement deux lectures distantes d'au plus une minute et refuse les soldes incomplets. Elle affiche un écart signé, une insuffisance potentielle et les limites de preuve. Les fonds en attente ne sont pas traités comme disponibles ; un portefeuille gelé ne fournit pas de conclusion de couverture mobilisable.
6. **Diagnostic payout réparable.** Suppression du premier programme dupliqué qui interceptait le CLI avant l'implémentation actuelle. Acceptation des deux types documentés `RETRAIT`/`TRANSFERT`, sélection du domaine sandbox pour une clé TEST, refus des préfixes inconnus. Test du véritable point d'entrée avec HTTP simulé, uniquement GET.
7. **Ancien artefact public retiré.** Suppression de la page HTML propre à une opération pilote et de ses actions administratives embarquées. L'historique Git conserve la trace ; la console authentifiée reste le point d'entrée opérateur.
8. **Configuration hors ligne.** Le contrôleur Python distingue désormais production multi-boutiques plafonnée et pilote mono-boutique. Modes mutuellement exclusifs, domaine/clé/profil cohérents, plafonds conservés. Il ne modifie aucune configuration et n'accorde aucune autorisation commerciale.
9. **Contrats et messages.** Régénération du contrat OpenAPI et des clients TypeScript ; noms de schémas uniques pour la synthèse comptable, les pages, reçus, contacts, lignes et états financiers directs afin d'éviter les collisions entre DTO. Retrait des mentions inexactes affirmant que tous les paiements directs étaient désactivés ; lien de disponibilité des fonds explicitement commun aux ventes directes et groupages.

## Migration et reprise du stock

Seule nouvelle migration : `065-direct-stock.sql`, ajoutée au master. Elle ajoute `stock_limit`, `stock_committed`, `stock_version` aux offres et un marqueur de réservation aux lignes de commande. Les offres existantes restent sans limite ; les anciennes lignes ne sont jamais présentées comme ayant réservé du stock. Les montants, statuts, références et preuves financières historiques ne sont pas réécrits.

Avant d'activer un stock fini, le commerçant renseigne **les unités encore vendables**, en excluant ses engagements historiques. Les réservations de nouvelles commandes sont déjà déduites du champ affiché. Le groupage demeure un parcours de précommande ; ce stock est celui de l'offre de vente directe, pas un entrepôt commun ou une allocation fournisseur inventée. Le test d'upgrade rejoue la migration, préserve les checksums et les données financières, et vérifie le refus d'un engagement supérieur au plafond.

## Calculs, portée et limites du rapprochement

Tous les montants sont en XOF ; les calculs backend utilisent `BigDecimal` et les preuves enregistrées. Le diagnostic est borné à 1 000 boutiques ; au-delà, il échoue explicitement au lieu de retourner un total tronqué.

- Flux nets connus = principaux des encaissements internes réussis − retraits réussis bruts − frais supplémentaires historiques de retrait `ADD_ON`. Les frais `DEDUCTED` sont déjà compris dans le brut et ne sont donc pas soustraits une deuxième fois. Les frais d'encaissement payés en plus par le client ne deviennent pas des revenus Groupia.
- Principal marchand conservé = somme, **par boutique**, de `max(principal marchand affecté − retraits réussis, 0)`. Les soldes négatifs sont des déficits distincts, jamais une couverture pour une autre boutique. Ce montant conservateur n'est pas le solde éligible au retrait : les règles de clôture, transport, distribution, dette et remboursement demeurent dans le module settlement.
- Les réservations de retrait sont présentées à part, sans les ajouter une seconde fois aux fonds conservés. Une réservation incertaine reste bloquée ; elle n'est pas soustraite en supposant que le fournisseur aurait déjà débité le wallet.
- Seule une facture `PAID` reliée à son paiement réussi devient un revenu d'abonnement affecté. Un encaissement réussi non encore affecté à une facture ou au registre marchand reste dans les montants à examiner. Ce contrôle rend visible un retard/échec de consommateur événementiel.
- Les remboursements externes restent des **déclarations auditées**, pas des remboursements confirmés par SasPay. Ils ne diminuent pas artificiellement le portefeuille. Les dossiers dont les sources couvrent plusieurs comptes sont comptés séparément, sans allocation arbitraire de leur montant.
- Écart wallet = disponible + en attente − flux nets connus. L'insuffisance potentielle compare le seul disponible aux fonds marchands conservés et non affectés. Le solde initial, les apports/retraits externes, les frais non documentés, les débits en cours, d'autres portefeuilles et d'éventuels remboursements financés hors SasPay empêchent de transformer ces calculs en attestation de solvabilité.
- La fenêtre inverse utilise la date d'initiation locale ; la liste fournisseur filtre sa date de création. Une différence de date, une rétention fournisseur ou une référence legacy mal formée exige une investigation. **Absence de liste ≠ non-exécution** ; aucun retry financier n'est débloqué par ce résultat.

La synthèse ne remplace ni le bilan marchand autorisé ni les GET indépendants de vérification transactionnelle. Aucun équilibre n'est forcé. Aucun funding administratif de frais n'est assimilé à une entrée réelle d'argent dans le wallet.

## Matrice finale des fonctionnalités

« Implémenté et testé » désigne ici une preuve logicielle, pas une qualification LIVE. Les résultats des suites globales figurent dans la section Validation.

| Périmètre | État | Preuve / limite |
| --- | --- | --- |
| Boutique, catalogue, variantes, médias, publication, panier public | Implémenté et testé | Catalog, ProductMedia, DirectStorefront et scénarios navigateur ; prix et disponibilité revalidés côté serveur |
| Stock direct fini, concurrence, rejeu, correction périmée, annulation sûre | Implémenté et testé | Nouvelle migration et tests PostgreSQL à deux boutiques ; pas de stock historique inventé |
| Commande directe, livraison/cartographie, retrait, suivi privé, préparation/remise/clôture | Implémenté et testé | Suites DirectStorefront, DirectFulfillment, DirectBictorys et scénarios direct-saspay ; le paiement dépend de ses flags et preuves |
| Stock initial, retour physique, traitement quotidien des paniers abandonnés | À valider manuellement | Stock réel à inventorier ; annulation sûre des brouillons, réserves incertaines conservées ; pas de réassort automatique sur remboursement |
| Plusieurs points de retrait / entrepôts / stock partagé avec le fournisseur | Hors périmètre V1 | Retrait existant à organiser avec la boutique ; pas de gestion multi-entrepôt revendiquée |
| Campagne, collecte multi-participants, fermeture, récapitulatif/commande fournisseur | Implémenté et testé | Grouping, Ordering, SupplierSummary et SupplierOrder ; aucun pseudo-groupage pour vendre directement |
| Approvisionnement, transport, frais finaux, réception, distribution et clôture | Implémenté et testé | CampaignLogistics, ShippingAssessment/Finance, Distribution, GroupingCompletion |
| Dettes/crédits groupage et disponibilité unifiée direct/groupage | Implémenté et testé | MerchantSettlement, UnifiedMerchantPayout, CreditResolution ; réserves et dettes bloquent les retraits selon les règles existantes |
| Encaissement Wave direct de 1 000 FCFA | Validé en conditions réelles | Preuve rapportée dans la mission ; aucun nouvel encaissement effectué ici |
| Retrait Wave 1 000 brut / 37 frais / 963 net | Validé en conditions réelles | Preuve rapportée, limitée à cette opération et à ce réseau |
| Encaissements/retraits Orange Money et cycle complet groupage LIVE | À valider manuellement | Contrats, stubs et tests logiciels ; qualification réelle distincte requise |
| Webhooks, GET indépendant, doublons, succès tardifs, incertitude, reprise | Implémenté et testé | Saspay contracts/integration/recovery ; aucune réémission automatique d'un POST incertain |
| Rapprochement bidirectionnel et ventilation des fonds | Implémenté et testé | Contrôles bornés, comparaison de sources, séparation abonnement/marchand, autorisation opérateur |
| Exhaustivité comptable et couverture réelle du wallet | Bloqué par une dépendance externe | Relevés exhaustifs, solde initial et mouvements externes à qualifier ; aucune preuve de solvabilité déduite du solde global |
| Annulation directe/groupage et remboursement externe total audité | Implémenté et testé | Gardes financières et idempotence préservées ; déclaration externe explicitement distincte d'une preuve SasPay |
| Supervision indépendante et double validation d'un remboursement réel | À valider manuellement | Processus d'exploitation et justificatifs requis ; pas de double approbation technique revendiquée |
| Remboursement partiel générique / API native SasPay | Hors périmètre V1 | Contrat et allocation insuffisants ; aucun faux endpoint créé |
| Essai, facture mensuelle configurée, activation serveur, renouvellement manuel J−7 | Implémenté et testé | Bornes J−7, concurrence, idempotence, expiration, paiement tardif et isolation dans SubscriptionIntegration/SaspayIntegration |
| Paiement d'abonnement SasPay | Validé en conditions réelles | Rapporté par l'utilisateur ; le revenu ne crédite pas les fonds marchands |
| Engagements antérieurs après expiration | Implémenté et testé | Nouvelle vente directe/création-ouverture de campagne bloquées ; campagne déjà ouverte et gestion des obligations existantes préservées selon la règle produit |
| Plusieurs forfaits, prorata, prélèvement récurrent | Hors périmètre V1 | Un seul tarif mensuel configuré, renouvellement volontaire |
| Isolation organisations, permissions, suivi client, données financières/statistiques | Implémenté et testé | Suites IdentityOrganization, Ordering, Payment, CustomerAccess, BusinessAnalytics et tests de stock A/B |
| Notifications et événements persistants | Implémenté et testé | Publications Modulith, consommateurs idempotents et tests de récupération existants ; panne notification ne vaut pas échec financier |
| Délivrabilité SMS/mail, sauvegarde/restauration et alertes réelles | À valider manuellement | Comptes externes, infrastructure et exploitation à qualifier avant exposition |
| Authentification renforcée opérateurs / MFA | À valider manuellement | Allowlist + identité active + session ; aucune MFA applicative ni certification ASVS revendiquée |
| Contrats commerciaux, KYC, autorisation marketplace et conformité financière | Bloqué par une dépendance externe | Validation fournisseur et conseil compétent hors du périmètre des tests logiciels |
| Refonte complète UI/UX | Hors périmètre V1 de cette mission | Ticket #96 ; les contrats fonctionnels et distinctions financières doivent être conservés |

## Diagnostic historique local — lecture seule

Lecture initiale le 24 novembre vers 03:18 UTC, dans `BEGIN READ ONLY` sur PostgreSQL local. Aucun UPDATE/DELETE de statut, aucune migration appliquée par cette mission à cette base.

- Trois paiements `CREATED` portent `SASPAY_LEGACY_REFERENCE_UNAVAILABLE` : toujours à investiguer, pas de relance.
- Aucun paiement SasPay `PENDING` observé ; deux `FAILED`, un `CANCELLED`, huit `EXPIRED`, quatre `SUCCEEDED`. La situation ancienne « un PENDING dont le GET indique FAILED » n'est donc plus présente dans cette lecture. Ce constat ne permet pas d'attribuer à lui seul chaque transition à une cause.
- Deux reçus `REVIEW_REQUIRED / WEBHOOK_PARSE_FAILED`, quatorze `REVIEW_REQUIRED / UNKNOWN_REFERENCE`, huit `IGNORED` (dont quatre erreurs d'authenticité), six `PROCESSED`. Ils restent conservés.
- 105 incidents au moment de la lecture, quatorze décisions `CLOSED` ; le total historique de 104 du rapport précédent n'a pas été réutilisé comme un invariant artificiel.
- Checkpoint `completed_until=2026-11-24T03:11:11.175182Z`, succès `03:16:11.705604Z`, aucun code d'échec. Queue de récupération : sept lignes, dernière fin de traitement `02:46:08.740127Z`.

Contrôle hors ligne du fichier `Backend/.env` : le profil `prod` n'y est pas déclaré. Le reste des paramètres de collecte passe lorsque ce profil est fourni explicitement au lanceur. Ce second contrôle est conditionnel ; il ne prouve pas le profil hérité par le processus existant. Le profil effectivement chargé doit être confirmé dans le journal de démarrage avant toute qualification LIVE. Aucun fichier ni processus utilisateur n'a été modifié pour satisfaire ce contrôle.

Les deux erreurs de parsing et les références inconnues ne sont pas clôturées pour faire disparaître des alertes. Une clôture administrative antérieure reste conservée et ne libère aucune réserve.

## Sécurité et limites de qualification

Référentiels consultés : [OWASP API Security Top 10 2023](https://api-security.owasp.org/editions/2023/en/0x11-t10/) et [OWASP ASVS 5.0.0](https://asvs.owasp.org/). Ils servent de guide de contrôle, pas de certificat.

| Axe | Contrôle et preuve |
| --- | --- |
| API1/API5 : autorisation objet/fonction | Organisation résolue côté serveur, permissions explicites, tests A/B et OWNER/MANAGER/OPERATOR/VIEWER ; données globales réservées à l'allowlist plateforme |
| API2 : authentification/session | JWT/session et identité active existants ; renforcement MFA opérateur restant à qualifier avant lancement public |
| API3 : propriétés exposées | Aucun statut financier modifiable depuis l'interface ; synthèse sans contact, téléphone, secret ni référence bancaire brute |
| API4/API6 : consommation et flux sensibles | Fenêtres/pages plafonnées, limite de références/boutiques, cooldown fournisseur, verrous, CAS, idempotence, budgets de retrait existants |
| API7/API10 : appels fournisseur | Hôte de pagination strict, environnements clé/domaine cohérents, GET indépendant, contrôles montant/devise/type/compte, aucun POST de récupération incertain |
| API8/API9 : configuration et inventaire | Artefact pilote public retiré, contrat OpenAPI remis à jour, flags conservés OFF par défaut, vérificateur hors ligne corrigé |
| ASVS : transactions et audit | Valeurs exactes, snapshots historiques, preuves immuables, consommateur événementiel idempotent, journal administratif distinct des preuves de règlement |

Un abus automatisé de réservation de stock reste un sujet d'exploitation : suivre les abandons, annuler uniquement après vérification sûre et conserver la protection/régulation HTTP existante. Une suppression de réserve fondée uniquement sur l'âge aurait un risque financier plus grave et n'est pas ajoutée ici. Les droits opérateur doivent être nominatifs, réduits et supervisés ; une authentification renforcée et une revue indépendante des actions sensibles restent des conditions de lancement public.

## Recette LIVE à exécuter par le responsable, après autorisation explicite

Aucune étape de cette section n'a été exécutée comme transaction réelle par l'agent. Ne pas transformer le protocole en script automatique.

1. **Préparer.** Valider contrats/KYC, compte marchand et réseaux, sauvegarde/restauration, domaine HTTPS, webhook, opérateurs autorisés et budget d'essai explicite. Revoir/fusionner/déployer séparément après la PR. Vérifier la migration sur une copie puis le nombre des incidents/décisions historiques. Arrêt si secret/environnement/compte incohérent ou historique modifié.
2. **Lecture initiale.** Consulter checkpoint et incidents, puis diagnostic SasPay et écritures Groupia à moins d'une minute. Conserver un relevé fournisseur de référence et les soldes par boutique. Examiner tout écart, fonds non affectés, preuve de frais absente ou portefeuille gelé. Aucun écart n'est « corrigé » par SQL. Arrêt si le relevé est incomplet ou la couverture ne peut pas être expliquée.
3. **Deux boutiques.** Préparer A et B avec propriétaires distincts et un membre lecture seule. Vérifier anonymat de la vitrine, refus d'accès croisé aux commandes, paiements, destinations, abonnements et statistiques. Arrêt au premier accès indu.
4. **Vente directe.** Configurer une variante avec une unité encore vendable, prix faible explicitement autorisé, livraison tarifée ou retrait organisé. Deux paniers concurrents : une seule commande peut réserver l'unité. Rejouer la même soumission : même reçu, même total, aucune seconde réservation. Ne pas réaliser deux paiements pour « tester » la concurrence.
5. **Encaissement autorisé.** Sur la seule commande retenue, lire le devis serveur, confirmer principal + frais client = total et accepter une fois le paiement. Conserver référence de transaction et reçu client. Attendre le GET indépendant et le statut serveur avant préparation. Arrêt si timeout incertain, montant/devise/compte discordant ou confirmation uniquement visuelle ; ne pas recliquer avec une nouvelle clé.
6. **Remise.** Préparer, expédier/livrer ou confirmer le retrait, puis clôturer selon les transitions autorisées. Vérifier suivi client, notification et stabilité des snapshots après modification du catalogue. La notification peut être retardée sans modifier le paiement.
7. **Retrait autorisé.** Vérifier destination immuable, solde éligible de A, dettes et réserves. Lire un devis récent : brut − frais = net, montant brut débité compatible avec `DEDUCTED`. Confirmer une fois dans le budget approuvé. Comparer double vérification fournisseur et reçu du bénéficiaire. Arrêt sur incertitude, devis expiré, écart de frais/net ou réserve inattendue ; aucun POST de récupération.
8. **Groupage.** Sur une campagne distincte de B, qualifier participants, clôture de collecte, récapitulatif fournisseur, approvisionnement, transport, frais finaux, réception, distribution puis clôture. Les simulations de retards/doublons se font en sandbox. Vérifier que dettes et crédits bloquent/reduisent les retraits comme attendu et qu'aucun montant de A ne finance B.
9. **Abonnement.** Vérifier tarif mensuel configuré et facture ; régler une fois uniquement avec autorisation distincte. Activation après preuve serveur et période unique. Vérifier le refus avant J−7, l'ouverture à J−7, la lecture des factures payées et la gestion des engagements existants. Les variations d'horloge/expiration sont testées en environnement isolé, jamais en modifiant l'horloge ou les statuts LIVE.
10. **Abandon/annulation/remboursement.** Annuler un brouillon sans paiement incertain et vérifier une seule restitution du stock. Pour un paiement encaissé, ouvrir le dossier ; tout remboursement réel exige une autorisation propre et un justificatif réel revu indépendamment. Conserver la mention « déclaré externe » et traiter la dette éventuelle après reversement. Arrêt si le justificatif manque ou si la destination n'est pas démontrée.
11. **Relever et décider.** Refaire les deux lectures, rapprocher les variations avec chaque reçu, documenter les écarts et l'état des réserves. Orange Money et chaque nouveau parcours LIVE demandent leur propre qualification ; un succès Wave ne suffit pas.

Conditions d'arrêt communes : preuve contradictoire, paiement/retrait incertain, double référence, contrôle d'autorisation défaillant, webhook non authentifiable, wallet gelé, checkpoint durablement en retard, montant non affecté inexpliqué, fonds d'une autre boutique utilisés ou nouveau POST inattendu. Conserver toutes les traces, suspendre les nouvelles opérations concernées, demander une preuve fournisseur ; ne pas réinitialiser l'historique.

## GO / NO-GO

| Condition | Pilote privé supervisé | Lancement commercial public |
| --- | --- | --- |
| Revue de la PR, tests complets et migration sur copie | Obligatoire | Obligatoire |
| Autorisation explicite du responsable, compte/réseaux/KYC validés | Obligatoire, petit périmètre et budget | Obligatoire, périmètre commercial confirmé |
| Stocks et engagements historiques inventoriés, annulations suivies | Obligatoire pour les offres à stock fini | Procédure durable et surveillance des abandons |
| Incidents sans référence et parse failures conservés/isolés | Revue nominative ; aucun rejeu incertain | Processus fournisseur et supervision démontrés |
| Rapprochement wallet et fonds marchands | Contrôle manuel avant/après chaque essai | Relevés exhaustifs, écarts expliqués, contrôle régulier et couverture démontrée |
| Remboursement externe | Justificatif réel, revue indépendante | Procédure complète, double validation/contrôle renforcé et litiges qualifiés |
| Opérateurs, MFA, alertes et sauvegardes | Accès nominatif supervisé | Authentification renforcée, supervision et restauration validées |
| Tous réseaux et parcours LIVE | Uniquement ceux explicitement qualifiés | Qualification de chaque parcours effectivement commercialisé |

**Refonte UI/UX : GO technique.** Les parcours métier et leurs contrats peuvent servir de base au chantier #96, en conservant les contrôles serveur, les preuves financières et les distinctions affichées. **Pilote privé : GO conditionnel**, après revue/fusion/déploiement séparés et satisfaction des conditions du tableau ; aucune autorisation LIVE n'est déduite des tests. **Lancement commercial public : NO-GO** tant que les preuves comptables, contractuelles, d'exploitation et de sécurité ci-dessus manquent. Ces validations externes peuvent avancer indépendamment de la refonte graphique.

## Validation exécutée

Les logs locaux sont sous `build/readiness-*` (ignorés par Git). Ils correspondent à cette branche ; les chiffres du rapport de la PR #106 ne sont pas recyclés.

- Python : **91 tests réussis**, dont exécution CLI payout simulée et configuration production/pilote.
- Migrations gelées : **22 conformes** à la baseline immuable.
- Maven ciblé, stock/rapprochement inverse : **28 tests**, zéro échec/erreur.
- Maven ciblé, synthèse opérateur/Modulith/OpenAPI : **21 tests**, zéro échec/erreur ; un test de contrat supplémentaire couvre ensuite le nom unique du schéma.
- Maven ciblé, migration stock, permissions, SasPay et abonnements : **72 tests**, zéro échec/erreur.
- Angular : **559 tests / 54 fichiers**, zéro échec ; lint réussi. Build production réussi avec avertissements de taille SCSS déjà présents et diagnostics de templates, sans désactivation de contrôle.
- Dépendances : `npm ci` réussi dans une copie de travail isolée ; tentative dans le dossier actif bloquée par un verrou Windows `EBUSY` sur Sass. Le serveur utilisateur n'a pas été arrêté. Sources et lockfile sont recopiés pour les validations. Les 12 201 fichiers de dépendances manquants après cette tentative ont été restaurés depuis cette installation au lockfile identique, sans écraser les fichiers existants. Audit production : **0 signalement** ; arbre complet : **9 modérés**, seuil CI respecté, aucun high/critical.
- Playwright première passe complète : **392 réussites / 2 échecs**. Une fixture publique omettait la réponse de couverture de livraison ; la console utilisait la locale anglaise pour les montants. Corrections effectuées, puis **4 scénarios ciblés réussis**, notamment affichage français, écart signé, stock épuisé et absence de débordement mobile.
- Contrats/Modulith après la première désambiguïsation : **17 tests réussis**. La couverture a ensuite été étendue aux reçus, contacts et lignes de commande ; la passe globale finale doit la valider.
- Une première passe Maven globale a été interrompue pour corriger ces collisions de contrats ; elle n'est pas comptée comme un succès global.
- Scan de secrets Docker, y compris après régénération finale : **0 résultat**. La première tentative restreinte n'avait pas produit de rapport ; la seconde a exécuté l'image CI. Actionlint passe et `git diff --check` passe.
- GitHub Actions, code `4993d12a732487d4d109ae8c2dd2952a4b36131d` : backend **1 604 tests**, zéro échec/erreur/ignoré, `BUILD SUCCESS` ; frontend **559 tests unitaires et 394 scénarios Playwright réussis**, lint/build/audit réussis ; contrôles Security réussis ([run 45394098095](https://github.com/ErenZone01/groupia-ai/actions/runs/45394098095)).
- Playwright complet local après corrections fonctionnelles : **394 réussites, zéro échec**, 21 minutes (`readiness-playwright-final.log`). Cette passe précède la dernière régénération des types API ; les contrôles du client final sont indiqués ci-dessous.
- Maven verify complet local final : **1 604 tests, zéro échec/erreur/ignoré**, `BUILD SUCCESS`, 40 min 09 s ; Spotless vérifie les 424 fichiers Java. PostgreSQL/Testcontainers, migrations, architecture Modulith, autorisations, concurrence et idempotence sont inclus.
- Contrat final : **216 schémas, 38 services, 424 fichiers générés** ; snapshot identique octet par octet à l’export du test OpenAPI. Neuf anciens fichiers générés devenus obsolètes sont retirés. Le premier contrôle frontend après régénération a signalé un import `Contact` obsolète : remplacé par `GroupingCheckoutContact`, sans changement du JSON envoyé.
- Client frontend final après correction de l’import : **559 tests / 54 fichiers réussis**, lint et build production réussis. Comparaison des **973 fichiers frontend** entre le dépôt et la copie testée : aucune différence. Les avertissements SCSS et de templates existants restent visibles. La dernière validation CI doit être consultée sur la tête courante de [PR #108](https://github.com/ErenZone01/groupia-ai/pull/108/checks), distinctement du run de code déjà détaillé ci-dessus.

Commandes : `mvnw.cmd --batch-mode --no-transfer-progress spotless:apply test -Dtest=...`, puis `mvnw.cmd --batch-mode --no-transfer-progress spotless:apply verify` avec `SPRING_CONFIG_IMPORT=''` et `SPRING_DOCKER_COMPOSE_ENABLED=false`. Côté frontend : `npm ci`, `npm run lint`, `npm test`, `npm run build`, `npm run test:e2e`, `npm run api:generate:snapshot`, `python ../../tools/check_frontend_audit.py` depuis la copie isolée. Outils : `python -m unittest discover -s tools -p 'test_*.py'`, `python tools/check_migrations.py`, `python tools/scan_secrets.py`, `git diff --check`.

## Fichiers modifiés

137 chemins ajoutés, modifiés ou retirés par rapport à la base `6727a5244ab19ec7975bcb92085f5b34c4c13980`. La liste inclut les anciens noms de modèles retirés et leurs remplacements générés. Seule migration ajoutée : `065-direct-stock.sql` ; aucun ancien changeset, `.env` ou secret modifié.

```text
Backend/docs/BUSINESS_FINANCIAL_READINESS_2026-11-24.md
Backend/src/main/java/sn/groupia/directsales/api/DirectOrderRecovery.java
Backend/src/main/java/sn/groupia/directsales/application/DirectCartQuoteService.java
Backend/src/main/java/sn/groupia/directsales/application/DirectCheckoutService.java
Backend/src/main/java/sn/groupia/directsales/application/DirectCustomerPaymentStatus.java
Backend/src/main/java/sn/groupia/directsales/application/DirectMerchantPaymentReadiness.java
Backend/src/main/java/sn/groupia/directsales/application/DirectOrderCancellation.java
Backend/src/main/java/sn/groupia/directsales/application/DirectOrderFulfillment.java
Backend/src/main/java/sn/groupia/directsales/application/DirectOrderPreparation.java
Backend/src/main/java/sn/groupia/directsales/application/DirectOrderQueries.java
Backend/src/main/java/sn/groupia/directsales/application/DirectOrderReadiness.java
Backend/src/main/java/sn/groupia/directsales/application/DirectStorefrontService.java
Backend/src/main/java/sn/groupia/directsales/infrastructure/DirectStorefrontController.java
Backend/src/main/java/sn/groupia/ordering/application/command/CheckoutCommand.java
Backend/src/main/java/sn/groupia/payment/infrastructure/provider/saspay/SaspayAccountingDiagnostic.java
Backend/src/main/java/sn/groupia/payment/infrastructure/provider/saspay/SaspayHistoryAudit.java
Backend/src/main/java/sn/groupia/payment/infrastructure/provider/saspay/SaspayIncidentConsoleController.java
Backend/src/main/java/sn/groupia/payment/infrastructure/provider/saspay/SaspayIncidentConsoleService.java
Backend/src/main/java/sn/groupia/payment/infrastructure/provider/saspay/SaspayIncidentMonitor.java
Backend/src/main/java/sn/groupia/settlement/infrastructure/web/PayoutAdministrationController.java
Backend/src/main/resources/db/changelog/065-direct-stock.sql
Backend/src/main/resources/db/changelog/db.changelog-master.yaml
Backend/src/test/java/sn/groupia/DirectStockMigrationTests.java
Backend/src/test/java/sn/groupia/DirectStorefrontIntegrationTests.java
Backend/src/test/java/sn/groupia/OpenApiIntegrationTests.java
Backend/src/test/java/sn/groupia/SaspayIncidentConsoleIntegrationTests.java
Backend/src/test/java/sn/groupia/SaspayIntegrationTests.java
Backend/src/test/java/sn/groupia/payment/infrastructure/provider/saspay/SaspayHistoryAuditTests.java
Backend/src/test/java/sn/groupia/payment/infrastructure/provider/saspay/SaspayIncidentMonitorDatabaseIntegrationTests.java
Frontend/e2e/direct-sales-v1.spec.ts
Frontend/e2e/saspay-accounting.spec.ts
Frontend/openapi.json
Frontend/public/pilot-admin-79f4562038319721702650e2.html
Frontend/src/app/api/generated/fn/customer-access-controller/verified-direct-orders.ts
Frontend/src/app/api/generated/fn/direct-checkout-controller/checkout-1.ts
Frontend/src/app/api/generated/fn/direct-customer-payment-status-controller/read-direct-payment-preparation-by-token.ts
Frontend/src/app/api/generated/fn/direct-order-management-controller/cancel-direct-unpaid-order.ts
Frontend/src/app/api/generated/fn/direct-order-management-controller/fulfill.ts
Frontend/src/app/api/generated/fn/direct-order-management-controller/get-direct-order-payment-readiness.ts
Frontend/src/app/api/generated/fn/direct-order-management-controller/list-direct-orders.ts
Frontend/src/app/api/generated/fn/direct-order-management-controller/mark-direct-order-ready.ts
Frontend/src/app/api/generated/fn/direct-order-management-controller/start-direct-order-preparation.ts
Frontend/src/app/api/generated/fn/direct-storefront-controller/stock.ts
Frontend/src/app/api/generated/fn/merchant-settlement-controller/quote-direct-merchant-payout.ts
Frontend/src/app/api/generated/fn/merchant-settlement-controller/quote-merchant-payout.ts
Frontend/src/app/api/generated/fn/merchant-settlement-controller/recover-pilot-merchant-payout.ts
Frontend/src/app/api/generated/fn/organization-controller/get-3.ts
Frontend/src/app/api/generated/fn/organization-controller/get-4.ts
Frontend/src/app/api/generated/fn/payout-administration-controller/close-historical-unlinked.ts
Frontend/src/app/api/generated/fn/payout-administration-controller/close-recorded-rejection.ts
Frontend/src/app/api/generated/fn/payout-administration-controller/decide-1.ts
Frontend/src/app/api/generated/fn/payout-administration-controller/historical-unlinked-status.ts
Frontend/src/app/api/generated/fn/saspay-incident-console-controller/access.ts
Frontend/src/app/api/generated/fn/saspay-incident-console-controller/accounting.ts
Frontend/src/app/api/generated/fn/saspay-incident-console-controller/decide.ts
Frontend/src/app/api/generated/fn/saspay-incident-console-controller/get-3.ts
Frontend/src/app/api/generated/fn/saspay-incident-console-controller/list-3.ts
Frontend/src/app/api/generated/fn/saspay-incident-console-controller/provider-preflight.ts
Frontend/src/app/api/generated/functions.ts
Frontend/src/app/api/generated/models.ts
Frontend/src/app/api/generated/models/access.ts
Frontend/src/app/api/generated/models/address.ts
Frontend/src/app/api/generated/models/checkout-command.ts
Frontend/src/app/api/generated/models/checkpoint.ts
Frontend/src/app/api/generated/models/command-result.ts
Frontend/src/app/api/generated/models/command.ts
Frontend/src/app/api/generated/models/contact.ts
Frontend/src/app/api/generated/models/decision-request.ts
Frontend/src/app/api/generated/models/detail.ts
Frontend/src/app/api/generated/models/direct-cancellation-receipt.ts
Frontend/src/app/api/generated/models/direct-checkout-address.ts
Frontend/src/app/api/generated/models/direct-checkout-command.ts
Frontend/src/app/api/generated/models/direct-checkout-contact.ts
Frontend/src/app/api/generated/models/direct-checkout-line.ts
Frontend/src/app/api/generated/models/direct-checkout-receipt.ts
Frontend/src/app/api/generated/models/direct-customer-financial-status.ts
Frontend/src/app/api/generated/models/direct-fulfillment-command.ts
Frontend/src/app/api/generated/models/direct-merchant-financial-status.ts
Frontend/src/app/api/generated/models/direct-order-address.ts
Frontend/src/app/api/generated/models/direct-order-contact.ts
Frontend/src/app/api/generated/models/direct-order-page.ts
Frontend/src/app/api/generated/models/direct-order-recovery-page.ts
Frontend/src/app/api/generated/models/direct-preparation-receipt.ts
Frontend/src/app/api/generated/models/direct-readiness-receipt.ts
Frontend/src/app/api/generated/models/grouping-checkout-contact.ts
Frontend/src/app/api/generated/models/grouping-checkout-line.ts
Frontend/src/app/api/generated/models/historical-unlinked-status.ts
Frontend/src/app/api/generated/models/historical-unlinked.ts
Frontend/src/app/api/generated/models/http-headers.ts
Frontend/src/app/api/generated/models/incident-detail.ts
Frontend/src/app/api/generated/models/incident.ts
Frontend/src/app/api/generated/models/line.ts
Frontend/src/app/api/generated/models/merchant-balance.ts
Frontend/src/app/api/generated/models/merchant-payout-view.ts
Frontend/src/app/api/generated/models/offer.ts
Frontend/src/app/api/generated/models/organization-position.ts
Frontend/src/app/api/generated/models/page.ts
Frontend/src/app/api/generated/models/payout-administrative-decision.ts
Frontend/src/app/api/generated/models/payout-fee-quote-view.ts
Frontend/src/app/api/generated/models/payout-recovery-request.ts
Frontend/src/app/api/generated/models/receipt.ts
Frontend/src/app/api/generated/models/recorded-rejection.ts
Frontend/src/app/api/generated/models/result.ts
Frontend/src/app/api/generated/models/saspay-accounting-snapshot.ts
Frontend/src/app/api/generated/models/saspay-incident-decision.ts
Frontend/src/app/api/generated/models/saspay-incident-page.ts
Frontend/src/app/api/generated/models/snapshot.ts
Frontend/src/app/api/generated/models/status.ts
Frontend/src/app/api/generated/models/stock-input.ts
Frontend/src/app/api/generated/services.ts
Frontend/src/app/api/generated/services/customer-access-controller.service.ts
Frontend/src/app/api/generated/services/direct-checkout-controller.service.ts
Frontend/src/app/api/generated/services/direct-customer-payment-status-controller.service.ts
Frontend/src/app/api/generated/services/direct-order-management-controller.service.ts
Frontend/src/app/api/generated/services/direct-storefront-controller.service.ts
Frontend/src/app/api/generated/services/merchant-settlement-controller.service.ts
Frontend/src/app/api/generated/services/organization-controller.service.ts
Frontend/src/app/api/generated/services/payout-administration-controller.service.ts
Frontend/src/app/api/generated/services/saspay-incident-console-controller.service.ts
Frontend/src/app/features/checkout/checkout.store.ts
Frontend/src/app/features/direct-sales/direct-sales.api.spec.ts
Frontend/src/app/features/direct-sales/direct-sales.api.ts
Frontend/src/app/features/direct-sales/direct-sales.page.html
Frontend/src/app/features/direct-sales/direct-sales.page.ts
Frontend/src/app/features/direct-sales/public-direct.page.html
Frontend/src/app/features/direct-sales/public-direct.page.spec.ts
Frontend/src/app/features/direct-sales/public-direct.page.ts
Frontend/src/app/features/payments/saspay-accounting.spec.ts
Frontend/src/app/features/payments/saspay-incidents.api.ts
Frontend/src/app/features/payments/saspay-incidents.page.html
Frontend/src/app/features/payments/saspay-incidents.page.scss
Frontend/src/app/features/payments/saspay-incidents.page.ts
Frontend/src/app/features/settlements/provider-collections.page.html
tools/check_saspay_config.py
tools/saspay_inspect_payout.py
tools/test_check_saspay_config.py
tools/test_saspay_inspect_payout.py
```
