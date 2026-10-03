package sn.groupia.shipping.application.service;
import java.time.*;import java.time.temporal.ChronoUnit;import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.shipping.api.*;
import sn.groupia.shipping.api.dto.*;
import sn.groupia.shipping.domain.model.*;
import sn.groupia.shipping.domain.service.FinalShippingCalculator;
import sn.groupia.shipping.domain.repository.AssessmentRepository;
import sn.groupia.shared.api.ApiException;
@Service
public class AssessmentService implements ShippingAssessments {
    private final AssessmentRepository repository;private final Clock clock;
    AssessmentService(AssessmentRepository repository,Clock clock){this.repository=repository;this.clock=clock;}
    @Transactional(readOnly=true)
    public Map<UUID,ShippingAssessmentView> readAll(UUID org,UUID campaign){
        var result=new HashMap<UUID,ShippingAssessmentView>();
        for(var a:repository.findAll(org,campaign))result.put(a.snapshot().orderId(),view(a));
        return Map.copyOf(result);
    }
    @Transactional(readOnly=true)
    public ShippingAssessmentView read(AssessmentSeed seed){
        return repository.find(seed.organizationId(),seed.orderId()).map(this::view).orElseGet(()->pending(seed));
    }
    public static ShippingAssessmentView pending(AssessmentSeed seed){
        return new ShippingAssessmentView(null,"PENDING",seed,0,null,null,null,null,List.of(),List.of());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public ShippingAssessmentView measure(AssessmentSeed seed,long version,MeasurementInput input,UUID actor){
        var a=open(seed);a.requireVersion(version);
        var m=ShippingMeasurement.receive(a.id(),a.currentMeasurementId(),input,actor,now());
        repository.append(m);a=a.measured(m.id(),m.recordedAt());repository.update(a);return view(a);
    }
    @Transactional(readOnly=true)
    public ShippingExplanation preview(AssessmentSeed seed,FinalShippingInput input){
        var current=repository.find(seed.organizationId(),seed.orderId());
        var a=current.orElseGet(()->ShippingAssessment.open(seed,now()));
        a.requireVersion(input.version());validateFinal(a,input);
        return FinalShippingCalculator.calculate(a.snapshot(),input,measurement(a,input.measurementId()));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public ShippingAssessmentView finalizeShipping(AssessmentSeed seed,FinalShippingInput input,UUID actor){
        var a=open(seed);a.requireVersion(input.version());validateFinal(a,input);
        var m=measurement(a,input.measurementId());
        var explanation=FinalShippingCalculator.calculate(a.snapshot(),input,m);
        var old=repository.calculations(a.id());
        var calculation=new ShippingCalculationView(UUID.randomUUID(),a.id(),old.size()+1,"FINAL",
            m==null?null:m.id(),explanation,Objects.requireNonNull(actor),input.occurredAt(),now(),
            Shipment.clean(input.note(),2000),a.currentFinalCalculationId(),Shipment.clean(input.correctionReason(),1000));
        repository.append(calculation);a=a.finalized(calculation.id(),calculation.recordedAt());repository.update(a);return view(a);
    }
    @Transactional(readOnly=true)
    public ShippingExplanation currentFinal(UUID org,UUID order){
        return repository.find(org,order).map(this::view).map(ShippingAssessmentView::currentFinal)
            .map(ShippingCalculationView::explanation).orElse(null);
    }
    private void validateFinal(ShippingAssessment a,FinalShippingInput i){
        Shipment.validateActual(i.occurredAt(),now());
        Shipment.clean(i.note(),2000);
        if(a.currentFinalCalculationId()!=null && Shipment.clean(i.correctionReason(),1000)==null)
            throw new ApiException(400,"SHIPPING_CORRECTION_REASON_REQUIRED","Indiquez pourquoi le transport final est corrigé.");
    }
    private ShippingMeasurementView measurement(ShippingAssessment a,UUID id){
        if(id==null)return null;
        if(!id.equals(a.currentMeasurementId()))throw new ApiException(409,"SHIPPING_MEASUREMENT_STALE","Choisissez la dernière mesure de cette commande.");
        return repository.measurements(a.id()).stream().filter(m->m.id().equals(id)).findFirst()
            .orElseThrow(()->new ApiException(404,"SHIPPING_MEASUREMENT_NOT_FOUND","Mesure introuvable."));
    }
    private ShippingAssessment open(AssessmentSeed seed){
        return repository.find(seed.organizationId(),seed.orderId()).orElseGet(()->{
            var a=ShippingAssessment.open(seed,now());repository.insert(a);return a;
        });
    }
    private ShippingAssessmentView view(ShippingAssessment a){return new ShippingAssessmentView(a.id(),a.status(),a.snapshot(),a.version(),
        a.currentMeasurementId(),a.currentFinalCalculationId(),a.createdAt(),a.updatedAt(),repository.measurements(a.id()),repository.calculations(a.id()));}
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
}
