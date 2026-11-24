# Recette métier et financière Groupia V1 — 24 novembre 2026

Base : `6727a5244ab19ec7975bcb92085f5b34c4c13980` (main, PR #107). Dépôt initial propre ; branche `codex/groupia-final-business-financial-readiness`. Aucun déploiement, changement de flags LIVE, POST financier ou modification SQL de statut autorisé par cette recette.

## Audit initial et corrections à valider

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

## Validation

En cours : les résultats seront ajoutés après exécution, sans reprendre les résultats d'une PR antérieure comme validation du nouveau commit.
