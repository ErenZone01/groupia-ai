package sn.groupia.shipping.domain.repository;
import java.util.*;
import sn.groupia.shipping.domain.model.ShippingAssessment;
import sn.groupia.shipping.api.dto.*;
public interface AssessmentRepository {
    Optional<ShippingAssessment> find(UUID org,UUID order);
    List<ShippingAssessment> findAll(UUID org,UUID campaign);
    void insert(ShippingAssessment assessment);
    void update(ShippingAssessment assessment);
    void append(ShippingMeasurementView measurement);
    void append(ShippingCalculationView calculation);
    List<ShippingMeasurementView> measurements(UUID assessment);
    List<ShippingCalculationView> calculations(UUID assessment);
}
