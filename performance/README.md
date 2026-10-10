# Charge isolée, hors CI normale

Aucun test de charge n'a été lancé dans cette tranche. Installer k6 séparément. Préparer une DB jetable avec des données synthétiques et des adapters de paiement/messagerie de test ; aucun secret ni endpoint fournisseur réel.

Exemple dans cet environnement seulement : `k6 run -e ALLOW_LOAD=isolated-environment -e BASE_URL=http://127.0.0.1:8080 -e PUBLIC_GROUPAGE_ID=<identifiant-public-de-test> performance/public-groupage.js`. Les seuils du script sont des objectifs de départ, pas des performances mesurées. Ne pas activer HTTP debug ni logger les réponses.

Scénarios suivants à construire après définition du jeu de données :

| Scénario | Assertions et précautions |
| --- | --- |
| Lecture groupage public | p95/p99, 5xx, requêtes SQL et cache froid/chaud |
| Checkout | ID d'idempotence par commande synthétique ; compteurs/stock cohérents sous concurrence ; aucun vrai encaissement |
| Tracking | jetons de test dans le header du contrat, aucune valeur dans logs ou tags métriques ; tester 429 |
| Liste commandes organisatrice | JWT synthétique court, pagination maximale, isolation boutique |
| Burst webhook | provider double local, signatures de test, doublons et ordre inversé ; un seul settlement et événement |

Mesurer CPU, pool JDBC, verrous PostgreSQL, durée d'expiration et backlog Modulith. Commencer faible et définir des conditions d'arrêt. Ne jamais exécuter un dump production ou des messages WhatsApp réels dans ces scénarios.
