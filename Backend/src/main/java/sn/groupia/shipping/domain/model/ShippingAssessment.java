package sn.groupia.shipping.domain.model;
import java.time.Instant; import java.util.UUID;
import sn.groupia.shipping.api.dto.AssessmentSeed;
import sn.groupia.shared.api.ApiException;
public record ShippingAssessment(UUID id,AssessmentSeed snapshot,String status,long version,
    UUID currentMeasurementId,UUID currentFinalCalculationId,Instant createdAt,Instant updatedAt) {
    public static ShippingAssessment open(AssessmentSeed seed,Instant now){return new ShippingAssessment(UUID.randomUUID(),seed,"PENDING",0,null,null,now,now);}
    public void requireVersion(long expected){if(expected!=version)throw new ApiException(409,"SHIPPING_VERSION_CONFLICT","Le transport a changé. Actualisez la commande.");}
    public ShippingAssessment measured(UUID id,Instant now){return new ShippingAssessment(this.id,snapshot,status,version+1,id,currentFinalCalculationId,createdAt,now);}
    public ShippingAssessment finalized(UUID id,Instant now){return new ShippingAssessment(this.id,snapshot,"FINALIZED",version+1,currentMeasurementId,id,createdAt,now);}
}
