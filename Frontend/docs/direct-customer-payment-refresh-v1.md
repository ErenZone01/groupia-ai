# Vente directe — actualisation du paiement après Bictorys

Une cliente peut revenir sur son suivi de commande après avoir ouvert la
page Bictorys dans un autre onglet. Le frontend vérifie alors sans action
supplémentaire les états du fournisseur **au travers du backend uniquement**.

## Comportement
- Après une initiation Bictorys, ou au retour sur un suivi dont l'état
  financier est `PAYMENT_IN_PROGRESS` / `SETTLEMENT_PROCESSING`,
  la page vérifie le statut toutes les **15 secondes**, au maximum
  **12 fois (3 minutes)** pour cette visite.
- Chaque vérification relit le suivi de la commande ET la vérité financière
  côté serveur, et compare **référence, devise et total accepté**.
- L'ancienne commande reste visible pendant la vérification : pas de
  clignotement de la page, de recharge navigateur ou de création de charge.
- `SETTLEMENT_VERIFIED` efface l'état local « en attente » et le lien
  de paiement précédent. L'affichage reste en lecture seule.
- Un état invalide, expiré, en revue ou contradictoire n'est jamais
  présenté comme payé : le paiement reste bloqué et la surveillance
  s'arrête sur un état terminal.
- Un changement de jeton (ou la destruction du composant) annule les
  contrôles programmés. Une réponse arrivée après un changement de jeton
  est ignorée. Les contrôles ne survivent pas à la fermeture de la page.
- Au-delà des trois minutes, la cliente peut toujours utiliser
  **« Actualiser le statut »** pour reprendre la vérification.

Aucune nouvelle route, aucun stockage du jeton, aucun webhook supplémentaire
ni aucun paiement automatique n'a été ajouté. Le flag
`groupia.direct-sales.payment-initiation.enabled=false` reste inchangé.
La confirmation dépend exclusivement de l'état canonique vérifié par le backend.
