package sn.groupia.shipping.infrastructure.persistence;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sn.groupia.shared.api.ApiException;
import sn.groupia.shipping.api.TransportMode;
import sn.groupia.shipping.domain.model.Shipment;
import sn.groupia.shipping.domain.repository.ShipmentRepository;
@Repository
class JdbcShipments implements ShipmentRepository {
    private final JdbcTemplate jdbc;
    JdbcShipments(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Shipment> findAll(Set<UUID> ids) {
        if(ids.isEmpty())return List.of();
        return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(
            "select * from shipments where grouping_campaign_id in (:ids)",Map.of("ids",ids),(r,n)->
                new Shipment(r.getObject("id",UUID.class),r.getObject("organization_id",UUID.class),r.getObject("grouping_campaign_id",UUID.class),
                TransportMode.valueOf(r.getString("transport_mode")),instant(r,"departed_at"),instant(r,"expected_arrival_at"),
                instant(r,"arrived_at"),r.getString("tracking_reference"),r.getString("note"),instant(r,"created_at"),instant(r,"updated_at")));
    }
    public Optional<Shipment> find(UUID org,UUID campaign) {
        return jdbc.query("select * from shipments where organization_id=? and grouping_campaign_id=?",(r,n)->
            new Shipment(r.getObject("id",UUID.class),r.getObject("organization_id",UUID.class),
                r.getObject("grouping_campaign_id",UUID.class),TransportMode.valueOf(r.getString("transport_mode")),
                instant(r,"departed_at"),instant(r,"expected_arrival_at"),instant(r,"arrived_at"),
                r.getString("tracking_reference"),r.getString("note"),instant(r,"created_at"),instant(r,"updated_at")),
            org,campaign).stream().findFirst();
    }
    public void insert(Shipment s) {
        jdbc.update("""
            insert into shipments(id,organization_id,grouping_campaign_id,transport_mode,departed_at,
                expected_arrival_at,tracking_reference,note,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?)
            """,s.id(),s.organizationId(),s.campaignId(),s.transportMode().name(),stamp(s.departedAt()),
            stamp(s.expectedArrivalAt()),s.trackingReference(),s.note(),stamp(s.createdAt()),stamp(s.updatedAt()));
    }
    public void recordArrival(Shipment s) {
        int count=jdbc.update("update shipments set arrived_at=?,updated_at=? where id=? and organization_id=? and arrived_at is null",
            stamp(s.arrivedAt()),stamp(s.updatedAt()),s.id(),s.organizationId());
        if(count!=1)throw new ApiException(409,"SHIPMENT_ALREADY_ARRIVED","L’arrivée est déjà enregistrée.");
    }
    private static Instant instant(ResultSet r,String field)throws SQLException{var t=r.getTimestamp(field);return t==null?null:t.toInstant();}
    private static Timestamp stamp(Instant at){return at==null?null:Timestamp.from(at);}
}
