package sn.groupia.shipping.api;
import java.util.*;
import sn.groupia.shipping.api.dto.*;
/** Caller authenticates, scopes and locks the order/campaign; shipping only receives trusted snapshots. */
public interface ShippingAssessments {
    Map<UUID,ShippingAssessmentView> readAll(UUID org,UUID campaign);
    ShippingAssessmentView read(AssessmentSeed seed);
    ShippingAssessmentView measure(AssessmentSeed seed,long version,MeasurementInput input,UUID actor);
    ShippingExplanation preview(AssessmentSeed seed,FinalShippingInput input);
    ShippingAssessmentView finalizeShipping(AssessmentSeed seed,FinalShippingInput input,UUID actor);
    ShippingExplanation currentFinal(UUID org,UUID order);
}
