# Parcours cliente public — panier, commande et paiement

Cette tranche prolonge la vitrine existante sans compte cliente. Le backend et les migrations ne sont pas modifiés. Aucun appel Bictorys direct n’est fait par Angular.

## Routes et architecture

| Route | Page |
| --- | --- |
| /public/groupages/:publicIdentifier | Vitrine, sélection, accès au panier |
| /public/groupages/:publicIdentifier/cart | Quantités, retrait des lignes, accord sur les nouveaux prix |
| /public/groupages/:publicIdentifier/checkout | Coordonnées, validation et création de commande |
| /public/order | Récapitulatif serveur, préparation du paiement, confirmation et suivi |

Les routes sont lazy-loaded et anonymes. Les anciens liens /g/:publicId/commander et /suivi restent utilisables.

Principaux fichiers :
- features/checkout/cart.store.ts : paniers par groupage, Signals, snapshots et consentement aux nouvelles révisions.
- features/checkout/cart.page.ts et cart-lines.ts : panier et quantités.
- features/checkout/checkout.page.ts et checkout.store.ts : formulaire, prévalidation, idempotence et traitement 201/200.
- features/checkout/public-order-api.ts : adaptation du client généré.
- features/checkout/public-order-session.service.ts : stockage privé de la commande courante.
- core/http/order-tracking.interceptor.ts : credential explicite, uniquement sur les deux routes Groupia autorisées, de même origine.
- features/tracking/public-tracking.store.ts : polling borné et initiation idempotente.
- features/tracking/tracking.page.ts et order-summary.ts : montants serveur et états traduits.

Aucune dépendance ajoutée.

## Contrats utilisés

| Méthode | Endpoint | Credential |
| --- | --- | --- |
| GET | /api/public/groupings/{publicIdentifier} | Aucun |
| POST | /api/public/groupings/{publicIdentifier}/checkout | Idempotency-Key |
| POST | /api/public/orders/{orderNumber}/payments | Idempotency-Key et X-Order-Tracking-Token |
| GET | /api/public/orders/track | X-Order-Tracking-Token |

Aucun JWT sur ces routes. Les types viennent de api/generated : PublicGroupingView, PublicOfferView, CatalogVariantView, ShippingTerms, CheckoutCommand, Contact, Line, OrderReceipt, OrderReplay, OrderLineView, InitiatePayment et PaymentAttemptView. Services générés : PublicGroupingControllerService et OrderingControllerService.

Deux particularités du contrat existant sont traitées dans du code applicatif :
- Le checkout documente 201/OrderReceipt et 200/OrderReplay, mais le générateur sélectionne le type de la réponse 200. La façade examine le statut HTTP et utilise les deux modèles générés.
- L’en-tête privé n’apparaît pas dans les paramètres générés. Un HttpContext explicite le transmet à un interceptor strictement limité aux endpoints de suivi/initiation. Aucun fichier généré n’est modifié.

## Panier, prix et idempotence

Le panier mémorise l’offre publique (identifiants, nom, libellé, attributs, unité de vente, prix et révision) et la quantité dans sessionStorage, séparément par groupage. Les totaux du panier sont indicatifs. Les frais et le montant à payer sont exclusivement ceux du reçu serveur. Les données de contact ne sont pas persistées.

Avant un POST checkout, la vitrine est rechargée. Une offre manquante ou inactive bloque la commande ; le panier reste disponible pour correction. Un changement de prix ou de révision ne remplace pas le snapshot accepté. Le bouton « Accepter le nouveau prix » est requis. Un conflit signalé par le POST recharge également les offres sans relancer la création.

Une empreinte SHA-256 du groupage, du contact normalisé et des lignes triées associe le contenu à une clé UUID de tentative. Clé et empreinte sont conservées dans sessionStorage. Un timeout/une perte réseau réutilise la même clé. Un contenu différent obtient une nouvelle clé. Le bouton et le store bloquent les soumissions concurrentes. Les POST ont un timeout de 20 secondes ; le GET de prévalidation de 15 secondes.

L’initiation du paiement possède sa propre clé, persistée avant l’appel et réutilisée après une erreur ou un rechargement. Une réponse déjà reçue, notamment reconciliationRequired=true ou checkoutUrl absent, n’entraîne aucune nouvelle initiation automatique. Les nouvelles tentatives après échec financier définitif restent hors de cette tranche.

## Session et suivi

Le trackingToken est enregistré synchroniquement dans sessionStorage dès la réponse 201, avant navigation ou initiation. Le stockage est vérifié avant de créer la commande. La commande courante, son reçu et la tentative de paiement sont conservés dans la session ; le token n’apparaît ni à l’écran, ni dans une URL, ni dans les logs. Il est envoyé uniquement en en-tête aux APIs Groupia de suivi et de paiement.

Le lien fourni par le backend doit être HTTPS, sans identifiants intégrés. Il s’ouvre dans un nouvel onglet avec noopener/noreferrer et sans referrer. Une URL absente ou un paiement en vérification n’est jamais reconstruit ou relancé automatiquement.

Le suivi appelle uniquement Groupia toutes les 4 secondes, sans requêtes simultanées, pendant au plus 3 minutes par visite. Une erreur interrompt les appels et propose une actualisation manuelle ; aucun retry en boucle. CONFIRMED, COMPLETED, CANCELLED ou EXPIRED arrête le polling. La destruction de la page annule le timer et la requête en cours. Une échéance dépassée ne devient jamais localement EXPIRED : on attend le statut serveur, dans la limite de cette fenêtre ; le suivi reste actualisable manuellement. L’initiation est bloquée après l’échéance connue.

