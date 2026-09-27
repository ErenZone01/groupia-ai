package sn.groupia.ordering.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.*;
import java.util.Arrays;
import sn.groupia.ordering.api.dto.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import sn.groupia.ordering.api.dto.SupplierSummaryLine;
import sn.groupia.ordering.api.dto.SupplierSummaryView;
import sn.groupia.ordering.application.query.SupplierSummaryReadModel;
import sn.groupia.ordering.domain.model.OrderStatus;

@Repository
class JdbcSupplierSummary implements SupplierSummaryReadModel {
    // One statement gives lines and totals the same PostgreSQL MVCC snapshot.
    private static final String SQL = """
        WITH scoped AS MATERIALIZED (
            SELECT id, status, payment_deadline FROM orders
            WHERE organization_id = :organization AND campaign_id = :campaign
        ), accepted AS MATERIALIZED (
            SELECT id FROM scoped WHERE status IN (:acceptedStatuses)
        ), grouped AS (
            SELECT i.product_id_snapshot, i.product_variant_id_snapshot, i.grouping_offer_id,
                   i.product_name_snapshot, i.product_description_snapshot, i.commercial_label_snapshot,
                   i.variant_attributes_snapshot, i.sku_snapshot, i.sale_unit_snapshot,
                   sum(i.quantity)::bigint AS quantity, sum(i.line_total) AS goods_amount
            FROM order_items i JOIN accepted a ON a.id = i.order_id
            WHERE i.organization_id = :organization AND i.campaign_id = :campaign
            GROUP BY i.product_id_snapshot, i.product_variant_id_snapshot, i.grouping_offer_id,
                     i.product_name_snapshot, i.product_description_snapshot, i.commercial_label_snapshot,
                     i.variant_attributes_snapshot, i.sku_snapshot, i.sale_unit_snapshot
        ), totals AS (
            SELECT (SELECT count(*) FROM accepted) AS accepted_count,
                   (SELECT array_agg(id ORDER BY id) FROM accepted) AS accepted_ids,
                   (SELECT count(*) FROM scoped WHERE status = 'PENDING_PAYMENT' AND payment_deadline > :decisionAt) AS pending_count,
                   coalesce(sum(quantity), 0)::bigint AS total_units,
                   coalesce(sum(goods_amount), 0) AS total_goods
            FROM grouped
        )
        SELECT t.accepted_count, t.total_units, t.total_goods, CASE WHEN row_number() OVER () = 1 THEN t.accepted_ids END AS accepted_ids, t.pending_count, g.*
        FROM totals t LEFT JOIN grouped g ON true
        ORDER BY g.product_name_snapshot, g.product_id_snapshot, g.product_variant_id_snapshot,
                 g.grouping_offer_id, g.sale_unit_snapshot, g.variant_attributes_snapshot::text,
                 g.commercial_label_snapshot, g.sku_snapshot, g.product_description_snapshot
        """;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    JdbcSupplierSummary(NamedParameterJdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public SupplierSummaryView read(UUID organizationId, UUID campaignId) {
        return projection(organizationId,campaignId,clock.instant()).summary();
    }
    @Override
    public void lockOrders(UUID organizationId, UUID campaignId) {
        jdbc.query("select id from orders where organization_id=:org and campaign_id=:campaign order by id for update",
                Map.of("org",organizationId,"campaign",campaignId), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {});
    }
    @Override
    public SupplierProjection projection(UUID organizationId, UUID campaignId, Instant decisionAt) {
        var parameters = Map.of("organization", organizationId, "campaign", campaignId, "decisionAt", java.sql.Timestamp.from(decisionAt),
                "acceptedStatuses", OrderStatus.commerciallyAcceptedStatuses().stream().map(Enum::name).toList());
        return jdbc.query(SQL, parameters, rs -> {
            long count = 0, units = 0, pending = 0;
            java.util.List<UUID> acceptedIds = java.util.List.of();
            BigDecimal amount = BigDecimal.ZERO;
            var lines = new ArrayList<SupplierProjectionLine>();
            while (rs.next()) {
                count = rs.getLong("accepted_count");
                pending = rs.getLong("pending_count");
                if(rs.getArray("accepted_ids") != null) acceptedIds = Arrays.asList((UUID[]) rs.getArray("accepted_ids").getArray());
                units = rs.getLong("total_units");
                amount = rs.getBigDecimal("total_goods").setScale(0);
                if (rs.getObject("product_variant_id_snapshot") != null) {
                    Map<String, String> attributes = json.readValue(rs.getString("variant_attributes_snapshot"), new TypeReference<>() {});
                    lines.add(new SupplierProjectionLine(rs.getObject("product_id_snapshot",UUID.class), rs.getObject("product_variant_id_snapshot",UUID.class),
                            rs.getObject("grouping_offer_id",UUID.class), new SupplierSummaryLine(rs.getString("product_name_snapshot"),
                            rs.getString("product_description_snapshot"), rs.getString("commercial_label_snapshot"),
                            attributes, rs.getString("sku_snapshot"), rs.getString("sale_unit_snapshot"),
                            rs.getLong("quantity"), rs.getBigDecimal("goods_amount").setScale(0))));
                }
            }
            // orders.ck_orders_currency enforces XOF; all line totals are integral XOF.
            return new SupplierProjection(campaignId, count, units, amount, "XOF", lines, acceptedIds, pending);
        });
    }
}
