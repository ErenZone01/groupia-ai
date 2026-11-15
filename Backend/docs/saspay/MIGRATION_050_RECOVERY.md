# Démarrage bloqué par la migration 050

Incident du 9 novembre 2026 : `050-commercial-cancellation-states` possède en base l’empreinte `9:2e225b38cb346c51a9f5f2af9a9b21e1`, alors que le fichier augmenté de l’acteur d’annulation produisait `9:cee7dcfde2808586052973a6183bfaa5`. Le refus Liquibase empêche l’initialisation JDBC, puis les filtres d’authentification et Tomcat. Le message Maven BUILD SUCCESS en fin de `spring-boot:run` avec DevTools ne prouve pas que l’application a démarré.

La version initiale de 050 est restaurée. La seule variante augmentée connue est déclarée par `validCheckSum` ; aucune empreinte arbitraire ni `ANY` n’est acceptée. La nouvelle migration 051 ajoute `grouping_campaigns.cancelled_by` si cette colonne est absente. Si elle existe, elle doit être UUID, nullable et sans valeur par défaut ; ses valeurs existantes sont conservées. Une forme incompatible interrompt la migration dans sa transaction.

Les montants, paiements, commandes et lignes historiques de DATABASECHANGELOG ne sont pas réécrits. Aucun `clearCheckSums`, suppression de base/volume, réexécution forcée de 050 ou mise à jour manuelle des empreintes n’est nécessaire.

Les tests PostgreSQL reconstruisent les deux fichiers historiques et vérifient leurs empreintes exactes avant la mise à niveau. Ils couvrent les versions initiale et augmentée, une empreinte inconnue refusée, les colonnes incompatibles (type, nullabilité, défaut), une base neuve et une seconde exécution sans changement. Ils comparent les montants et l’historique avant/après.

Après récupération du correctif, depuis `Backend`, relancer `.\mvnw.cmd spring-boot:run` avec la configuration locale habituelle. Liquibase applique uniquement la migration nouvelle 051 et l’application doit annoncer `Started GroupiaApplication`. Cette procédure n’active aucun fournisseur ni payout. La base personnelle n’a pas été modifiée par les tests : ils utilisent des conteneurs jetables.

Validation ciblée : 7 tests PostgreSQL réussis, aucun échec/erreur/ignoré, empreintes historiques reproduites exactement. Les 22 migrations gelées restent conformes et le scan de secrets ne signale rien. Maven verify complet sur `1e223ca` : **1 392 tests, aucun échec, erreur ou ignoré ; BUILD SUCCESS**, terminé 2026-11-09T00:55:50Z, durée 18:12 min. Les contextes Spring et les migrations fraîches/historiques sont validés. Les tests ne prouvent pas un redémarrage de la base personnelle, qui reste à effectuer avec la commande ci-dessus.

Référence : [contrôle des empreintes Liquibase 5.0.3](https://docs.liquibase.com/community/user-guide-5-0-3/what-is-a-changeset-checksum).
