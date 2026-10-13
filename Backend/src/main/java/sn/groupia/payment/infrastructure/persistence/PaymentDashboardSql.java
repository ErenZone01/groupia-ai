package sn.groupia.payment.infrastructure.persistence;

/**
 * Read-only mirror of PaymentRisk + PaymentDashboardPolicy. Covered by database/Java parity tests.
 */
final class PaymentDashboardSql {
    private PaymentDashboardSql() {}

    static final String CLASSIFIED =
            """
        with scoped as (
            select r.* from payment_requests r where r.organization_id=:org %s
        ), facts as (
            select r.*, coalesce(a.in_progress,false) in_progress,
                coalesce(a.risky,false) or exists (
                    select 1 from payment_reviews v where v.payment_request_id=r.id and v.status='OPEN'
                ) or (r.settled_payment_id is not null and
                    (r.acceptance_status<>'CLOSED' or not coalesce(a.principal_ok,false)))
                or (r.acceptance_status='CLOSED' and r.settled_payment_id is null) risk
            from scoped r
            left join lateral (
                select bool_or(p.status in ('CREATED','PENDING')) in_progress,
                    bool_or(p.id=r.settled_payment_id and p.status='SUCCEEDED' and p.consistent) principal_ok,
                    bool_or(p.reconciliation_required or (p.status='SUCCEEDED' and (
                        not p.consistent or (p.id is distinct from r.settled_payment_id and not exists (
                            select 1 from payment_reviews v where v.payment_request_id=r.id
                                and v.status='RESOLVED_REFUNDED' and v.reason='DUPLICATE_PROVIDER_SUCCESS'
                                and v.anomalous_payment_id=p.id and v.principal_payment_id=r.settled_payment_id
                                and v.anomalous_provider=p.provider
                                and v.anomalous_provider_account_reference is not distinct from p.provider_account_reference
                                and v.anomalous_provider_transaction_id=p.provider_transaction_id
                                and v.observed_amount=p.confirmed_amount and v.observed_currency=p.confirmed_currency
                        ))
                    ))) risky
                from (
                    select p.*, coalesce(p.organization_id=r.organization_id
                        and p.provider_transaction_id is not null and p.provider_transaction_id !~ '^[[:space:]]*$'
                        and p.expected_amount=r.expected_amount and p.currency=r.currency
                        and p.confirmed_amount=r.expected_amount and p.confirmed_currency=r.currency,false) consistent
                    from payments p where p.payment_request_id=r.id
                ) p
            ) a on true
        ), classified as (
            select f.*, case
                when risk or (acceptance_status='OPEN' and expected_amount=0)
                    or (in_progress and not (acceptance_status='OPEN' and settled_payment_id is null
                        and expected_amount>0 and (expires_at is null or expires_at>:at))) then 'REVIEW_REQUIRED'
                when acceptance_status='CLOSED' and settled_payment_id is not null then 'SETTLED'
                when acceptance_status='OPEN' and settled_payment_id is null and expected_amount>0
                    and (expires_at is null or expires_at>:at) then 'PENDING'
                when settled_payment_id is null and (acceptance_status in ('CANCELLED','SUPERSEDED')
                    or (acceptance_status='OPEN' and expires_at<=:at)) then 'TERMINAL_UNPAID'
                else 'REVIEW_REQUIRED' end operational_status
            from facts f
        )
        """;
}
