# Groupia — Coordonnées, paiement, suivi et localisation V2

## UX approuvée

Implémentation de la maquette coordonnées / paiement / suivi, en continuité de la vitrine et du panier publics. Les textes et montants proviennent des API réelles, jamais de données de maquette.

- Coordonnées : nom, prénom, téléphone, e-mail facultatif.
- Localisation Sénégal : région (14), ville/département (46), commune (~600 suggestions selon la région), quartier facultatif, adresse et repère requis.
- Recherche : 2 listes liées à la région, et choix « autre » pour saisir une ville ou une commune manuellement. Les communes proposées sont **filtrées par région**, pas par département : la source n'inclut pas ce rattachement fin. Ne pas utiliser les suggestions comme preuve d'éligibilité à une livraison.
- Réutilisation de la route existante de checkout et du contrat `contact.deliveryAddress` (champ optionnel serveur pour compatibilité des anciens clients, requis sur le nouveau formulaire).
- Enregistrement dans le **snapshot JSONB de la commande** via `CustomerContact`, sans créer de table, de colonne ou de migration : les commandes historiques continuent à fonctionner.
- Validation serveur stricte des localisations fournies et clé d'idempotence liée à l'adresse. Le fingerprint `checkout-v1` est conservé pour les checkouts sans adresse déjà commencés ; la nouvelle variante d'empreinte ne s'applique qu'aux adresses effectivement fournies.
- Fiche de commande gérante : localisation déclarée visible dans les informations cliente ; aucun changement aux accès public, au suivi anonyme ou au webhook.
- La collecte de localisation **ne signifie pas que la livraison à domicile est proposée**. Le mode de remise doit être convenu avec la gérante. Aucun nouveau tarif de dernier kilomètre calculé.
- Paiement et suivi : refonte visuelle uniquement, en conservant le paiement Bictorys existant, URL HTTPS contrôlée, gestion du timeout, des tentatives, du statut en attente et de la réconciliation, sans simuler de confirmation.

## Référentiel géographique

Les suggestions sont dérivées des fichiers `regions.json`, `departments.json`, `commune.json` de **GalsenAPi**, auteur **Lassana SIBY** :
https://github.com/sibylassana95/GalsenAPi/tree/main/dataset

Transformation par Groupia : sélection des noms et des parentés régionales, regroupement, tri et dédoublonnage, correction de présentation de `Thiès`. Référence consultée le 29 octobre 2026. Réutilisation et attribution conformément à la licence du projet :
https://github.com/sibylassana95/GalsenAPi/blob/main/Licence.md

Cette liste communautaire n'est **pas un référentiel administratif certifié** et ne permet pas toujours de rattacher une commune à un département. La saisie manuelle reste systématiquement possible. Pour un usage logistique avancé, prévoir un référentiel à codes administratifs stables et des correspondances exhaustives mises à jour.

## Vérifications locales obligatoires

```powershell
git fetch origin
git switch --track origin/public-checkout-ux-v2-address
cd Backend
.\mvnw.cmd spotless:apply
.\mvnw.cmd verify
cd ../Frontend
npm run lint
npm test
npm run build
npx playwright test e2e/public-checkout.spec.ts
npm run test:e2e
```

Contrôler aussi en situation réelle : montant estimé vs transport final, mobile 320/360/390, thèmes clair/sombre, commune absente, changement de région, paiement en attente/réconciliation/confirmé, erreur réseau et affichage de l'adresse chez la gérante.

Les tests n'ont pas été exécutés depuis l'éditeur GitHub distant, GitHub Actions étant temporairement limité par le quota. Ne pas fusionner sans vérification locale et CI ultérieure.

## Chaîne de PR

Cette branche se fonde sur `public-storefront-ux-v2` (PR #43), elle-même dépendante de #42 et #41. Respecter cet ordre de fusion.
