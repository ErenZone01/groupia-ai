package sn.groupia.ordering.infrastructure.persistence;
import java.util.*;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sn.groupia.ordering.domain.model.FulfillmentEvent;
import sn.groupia.ordering.domain.repository.FulfillmentEvents;
@Repository
class JdbcFulfillmentEvents implements FulfillmentEvents {
    private final JdbcTemplate jdbc;
    JdbcFulfillmentEvents(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void append(FulfillmentEvent e){
        jdbc.update("insert into order_fulfillment_events(id,organization_id,campaign_id,order_id,type,actor_id,recorded_at,note) values (?,?,?,?,?,?,?,?)",
            e.id(),e.organizationId(),e.campaignId(),e.orderId(),e.type().name(),e.actorId(),Timestamp.from(e.recordedAt()),e.note());
    }
    public List<FulfillmentEvent> forCampaign(UUID org,UUID campaign){
        return jdbc.query("select * from order_fulfillment_events where organization_id=? and campaign_id=? order by recording_sequence",
            (r,n)->new FulfillmentEvent(r.getObject("id",UUID.class),org,campaign,r.getObject("order_id",UUID.class),
                FulfillmentEvent.Type.valueOf(r.getString("type")),r.getObject("actor_id",UUID.class),r.getTimestamp("recorded_at").toInstant(),r.getString("note")),org,campaign);
    }
}
