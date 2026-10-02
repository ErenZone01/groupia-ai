package sn.groupia.shipping.infrastructure.persistence;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sn.groupia.shared.api.ApiException;
import sn.groupia.shipping.api.*;
import sn.groupia.shipping.api.dto.LogisticsFactView;
import sn.groupia.shipping.domain.model.Shipment;
import sn.groupia.shipping.domain.repository.ShipmentRepository;
@Repository
class JdbcShipments implements ShipmentRepository {
    private final JdbcTemplate jdbc;
    JdbcShipments(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Shipment> findAll(Set<UUID> ids) {
        if(ids.isEmpty())return List.of();
        return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(
            "select * from shipments where grouping_campaign_id in (:ids)",Map.of("ids",ids),(r,n)->map(r));
    }
    public Optional<Shipment> find(UUID org,UUID campaign) {
        return jdbc.query("select * from shipments where organization_id=? and grouping_campaign_id=?",
            (r,n)->map(r),org,campaign).stream().findFirst();
    }
    private Shipment map(ResultSet r)throws SQLException {
        return new Shipment(r.getObject("id",UUID.class),
            r.getObject("organization_id",UUID.class),
            r.getObject("grouping_campaign_id",UUID.class),
            TransportMode.valueOf(r.getString("transport_mode")),
            instant(r,"departed_at"),
            instant(r,"expected_arrival_at"),
            instant(r,"arrived_at"),
            r.getString("tracking_reference"),
            r.getString("note"),
            instant(r,"created_at"),
            instant(r,"updated_at"),
            r.getString("provider_name"),
            r.getString("provider_contact"),
            instant(r,"departure_confirmed_at"),
            instant(r,"arrival_confirmed_at"),
            r.getLong("version"));
    }
    public void insert(Shipment s) {
        jdbc.update("insert into shipments(id,organization_id,grouping_campaign_id,transport_mode,departed_at,expected_arrival_at,arrived_at,tracking_reference,note,created_at,updated_at,provider_name,provider_contact,departure_confirmed_at,arrival_confirmed_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            s.id(),s.organizationId(),s.campaignId(),s.transportMode().name(),stamp(s.departedAt()),stamp(s.expectedArrivalAt()),stamp(s.arrivedAt()),s.trackingReference(),s.note(),stamp(s.createdAt()),stamp(s.updatedAt()),s.providerName(),s.providerContact(),stamp(s.departureConfirmedAt()),stamp(s.arrivalConfirmedAt()),s.version());
    }
    public void update(Shipment s) {
        int count=jdbc.update("update shipments set departed_at=?,expected_arrival_at=?,arrived_at=?,tracking_reference=?,note=?,updated_at=?,provider_name=?,provider_contact=?,departure_confirmed_at=?,arrival_confirmed_at=?,version=? where id=? and organization_id=? and version=?",
            stamp(s.departedAt()),stamp(s.expectedArrivalAt()),stamp(s.arrivedAt()),s.trackingReference(),s.note(),stamp(s.updatedAt()),s.providerName(),s.providerContact(),stamp(s.departureConfirmedAt()),stamp(s.arrivalConfirmedAt()),s.version(),s.id(),s.organizationId(),s.version()-1);
        if(count!=1)throw new ApiException(409,"SHIPMENT_VERSION_CONFLICT","Le dossier logistique a changé. Actualisez-le.");
    }
    public void append(LogisticsFactView f) {
        jdbc.update("insert into shipment_facts(id,organization_id,shipment_id,shipment_version,type,occurred_at,recorded_at,source,actor_id,note,previous_expected_arrival_at,expected_arrival_at,provider_name,provider_contact,tracking_reference,legacy) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            f.id(),f.organizationId(),f.shipmentId(),f.shipmentVersion(),f.type().name(),stamp(f.occurredAt()),stamp(f.recordedAt()),f.source().name(),f.actorId(),f.note(),stamp(f.previousExpectedArrivalAt()),stamp(f.expectedArrivalAt()),f.providerName(),f.providerContact(),f.trackingReference(),f.legacy());
    }
    public List<LogisticsFactView> timeline(UUID org,UUID shipment) {
        return jdbc.query("select * from shipment_facts where organization_id=? and shipment_id=? order by shipment_version",
            (r,n)->new LogisticsFactView(r.getObject("id",UUID.class),
                r.getObject("organization_id",UUID.class),
                r.getObject("shipment_id",UUID.class),
                r.getLong("shipment_version"),
                LogisticsFactType.valueOf(r.getString("type")),
                instant(r,"occurred_at"),
                instant(r,"recorded_at"),
                LogisticsSource.valueOf(r.getString("source")),
                r.getObject("actor_id",UUID.class),
                r.getString("note"),
                instant(r,"previous_expected_arrival_at"),
                instant(r,"expected_arrival_at"),
                r.getString("provider_name"),
                r.getString("provider_contact"),
                r.getString("tracking_reference"),
                r.getBoolean("legacy")),org,shipment);
    }
    private static Instant instant(ResultSet r,String field)throws SQLException{var t=r.getTimestamp(field);return t==null?null:t.toInstant();}
    private static Timestamp stamp(Instant at){return at==null?null:Timestamp.from(at);}
}
