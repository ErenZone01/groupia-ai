package sn.groupia.shipping.api.dto;
import java.time.Instant; import java.util.*;
public record ShippingAssessmentView(UUID id,String status,AssessmentSeed snapshot,long version,
    UUID currentMeasurementId,UUID currentFinalCalculationId,Instant createdAt,Instant updatedAt,
    List<ShippingMeasurementView> measurements,List<ShippingCalculationView> calculations) {
    public ShippingCalculationView currentFinal(){return calculations.stream().filter(c->c.id().equals(currentFinalCalculationId)).findFirst().orElse(null);}
}
