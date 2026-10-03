# ADR-0016 — Mesures externes et calcul final par commande

Statut : accepté pour la tranche Shipping V1, 2026-10-03.

Les informations physiques viennent de prestataires ; Groupia doit pouvoir expliquer le calcul
ou tracer un montant fourni sans reconstruire artificiellement une formule.

Décision : shipping possède l'assessment, les observations et les calculs immuables.
ordering fournit ses snapshots par shipping::api et assure autorisation, éligibilité et verrouillage.
Le graphe Modulith reste inchangé. Les FK SQL assurent l'appartenance sans lien JPA intermodule.

Une évaluation se crée à la première écriture, passe de PENDING à FINALIZED et conserve cet état lors
des corrections. Le dernier calcul courant reste visible jusqu'à validation d'une nouvelle révision.
Une correction de mesure est indépendante d'une finalisation ; chacune possède un motif et son historique.
CALCULATED et PROVIDER_QUOTED sont explicites. Source externe et auteur authentifié sont séparés.

Le diviseur volumétrique facultatif appartient aux conditions commerciales gelées après OPEN.
Les anciens snapshots sans ce champ ne changent pas de sens. Aucun diviseur par défaut.
Le ratio exact est conservé jusqu'à l'arrondi monétaire HALF_UP ; l'affichage décimal ne sert pas au calcul.

Décisions complémentaires : campagnes éligibles SUPPLIER_ORDERED/IN_TRANSIT/ARRIVED,
commandes CONFIRMED, permissions VIEW_ORDERS + MANAGE_GROUPINGS pour écrire,
seuil UX d'écart 20 % non bloquant, lots complets pour les volumes MVP, dimensions par commande.
Les métadonnées inconnues restent null et les corrections nécessitent une action explicite.

Conséquences : migration 014 additive ; lecture cliente filtrée ; pas d'événement consommé,
d'obligation de paiement ni de mutation d'état commercial/logistique.
La prochaine tranche devra définir le contrat d'événement final/recalcul et son traitement idempotent
avant toute création de solde ; aucun faux événement financier n'est introduit ici.

Voir [contrats, calculs et recette](../docs/shipping-v1.md).
