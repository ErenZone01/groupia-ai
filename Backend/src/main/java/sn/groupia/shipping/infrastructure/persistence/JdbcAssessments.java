package sn.groupia.shipping.infrastructure.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import sn.groupia.shared.api.ApiException;
import sn.groupia.shipping.api.dto.*;
import sn.groupia.shipping.domain.model.ShippingAssessment;
import sn.groupia.shipping.domain.repository.AssessmentRepository;

@Repository
class JdbcAssessments implements AssessmentRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    JdbcAssessments(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public Optional<ShippingAssessment> find(UUID org,UUID order){
        return jdbc.query("select * from shipping_assessments where organization_id=? and order_id=?",
            (r,n)->map(r),org,order).stream().findFirst();
    }
    public List<ShippingAssessment> findAll(UUID org,UUID campaign){
        return jdbc.query("select * from shipping_assessments where organization_id=? and campaign_id=?",
            (r,n)->map(r),org,campaign);
    }
    private ShippingAssessment map(ResultSet r)throws SQLException{
        return new ShippingAssessment(r.getObject("id",UUID.class),json.readValue(r.getString("snapshot"),AssessmentSeed.class),
            r.getString("status"),r.getLong("version"),r.getObject("current_measurement_id",UUID.class),
            r.getObject("current_final_calculation_id",UUID.class),at(r,"created_at"),at(r,"updated_at"));
    }
    public void insert(ShippingAssessment a){
        jdbc.update("insert into shipping_assessments(id,organization_id,campaign_id,order_id,snapshot,status,version,created_at,updated_at) values (?,?,?,?,?::jsonb,?,?,?,?)",
            a.id(),a.snapshot().organizationId(),a.snapshot().campaignId(),a.snapshot().orderId(),
            json.writeValueAsString(a.snapshot()),a.status(),a.version(),stamp(a.createdAt()),stamp(a.updatedAt()));
    }
    public void update(ShippingAssessment a){
        int count=jdbc.update("update shipping_assessments set status=?,version=?,current_measurement_id=?,current_final_calculation_id=?,updated_at=? where id=? and version=?",
            a.status(),a.version(),a.currentMeasurementId(),a.currentFinalCalculationId(),stamp(a.updatedAt()),a.id(),a.version()-1);
        if(count!=1)throw new ApiException(409,"SHIPPING_VERSION_CONFLICT","Le transport a changé. Actualisez la commande.");
    }
    public void append(ShippingMeasurementView m){
        jdbc.update("insert into shipping_measurements(id,assessment_id,information,actor_id,recorded_at,supersedes_measurement_id) values (?,?,?::jsonb,?,?,?)",
            m.id(),m.assessmentId(),json.writeValueAsString(m.information()),m.actorId(),stamp(m.recordedAt()),m.information().supersedesMeasurementId());
    }
    public void append(ShippingCalculationView c){
        jdbc.update("insert into shipping_calculations(id,assessment_id,revision,kind,measurement_id,explanation,actor_id,occurred_at,recorded_at,note,supersedes_calculation_id,correction_reason) values (?,?,?,?,?,?::jsonb,?,?,?,?,?,?)",
            c.id(),c.assessmentId(),c.revision(),c.kind(),c.measurementId(),json.writeValueAsString(c.explanation()),c.actorId(),
            stamp(c.occurredAt()),stamp(c.recordedAt()),c.note(),c.supersedesCalculationId(),c.correctionReason());
    }
    public List<ShippingMeasurementView> measurements(UUID assessment){
        return jdbc.query("select * from shipping_measurements where assessment_id=? order by recorded_at,id",
            (r,n)->new ShippingMeasurementView(r.getObject("id",UUID.class),assessment,json.readValue(r.getString("information"),MeasurementInput.class),
                r.getObject("actor_id",UUID.class),at(r,"recorded_at")),assessment);
    }
    public List<ShippingCalculationView> calculations(UUID assessment){
        return jdbc.query("select * from shipping_calculations where assessment_id=? order by revision",
            (r,n)->new ShippingCalculationView(r.getObject("id",UUID.class),assessment,r.getLong("revision"),r.getString("kind"),
                r.getObject("measurement_id",UUID.class),json.readValue(r.getString("explanation"),ShippingExplanation.class),
                r.getObject("actor_id",UUID.class),at(r,"occurred_at"),at(r,"recorded_at"),r.getString("note"),
                r.getObject("supersedes_calculation_id",UUID.class),r.getString("correction_reason")),assessment);
    }
    private static Timestamp stamp(Instant at){return at==null?null:Timestamp.from(at);}
    private static Instant at(ResultSet r,String column)throws SQLException{var t=r.getTimestamp(column);return t==null?null:t.toInstant();}
}
