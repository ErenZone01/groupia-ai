package sn.groupia.procurement.infrastructure.persistence;
import java.util.*;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
import sn.groupia.ordering.api.dto.SupplierProjectionLine;
import sn.groupia.procurement.domain.model.SupplierOrder;
import sn.groupia.procurement.domain.repository.SupplierOrders;
@Repository
class JdbcSupplierOrders implements SupplierOrders {
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    JdbcSupplierOrders(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public Optional<SupplierOrder> find(UUID org,UUID campaign) {
        return jdbc.query("select * from supplier_orders where organization_id=? and grouping_campaign_id=?", (rs,n)->
            new SupplierOrder(rs.getObject("id",UUID.class),rs.getObject("organization_id",UUID.class),rs.getObject("grouping_campaign_id",UUID.class),
                rs.getLong("source_campaign_version"),rs.getObject("ordered_by",UUID.class),rs.getTimestamp("ordered_at").toInstant(),
                rs.getLong("accepted_order_count"),rs.getLong("total_units"),rs.getBigDecimal("goods_amount"),rs.getString("currency"),
                json.readValue(rs.getString("lines"),new TypeReference<List<SupplierProjectionLine>>() {}),
                Arrays.asList((UUID[])rs.getArray("accepted_order_ids").getArray())),org,campaign).stream().findFirst();
    }
    public void insert(SupplierOrder p) {
        jdbc.update(connection->{
            var s=connection.prepareStatement("""
                insert into supplier_orders(id,organization_id,grouping_campaign_id,source_campaign_version,ordered_by,ordered_at,
                  accepted_order_count,total_units,goods_amount,currency,lines,accepted_order_ids) values (?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
                """);
            s.setObject(1,p.id());s.setObject(2,p.organizationId());s.setObject(3,p.groupingCampaignId());s.setLong(4,p.sourceCampaignVersion());
            s.setObject(5,p.orderedBy());s.setTimestamp(6,Timestamp.from(p.orderedAt()));s.setLong(7,p.acceptedOrderCount());
            s.setLong(8,p.totalUnits());s.setBigDecimal(9,p.goodsAmount());s.setString(10,p.currency());
            s.setString(11,json.writeValueAsString(p.lines()));s.setArray(12,connection.createArrayOf("uuid",p.acceptedOrderIds().toArray()));
            return s;
        });
    }
    public long anomalyCount(UUID id){return jdbc.queryForObject("select count(*) from supplier_order_anomalies where supplier_order_id=?",Long.class,id);}
    public void flagLateAcceptance(UUID org,UUID campaign,UUID orderId,UUID eventId,Instant detectedAt) {
        jdbc.update("""
            insert into supplier_order_anomalies(supplier_order_id,customer_order_id,event_id,detected_at)
            select id,?,?,? from supplier_orders where organization_id=? and grouping_campaign_id=?
              and not (? = any(accepted_order_ids))
            on conflict (supplier_order_id,customer_order_id) do nothing
            """,orderId,eventId,Timestamp.from(detectedAt),org,campaign,orderId);
    }
}
