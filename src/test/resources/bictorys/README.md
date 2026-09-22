# Fixtures Bictorys
Vérifiées le 22 septembre 2026. JSON synthétiques, pas des captures d'un compte réel.
Identifiants, coordonnées et jetons sont factices.

- charge-201.json : ConfirmationLinkObject de [createCharge](https://docs.bictorys.com/reference/createcharge), transactionId/link ; redirectUrl volontairement distinct.
- charge-202.json : CheckoutLinkObject, même référence OpenAPI, chargeId/link/opToken ; aucun transactionId inventé.
- webhook-root.json : [guide intégration](https://docs.bictorys.com/docs/integration), id/type/paymentReference/merchantReference/amount/currency/status/timestamp et customerObject ignoré.
- webhook-data.json : [exemple historique](https://docs.bictorys.com/docs/status-responses-and-their-meanings), event=charge.successful, data, statut « réussi ». Sans merchantReference : revue, jamais corrélation par reference.
- status-only.json : forme minimale du guide. Complément de preuve selon le schéma de [transaction](https://docs.bictorys.com/reference/gettransactiondetails), représenté par les champs financiers de webhook-root.

Les tests modifient explicitement ces exemples pour les incohérences/états.
Ni id de transaction ni type event ne devient un providerEventId.
Tout nouveau format doit avoir un contrat et une source ; aucune valeur financière
de repli pour faire passer un test.
