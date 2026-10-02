# ADR-0015 — Dossier logistique externe, dates inconnues et historique privé

Statut : accepté pour la tranche demandée du 2 octobre 2026.

## Décision

Conserver Shipment dans shipping, sans nouveau statut de campagne. Le dossier est ouvert à la première saisie dès SUPPLIER_ORDERED, sans information externe obligatoire.
Les POST /ship et /arrive expriment explicitement la confirmation et conservent leur URL/contrat versionné ; seule l'obligation de date disparaît. Une date manquante reste null et la confirmation serveur est distincte.
Grouping conserve l'autorité des transitions et le verrou du groupage. Tout enrichissement avance aussi sa version ; Shipment a en plus un CAS sur version. Le partage d'une transaction est nécessaire à l'invariant état/dossier/fait.
La timeline append-only est un journal de déclarations et de snapshots, pas un moteur d'event sourcing. Sa lecture suit la révision de saisie ; occurredAt communiquée est distincte de recordedAt.
Les premières dates de transition restent immuables. Une correction se consigne comme nouvelle information ; changer la projection d'une date confirmée requerra une action de correction dédiée future.
Les PUT remplacent explicitement les textes courants ou l'ETA ; les valeurs antérieures restent au journal.
Pas de nouvelle dépendance Modulith, relation JPA inter-module, notification ou effet financier.

## Provenance et compatibilité

V1 : prestataire externe → information → gérante → Groupia.
V2 future : prestataire inscrit → information directe → Groupia.
Source externe et actorId authentifié sont séparés ; des snapshots privés subsisteront après un éventuel rattachement futur à un compte prestataire.
Les données legacy sont importées sans réécriture des dates. recordedAt se fonde sur les écritures historiques connues. L'auteur peut rester inconnu uniquement pour ces imports explicitement marqués ; aucun acteur n'est inventé.
L'ancien remplacement d'une arrivée inconnue par l'heure serveur n'est pas réversible : conserver la valeur historique et signaler son origine est préférable à deviner.
Aucun changement du DTO client GroupingProgress ; les valeurs nullable suffisent au suivi sans date inventée.

## Conséquences

Une commande rejouée avec une version périmée est refusée (409), sans double fait. Après une réponse perdue, relecture explicite plutôt que retry automatique.
Les informations privées de dossier/timeline ne sont jamais renvoyées au suivi client. La source et les instants seront réutilisables pour les futures mesures, sans implémenter ShippingMeasurement/Assessment/Calculation maintenant.
