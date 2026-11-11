# Diagnostic : le paiement SasPay hébergé ne présente aucun lien

## Symptôme

Exemple local : commande de vente directe `DIR-000000000018`, montant
1000 XOF, la page client affiche « Vérification manuelle nécessaire »
sans lien checkout. Ne pas réessayer un paiement LIVE tant que l'on ne
sait pas si une session a été créée chez le prestataire.

Ce message est volontairement rendu par Groupia lorsque
`payments.reconciliation_required=true`; ce n'est **pas** une preuve
de rejet ou de débit. Les unités et tests de cette documentation ne
font aucune requête d'encaissement réelle.

## Contrôle en lecture seule dans PostgreSQL

```sql
SELECT
  p.id AS tentative,
  p.status,
  p.reconciliation_required,
  p.reconciliation_reason,
  (p.provider_charge_id IS NOT NULL) AS session_saspay_enregistree,
  (p.checkout_url IS NOT NULL) AS lien_enregistre,
  (p.provider_transaction_id IS NOT NULL) AS transaction_identifiee,
  p.initiated_at,
  p.last_checked_at
FROM payments p
JOIN payment_requests pr ON pr.id = p.payment_request_id
JOIN direct_orders d
  ON d.id = pr.direct_order_id AND d.organization_id = pr.organization_id
WHERE d.order_number = 'DIR-000000000018'
  AND p.provider = 'SASPAY'
ORDER BY p.initiated_at DESC;
```

Ne jamais publier une URL de checkout réelle, un lien privé client,
un téléphone, une clé ou un secret. Les booléens suffisent.

### Interprétation

| Session enregistrée | Raison | Action |
|---|---|---|
| `true` | `PROVIDER_STATUS_UNKNOWN` | Une session a probablement été créée, mais l'URL a été rejetée ou absente. Vérifier `SASPAY_CHECKOUT_HOSTS` (le host officiel est `pay.saspay.me`). Après correction, le poller peut consulter `GET /checkout-sessions/{id}/` et **restaurer uniquement** une URL autorisée liée au bon marchand, paiement et montant. Aucune nouvelle session ne doit être créée. |
| `false` | `PROVIDER_STATE_UNCERTAIN` | Cas indécidable depuis la base. Lire le code fixe des logs backend, puis retrouver la session potentielle en **GET uniquement** ou dans le dashboard SasPay avant toute action ; ne jamais remettre le statut à zéro par SQL. |
| `true` | `PROVIDER_STATE_UNCERTAIN` | Session connue mais état financier incertain. L'URL seule ne peut lever ce blocage. Vérifier le statut et la transaction côté fournisseur. |
| `false` | autre | Vérifier le journal et le périmètre du compte ; aucun reset automatique. |

## Logs backend (ne pas partager les secrets)

Chercher les lignes contenant :

- `Payment initiation configuration rejected provider=SASPAY reason=...`
- `Payment initiation requires reconciliation provider=SASPAY reason=...`
- `SasPay hosted checkout URL rejected for session (host not approved)`

Les codes les plus probables :

- `SASPAY_RETURN_URL_INVALID` : LIVE exige une URL de retour
  **HTTPS** stable ; renseigner `GROUPIA_PUBLIC_BASE_URL` avec le
  domaine réellement ouvert aux clients (pas `http://localhost:4200`).
- `SASPAY_HOSTED_CHECKOUT_HOST_NOT_ALLOWED` :
  `SASPAY_CHECKOUT_HOSTS` doit inclure `pay.saspay.me`.
- `SASPAY_HOSTED_CONTACT_REQUIRED` : nom ou email invalide.
- `SASPAY_HTTP_403` : scope marchand PAYIN ou permission refusée.
- `SASPAY_TRANSPORT_UNCERTAIN` : requête potentiellement envoyée ;
  ne pas relancer sans rapprochement.
- `SASPAY_HOSTED_SESSION_MISMATCH` : réponse fournisseur reçue mais
  données incompatible ; risque de session existante.
- `SASPAY_INVALID_CHECKOUT_URL` : hôte HTTPS non autorisé par le
  backend, sans preuve que la session n'existe pas.

La PR #86 comprend désormais une garde **sans réseau** sur
`GROUPIA_PUBLIC_BASE_URL` et `SASPAY_CHECKOUT_HOSTS` avant
toute création de tentative. Une erreur de configuration statique
retourne 503 au client et n'insère pas de tentative incertaine.

Le poller, lui, ne crée jamais de session. Il peut récupérer une
URL après une réponse provider vérifiée ; uniquement une revue
`PROVIDER_STATUS_UNKNOWN` liée au lien peut être levée.
`PROVIDER_STATE_UNCERTAIN`, `UNKNOWN_REFERENCE` ou tout
autre problème financier reste bloqué.

## Validation après correctifs

```powershell
git pull --ff-only origin saspay-payin-v1
cd Backend
$env:SPRING_PROFILES_ACTIVE = "test"
.\mvnw.cmd spotless:check
.\mvnw.cmd "-Dtest=SaspayHostedContractTests,PaymentHostedSessionTests,SaspayContractTests" test
```

Tous les tests de développement utilisent des réponses SasPay
synthétiques. Ne pas faire de paiement LIVE pour valider ce correctif.
