# Groupia Business Analytics API V1

Status: experimental on `business-analytics-api-v1`; do not release before CI and functional review.

## Merchant meaning

This API measures the **historical goods value of commercially accepted orders**, NOT net profit, received cash, available payout, or shipping revenue. For each order it uses the immutable `orders.goods_subtotal`, excluding `platform_fee_amount` and `initial_shipping_charge_amount`. A successful initial payment transitions the order to `CONFIRMED`; the subsequent `COMPLETED` status is also accepted and **must not count a second sale**.

Only orders with status `CONFIRMED` or `COMPLETED` and a non-null `confirmed_at` contribute. Grouping is by `confirmed_at` in **Africa/Dakar**, not by checkout date, pickup date or payout date. Values are XOF decimal numbers represented as JSON numbers; there are no fabricated revenue/profit numbers.

Potential adjustments or refunds should not be treated as zero or automatically subtracted: **these figures are gross accepted goods order value**, not final merchant receivables or revenue recognized after refunds. Financially adjusted metrics require a separately verified contract with payment/settlement modules.

## Endpoint and periods

`GET /api/organizations/{organizationId}/analytics/business?months=12`

- Authenticated gérante must have `VIEW_ORDERS` on **this exact organization**, enforced on every request.
- Allowed `months`: **3, 6, 12**, default 12; invalid values return 400.
- Current interval: midnight on the first day of the oldest selected month through current instant (exclusive); includes the current partial month.
- Comparison: the equivalent months and **same local wall-clock instant one year earlier**, avoiding current partial-month / previous full-month miscomparisons.
- February 29 maps to February 28 at the same time in a non-leap comparison year (`minusYears(1)`). At the exact start of a month the current bucket is present but empty; interval ends are always exclusive.
- Monthly buckets are present even when empty. Prior-year months are aligned to the matching bucket.
- Percentage variation returns **null** when the comparison base is zero, not a misleading 100% increase.
- Average goods basket returns **null** when no orders were accepted.
- `Cache-Control: no-store` on the private endpoint.
- No customer name, phone, tracking token or payment provider details are returned.

The monthly query performs grouped aggregation in PostgreSQL, filtered by tenant, status, currency and bounded confirmation timestamp, and runs under a consistent read-only REPEATABLE_READ snapshot. It never downloads all orders for client-side aggregation.

## PR #28 technical review and migration regression

The backend log of Actions run `39534574311` contains 1,154 tests, 14 failures and no errors. The failures span 13 migration test classes: `DistributionChecksumUpgradeTests` fails for both accepted historical variants. Their `id not like ...` exclusions stopped at `029-%`, so the actual result contained the legitimate new `030-business-analytics-index` row in addition to the unchanged historical rows. Liquibase itself applied the migrations successfully; these were assertion failures, not historical checksum validation failures.

`MigrationHistoryAssertions` now snapshots the persisted identity `(id, author, filename)`, checksum, execution order and execution type **before** an upgrade. Afterward every historical record must still match exactly, without duplicates. Added identities must be declared by the current master changelog parsed with Liquibase; persisted checksums must be present. No historical checksum is recalculated, overwritten or cleared. Dedicated negative tests reject changed/missing checksums, missing records, author/file substitution, duplicate identities, changed execution metadata and undeclared additions. Existing data-preservation and database-constraint assertions remain in place.

The isolated 029 → 030 test verifies unchanged historical records, order/customer facts, columns and constraints, the partial index predicate, planner eligibility and an idempotent second update. Its target changelog ends at 030 so future legitimate schema additions do not change what this index-specific test measures.

Audit findings and coverage:

- Authorization remains centralized through `OrganizationAccess.requirePermission(..., VIEW_ORDERS)` before any analytics read. HTTP integration tests use actual account/membership records, cover the four permitted roles, foreign and nonexistent organizations, revoked membership, disabled/unknown users and anonymous requests. Private responses have `Cache-Control: no-store` and contain only aggregates and period metadata.
- SQL sums `goods_subtotal` once per eligible order without joins. Read-model tests distinguish placement from confirmation dates, include lower bounds, exclude upper bounds, isolate organizations/statuses/currency and prove completing an already confirmed order does not add a second sale. Platform/shipping amounts are deliberately different in the fixtures.
- Service tests cover 3/6/12-month windows, month/year boundaries, leap days, empty buckets, partial comparison periods, zero bases, negative changes and HALF_UP XOF basket rounding.
- Refunds/corrections are **not an available metric** in this V1: no zero-valued refund field is fabricated. The gross, unadjusted meaning is documented here and in the OpenAPI response schema. Consumers must not label it net revenue, profit or available payouts.
- Two aggregate queries return at most 12 rows each under one REPEATABLE_READ snapshot. The new index is additive and matches the tenant/date and partial eligibility predicates. No other module's persistence is queried.

Deployment limit: regular PostgreSQL `CREATE INDEX` can block concurrent writes while building on a large `orders` table. Schedule the migration according to production volume and measure it before deployment; the planner test uses 2,000 older orders to verify date-selective index usage without forcing planner settings, not to claim production latency. No concurrent-index rollout or refund-adjusted metric is introduced by this correction.

## Next sprint, not this PR

The Angular UX/UI V2 dashboard should consume this endpoint to show gross accepted goods value and monthly trends. Keep charts and actions separate. Do not label gross goods value `bénéfice` or `argent reçu`. Do not invent graphs for empty merchants; render a useful empty state. Additional top-groupages, settled payouts, net refunds and urgency metrics require separate data contracts and permission checks.
