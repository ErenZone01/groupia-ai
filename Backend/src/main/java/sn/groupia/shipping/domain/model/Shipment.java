package sn.groupia.shipping.domain.model;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import sn.groupia.shipping.api.*;
import sn.groupia.shipping.api.dto.*;
import sn.groupia.shared.api.ApiException;

/** Current private dossier; historical information is preserved separately in immutable facts. */
public record Shipment(UUID id, UUID organizationId, UUID campaignId, TransportMode transportMode,
        Instant departedAt, Instant expectedArrivalAt, Instant arrivedAt, String trackingReference, String note,
        Instant createdAt, Instant updatedAt, String providerName, String providerContact,
        Instant departureConfirmedAt, Instant arrivalConfirmedAt, long version) {
    public static Shipment open(UUID org, UUID campaign, TransportMode mode, Instant now) {
        return new Shipment(UUID.randomUUID(),org,campaign,Objects.requireNonNull(mode),null,null,null,null,null,
            micros(now),micros(now),null,null,null,null,0);
    }
    public Shipment depart(Instant departure, Instant expected, String reference, String text, Instant now) {
        if(departureConfirmedAt!=null)throw new ApiException(409,"SHIPMENT_ALREADY_DEPARTED","Le départ est déjà confirmé.");
        validateActual(departure,now);
        Instant eta=expected==null?expectedArrivalAt:expected;
        validateEta(departure,eta);
        return revise(departure,eta,arrivedAt,reference==null?trackingReference:clean(reference,160),
            text==null?note:clean(text,2000),providerName,providerContact,now,arrivalConfirmedAt,now);
    }
    public Shipment arrive(Instant arrival, Instant now) {
        if(departureConfirmedAt==null)throw new ApiException(409,"SHIPMENT_NOT_DEPARTED","Le départ n’a pas été confirmé.");
        if(arrivalConfirmedAt!=null)throw new ApiException(409,"SHIPMENT_ALREADY_ARRIVED","L’arrivée est déjà confirmée.");
        validateActual(arrival,now);
        if(arrival!=null && departedAt!=null && arrival.isBefore(departedAt))throw invalidDates();
        return revise(departedAt,expectedArrivalAt,arrival,trackingReference,note,providerName,providerContact,departureConfirmedAt,now,now);
    }
    /** Full replacement of optional dossier text; blank/null clears the current value, history remains. */
    public Shipment enrich(String name,String contact,String reference,String text,Instant now) {
        return revise(departedAt,expectedArrivalAt,arrivedAt,clean(reference,160),clean(text,2000),
            clean(name,160),clean(contact,300),departureConfirmedAt,arrivalConfirmedAt,now);
    }
    public Shipment estimate(Instant expected,Instant now) {
        validateEta(departedAt,expected);
        return revise(departedAt,expected,arrivedAt,trackingReference,note,providerName,providerContact,departureConfirmedAt,arrivalConfirmedAt,now);
    }
    public Shipment record(Instant now) {
        return revise(departedAt,expectedArrivalAt,arrivedAt,trackingReference,note,providerName,providerContact,departureConfirmedAt,arrivalConfirmedAt,now);
    }
    private Shipment revise(Instant departure,Instant eta,Instant arrival,String reference,String text,
            String name,String contact,Instant departureConfirmation,Instant arrivalConfirmation,Instant now) {
        return new Shipment(id,organizationId,campaignId,transportMode,micros(departure),micros(eta),micros(arrival),
            reference,text,createdAt,micros(now),name,contact,micros(departureConfirmation),micros(arrivalConfirmation),version+1);
    }
    public ShipmentView view(List<LogisticsFactView> timeline) {
        return new ShipmentView(id,organizationId,campaignId,transportMode,departedAt,expectedArrivalAt,arrivedAt,
            trackingReference,note,createdAt,updatedAt,providerName,providerContact,departureConfirmedAt,arrivalConfirmedAt,version,List.copyOf(timeline));
    }
    public static void validateActual(Instant at,Instant now){if(at!=null && at.isAfter(now))throw invalidDates();}
    private static void validateEta(Instant departure,Instant eta){if(departure!=null && eta!=null && eta.isBefore(departure))throw invalidDates();}
    private static Instant micros(Instant at){return at==null?null:at.truncatedTo(ChronoUnit.MICROS);}
    public static String clean(String value,int limit) {
        if(value==null || value.isBlank())return null;
        if(value.length()>limit || value.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\r'))
            throw new ApiException(400,"INVALID_SHIPMENT_INFORMATION","Information logistique invalide.");
        return value.strip();
    }
    private static ApiException invalidDates(){return new ApiException(400,"INVALID_SHIPMENT_DATES","Vérifiez les dates de départ et d’arrivée.");}
}
