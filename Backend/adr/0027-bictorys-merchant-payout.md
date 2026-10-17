# ADR 0027 — Bictorys Merchant Payout V1

Status: accepted for sandbox implementation only.

Payment owns provider integration; Settlement owns merchant balances, destinations,
reservations and ledger policy. Settlement calls only `payment::api` through its
domain port. Payment never depends on Settlement.

`OutgoingPayoutOperations` registers an immutable operation before network I/O,
binds provider transaction ownership, initiates, verifies and exposes/acknowledges
durable evidence. The shared registry has unique provider/account/reference,
provider/account/idempotency-key, operation kind/id and provider/account/transaction.
PostgreSQL transaction locks coordinate independent instances. Historical collisions
abort migration 027; no history is silently discarded. Migrations 001–026 are unchanged.

The existing Refund HTTP adapter supplies the common transport, credentials and
outgoing gateway. Refund's historical envelope remains supported. Merchant evidence
requires the registered identity, transaction, currency, operator, compatible
principal and authenticated success. A supplied recipient must match the immutable
destination. Controlled Senegal phone normalization accepts only full +221/221 forms.
Missing or incompatible evidence remains reviewable, never an inferred success.

Initiation is not ledger settlement: even a POST response with status 0 only binds
the transaction and leaves PENDING. An ambiguous response, timeout, reset, 5xx, HTML
or malformed JSON keeps funds reserved. There is no automatic POST retry, including
with the same key. A later fully correlated webhook can resolve the uncertainty.
Reconciliation only GETs an existing transaction. Without its ID an operator is
required; no new payout is sent. Provider HTTP refusals currently stay conservatively
in review rather than releasing funds based on an unverified error-message contract.

The normal path is authenticated webhook evidence. GET detail uses the same normalized
merchant parser for reconciliation. `abs(amount)` is used only after identifying a
registered outgoing operation. Fees are never used to repair an incompatible amount:
`PAYOUT_AMOUNT_SEMANTICS_UNCONFIRMED` keeps the reservation and produces no debit.
Provider fees belong to Groupia; the merchant ledger debits the principal only.
Nonzero customer fees are also unconfirmed semantics in this version.

The existing `refund_webhook_receipts` table becomes the single outgoing journal.
Legacy observations stay intact; normalized evidence and operation identity are
additional columns. Raw payloads, keys and secrets are not persisted. Recipient
phone, when present, is retained only in minimal evidence needed for correlation.
Receipt insertion and a public Modulith event publication commit together. Consumers
are idempotent; persistent failed publications use the existing bounded event recovery
and operational resubmission procedure. Malformed and unknown receipts stay in review.

HMAC authenticates timestamp + dot + exact raw body with HmacSHA256, using the existing
webhook secret. Timestamp is Unix milliseconds, allowed within +/- five minutes.
Partial headers, invalid signatures or contradictory duplicate headers fail closed.
No HMAC downgrade is permitted. Static X-Secret-Key is supported only with no HMAC
headers. Both comparisons use constant-time digest comparison. The same authentication
applies to incoming charges and outgoing payouts.

OWNER alone receives EXECUTE_PAYOUTS. OWNER alone changes the destination. MANAGER
and VIEWER retain their permitted financial reads; MANAGE_SETTLEMENTS no longer
authorizes a money transfer. Frontend confirmation never supplies an authoritative
amount or proof. A lost browser response causes reload/review, never automatic retry.
Context/session generations prevent old responses from changing another boutique.

SUCCEEDED and the single PAYOUT:<id> ledger debit commit atomically. A contradictory
ACK, later evidence or reversal records an incident without rewriting success or
removing the debit. No automatic reversal accounting is introduced. FAILED/CANCELLED
requires complete correlated evidence and an acceptable timestamp under the existing
conservative state rules. Missing/invalid chronology stays reserved for review.

Sandbox URL, test_secret credentials, secrets/timeouts validation and disabled-by-default
kill switches remain. Never enable production credentials in this slice. No batch
payouts, payout cron, completion-triggered transfer or automatic global balance payout.

Preproduction prerequisites: capture anonymized real sandbox ACK/webhook/detail samples;
confirm observed amount and fee semantics; establish operator reconciliation/reversal
procedures, monitoring and treasury funding; introduce recent/step-up authentication
before prolonged sessions are used for production transfers. Metrics are operational
signals, not a financial ledger, and carry no per-customer identifiers.
