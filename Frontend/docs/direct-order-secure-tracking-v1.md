# Vente directe — lien personnel de suivi des brouillons

## Parcours client
Quand la cliente confirme une commande directe sans compte (et uniquement si
`GROUPIA_DIRECT_CHECKOUT_ENABLED=true`), son navigateur génère **deux valeurs
indépendantes** avec `crypto.getRandomValues` :
- une clé d'idempotence pour réessayer la soumission sans doublon ;
- un jeton opaque de 32 octets (256 bits), encodé Base64 URL sans padding.

Le jeton est envoyé au checkout lors de la création, et sa **seule empreinte
SHA-256 préfixée** est persistée dans `direct_orders.recovery_token_hash`.
Le serveur ne stocke pas le jeton brut. Une commande créée avant cette tranche
reste lisible par les gérantes, mais n'obtient pas un lien public rétroactif.

La confirmation propose un lien personnel du type
`/v/{publicIdentifier}/commande#access={token}`. La partie après `#`
n'est pas envoyée lors du chargement HTTP. Le client ne transmet le jeton
que dans le **corps** de :
`POST /api/public/direct-orders/lookup` (`{"token":"..."}`).

La réponse `Cache-Control: no-store` expose seulement référence, statut,
remise, devise, sous-total, frais et total. Elle **n'expose jamais** prénom,
nom, numéro de téléphone, email, adresse ou coordonnées GPS. Une valeur
absente/invalide produit la même erreur de ressource introuvable. Le numéro
`DIR-...` ne constitue jamais un titre d'accès.

## Sécurité et limitations
- Un lien est un **secret d'accès**. Une personne qui le reçoit peut consulter
  ce résumé. Ne pas le diffuser publiquement.
- Les jets de suivi ne sont pas conservés dans `localStorage` ou cookies
  persistants. Une fois l'onglet fermé, le lien privé sauvegardé par la
  cliente est son seul moyen de consultation sans compte.
- Une coupure réseau pendant la soumission garde la même clé d'idempotence
  et le même jeton en mémoire **tant que l'onglet reste ouvert**.
  Après fermeture de l'onglet avant réception, une récupération avec
  vérification du téléphone (OTP) sera une **tranche ultérieure**.
- Pas de commande consultable par numéro ou par téléphone, pas d'URL HTTP GET
  contenant le jeton, pas de paiement, pas de changement des commandes
  de groupage. Ni numéro de commande ni lien de suivi ne prouvent qu'une
  commande est payée.
- L'API est publique : une limitation de débit sur l'edge, supervision
  et processus d'expiration/révocation de jetons sont requis avant une
  exposition commerciale large. La force du jeton rend une supposition
  par brute force non réaliste, mais n'empêche pas le déni de service.

## Configuration
`GROUPIA_DIRECT_CHECKOUT_ENABLED=false` reste la valeur par défaut.
Le suivi d'une commande déjà créée fonctionne même si le flag checkout
est désactivé ensuite : ne pas priver les clientes de l'accès à leur reçu.

## Tests
- Base PostgreSQL : seule une empreinte hexadécimale est stockée ; lien
  valide => statut et total, numéro seul/jeton différent => introuvable.
- Rejeu idempotent de la commande sans duplication ; jeton différent sous
  la même clé => conflit.
- Angular : statut public sans coordonnées personnelles, rejet de lien
  absent, fragment de 256 bits, annulation des réponses périmées, appel POST.
- GitHub Security, Maven verify/Spotless, lint/tests/build, Playwright.