Seul le suivi retournant CONFIRMED affiche la confirmation et vide le panier du groupage correspondant. Ni OrderReceipt, ni PaymentAttempt SUCCEEDED, ni le retour de l’onglet prestataire ne confirme localement un paiement. L’état logistique est affiché séparément.

## Limites du backend et de la session

- En cas de perte de la réponse 201, le retry 200 retourne uniquement orderNumber et replayed. Si le token initial n’a jamais atteint le navigateur, le frontend ne peut pas le récupérer. Il indique que le suivi n’est plus disponible sur cet appareil et ne crée pas une nouvelle commande pour contourner cette limite.
- Fermeture de la session/effacement du stockage : perte du suivi. Besoin futur : récupération sécurisée par OTP ou autre mécanisme backend. Aucun endpoint fictif n’est utilisé.
- La vue /public/order concerne la commande courante. Un historique privé de plusieurs commandes et le transfert de suivi entre appareils restent à concevoir.
- Pas de calcul de transport futur. L’indication « après réception/pesée » vient des conditions de livraison du groupage conservées au checkout.
- Panier limité à 100 offres et 1 000 000 unités par ligne, conformément aux contrôles serveur ; montants au-delà de la précision entière sûre JavaScript bloqués au checkout.
- L’ouverture de paiement exige HTTPS. Le frontend doit être servi sur localhost ou HTTPS pour les primitives crypto et, en production, partager l’origine du proxy /api.

## Tests et vérification

Les tests unitaires utilisent HttpTestingController. Les tests navigateur interceptent toutes les APIs ; la transition du suivi vers CONFIRMED simule un webhook traité côté backend. Aucun vrai paiement sandbox ni appel Bictorys n’est automatisé.

Commandes depuis Frontend :

```powershell
npm test
npm run build -- --configuration production
npm run test:e2e
```

## Recette réelle manuelle

1. Dans Backend, démarrer PostgreSQL avec `docker compose up -d postgres`. Conserver la configuration JWT et les secrets locaux existants.
2. Configurer Bictorys sandbox selon [le guide backend](../../Backend/docs/bictorys-sandbox.md), notamment les variables BICTORYS_ENABLED, BICTORYS_API_URL, BICTORYS_API_KEY et BICTORYS_WEBHOOK_SECRET. Les secrets restent côté backend. Démarrer Spring Boot avec `.\mvnw.cmd spring-boot:run`.
3. Dans Frontend, lancer `npm start` et ouvrir localhost:4200. Le proxy /api vise localhost:8080. Laisser le navigateur ouvert pendant toute la recette.
4. Lancer `cloudflared tunnel --url http://localhost:8080`. Configurer dans le dashboard sandbox le webhook HTTPS `/api/payment/webhooks/bictorys` sur cette origine et le secret prévu. Mettre l’URL à jour si le tunnel change.
5. Côté gérante, disposer d’un groupage réellement OPEN avec au moins une variante active et un prix. Copier son lien public.
6. Ouvrir le lien en cliente, sans connexion. Sélectionner une variante et une quantité ; aller au panier. Vérifier prix/options/quantité.
7. Remplir les coordonnées puis continuer. Vérifier le reçu serveur : articles, frais de service, transport maintenant, total exigible et échéance.
8. Préparer le paiement sécurisé une seule fois. Si une vérification est en cours, ne pas recommencer. Sinon ouvrir le lien Wave / Orange Money.
9. Effectuer manuellement le scénario sandbox fourni par Bictorys. Ne pas utiliser de fonds réels et ne pas fabriquer un webhook pour cette recette.
10. Laisser Bictorys délivrer son vrai webhook via le tunnel. Vérifier côté backend le traitement authentifié et les preuves financières.
11. Revenir dans l’onglet Groupia. Le GET de suivi doit retourner CONFIRMED ; l’écran affiche la confirmation et vide uniquement le panier concerné. Si les trois minutes sont écoulées, utiliser « Actualiser le suivi ».
12. Vérifier le suivi après rechargement dans la même session, puis tester le message explicite de suivi indisponible dans une session vierge. Le retour navigateur et le dashboard prestataire seuls ne doivent jamais produire de confirmation Angular.

Cette recette est documentée, pas exécutée automatiquement. Le règlement réel et la livraison du webhook nécessitent l’environnement sandbox configuré.

## Résultats — 26 septembre 2026

- `npm test` : **113 tests réussis**, 17 fichiers.
- `npm run test:e2e` : **45 tests navigateur réussis** (31 parcours existants et 14 nouveaux), Chromium. Les parcours publics couvrent 360 px light/dark, 768 px et 1440 px, sans débordement horizontal.
- `npm run build -- --configuration production` : **réussi**, budgets respectés. Bundle initial : 301,33 kB, transfert estimé : 84,25 kB.
- Captures mobile light/dark inspectées ; formats des montants, récapitulatif et actions de paiement vérifiés.
- `git diff --check` : réussi. Aucun changement backend, SQL, modèle généré ni dépendance.
- Les tests utilisent exclusivement des réponses API simulées. Aucun paiement réel ou sandbox effectué. La recette manuelle ci-dessus reste à réaliser dans l’environnement configuré.
