package sn.groupia;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.ordering.application.query.BusinessAnalyticsReadModel;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class BusinessAnalyticsReadModelIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessAnalyticsReadModel analytics;

    @Test
    void groupsOnlyAcceptedGoodsInAuthorizedTenantAndConfirmationWindow() {
        // Shadow only this transaction's SQL read: no production data or FK constraints touched.
        jdbc.execute(
                "CREATE TEMP TABLE orders ON COMMIT DROP AS SELECT * FROM public.orders WITH NO DATA");
        var org = UUID.randomUUID();
        var other = UUID.randomUUID();
        insert(org, "CONFIRMED", "2026-08-31T23:59:59Z", "9999", "XOF");
        insert(org, "CONFIRMED", "2026-09-01T00:00:00Z", "300", "XOF");
        insert(org, "CONFIRMED", "2026-09-30T23:59:59Z", "1000", "XOF");
        insert(org, "COMPLETED", "2026-10-01T00:00:00Z", "2000", "XOF");
        insert(org, "PENDING_PAYMENT", "2026-10-01T00:00:00Z", "5000", "XOF");
        insert(org, "CANCELLED", "2026-10-01T00:00:00Z", "5000", "XOF");
        insert(other, "CONFIRMED", "2026-10-01T00:00:00Z", "9000", "XOF");
        insert(org, "CONFIRMED", "2026-10-01T00:00:00Z", "9000", "EUR");
        insert(org, "CONFIRMED", "2026-11-01T00:00:00Z", "9000", "XOF");
        jdbc.update(
                "INSERT INTO orders (organization_id, status, confirmed_at, goods_subtotal, currency)"
                        + " VALUES (?, 'CONFIRMED', NULL, 999, 'XOF')",
                org);

        var result =
                analytics.monthly(
                        org,
                        Instant.parse("2026-09-01T00:00:00Z"),
                        Instant.parse("2026-11-01T00:00:00Z"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).month()).isEqualTo("2026-09");
        assertThat(result.get(0).confirmedOrders()).isEqualTo(2);
        assertThat(result.get(0).goodsSales()).isEqualByComparingTo("1300");
        assertThat(result.get(1).month()).isEqualTo("2026-10");
        assertThat(result.get(1).confirmedOrders()).isEqualTo(1);
        assertThat(result.get(1).goodsSales()).isEqualByComparingTo("2000");
        assertThat(
                        analytics.monthly(
                                UUID.randomUUID(),
                                Instant.parse("2026-09-01T00:00:00Z"),
                                Instant.parse("2026-11-01T00:00:00Z")))
                .isEmpty();
        jdbc.update(
                "UPDATE orders SET status='COMPLETED' WHERE organization_id=? AND status='CONFIRMED'",
                org);
        assertThat(
                        analytics.monthly(
                                org,
                                Instant.parse("2026-09-01T00:00:00Z"),
                                Instant.parse("2026-11-01T00:00:00Z")))
                .containsExactlyElementsOf(result);
    }

    private void insert(UUID org, String status, String at, String amount, String currency) {
        jdbc.update(
                "INSERT INTO orders (organization_id, status, confirmed_at, goods_subtotal, currency, placed_at, platform_fee_amount, initial_shipping_charge_amount, initial_amount_due)"
                        + " VALUES (?, ?, ?, ?, ?, '2020-01-01T00:00:00Z', 300, 9000, 99999)",
                org,
                status,
                Timestamp.from(Instant.parse(at)),
                new BigDecimal(amount),
                currency);
    }
}
