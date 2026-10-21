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
- Monthly buckets are present even when empty. Prior-year months are aligned to the matching bucket.
- Percentage variation returns **null** when the comparison base is zero, not a misleading 100% increase.
- Average goods basket returns **null** when no orders were accepted.
- `Cache-Control: no-store` on the private endpoint.
- No customer name, phone, tracking token or payment provider details are returned.

The monthly query performs grouped aggregation in PostgreSQL, filtered by tenant, status, currency and bounded confirmation timestamp, and runs under a consistent read-only REPEATABLE_READ snapshot. It never downloads all orders for client-side aggregation.

## Next sprint, not this PR

The Angular UX/UI V2 dashboard should consume this endpoint to show gross accepted goods value and monthly trends. Keep charts and actions separate. Do not label gross goods value `bénéfice` or `argent reçu`. Do not invent graphs for empty merchants; render a useful empty state. Additional top-groupages, settled payouts, net refunds and urgency metrics require separate data contracts and permission checks.
