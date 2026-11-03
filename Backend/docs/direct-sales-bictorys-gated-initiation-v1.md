# Direct sales — Bictorys guarded initiation V1

## Security contract

Endpoint: `POST /api/public/direct-orders/payments`.

Input: JSON object `{ "token": "<256-bit recovery capability>" }` and mandatory
`Idempotency-Key` request header. The browser cannot supply an order ID,
organization ID, financial request ID, payment amount, provider, payment account,
customer data or currency.

The server:
1. Locates the order only by its domain-separated hash. A rotated or unrecognised
   capability cannot reach any payment initiation.
2. Requires a persisted, accepted XOF total and a prepared direct-order obligation.
3. Selects the **BICTORYS** provider and builds the checkout context entirely
   from the saved order contact snapshot.
4. Enters the existing payment module's durable transactional claim. Its
   `DirectPaymentClaimGuard` re-runs `DirectPaymentObligations.prepare`, which
   locks the storefront and the order and revalidates line totals, accepted
   shipping quote, tenant, expiry and current delivery coverage. The locked
   financial obligation is then verified for exact matching reference,
   target, amount, currency, payer and acceptance state.
5. Rechecks the recovery token **hash under the locked order**, so a capability
   rotated concurrently cannot initiate after its revocation. The generic
   payment initiation API is incapable of bypassing the token-bound direct-only
   API, even with the feature flag on.
6. Writes an idempotent payment attempt under the payment-request lock. A
   conflicting or unresolved attempt blocks any second provider initiation.
   Only after that transaction commits can the existing Bictorys adapter
   initiate an external charge.
7. Delegates all eventual success evidence to the existing authenticated
   webhook/reconciliation path and immutable direct-order settlement listener.
   Neither an initiation response nor a redirect URL is proof of payment.

## Deployment gate — IMPORTANT

`groupia.direct-sales.payment-initiation.enabled=false` is the default and
remains **OFF** on all environments until sandbox trials, payment settlement
evidence, reconciliation, cancellation races, merchant operations, refund
procedures and financial account routing are reviewed together.

Direct payments require an explicitly opted-in deployment of:
- the new initiation feature gate,
- a configured, available Bictorys adapter with suitable sandbox account,
- `groupia.direct-sales.payment-obligation.auto-prepare.enabled=true` for
  eligible new orders, and
- an existing enabled direct checkout with valid 256-bit recovery token.

No admin or public endpoint can bypass the default-off gate by choosing a
provider. This PR is a **backend foundation**, not a go-live authorization,
not a payout implementation, and not a frontend 'Pay now' release.

## Operational invariant

Payment claim, seller cancellation and obligation preparation take locks in the
same order: **storefront → direct order → financial obligation**. The network
call to Bictorys is outside the transaction. If the claim wins the race, the
unresolved attempt prevents unpaid cancellation; if the cancellation wins, the
claim's server-side revalidation refuses to charge.

All responses are non-cacheable. The bearer token is never used as a URL
parameter and is not included in payment logs. The provider checkout URL can
be used only through the Bictorys allowlist validated by the existing adapter.
