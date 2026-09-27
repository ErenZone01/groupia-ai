package sn.groupia.shipping.domain.model;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import sn.groupia.shipping.api.TransportMode;
import sn.groupia.shipping.api.dto.ShipmentView;
import sn.groupia.shared.api.ApiException;

/** One departure and one first arrival; no correction or split-shipment workflow in V1. */
public record Shipment(UUID id, UUID organizationId, UUID campaignId, TransportMode transportMode,
        Instant departedAt, Instant expectedArrivalAt, Instant arrivedAt, String trackingReference, String note,
        Instant createdAt, Instant updatedAt) {
    public static Shipment depart(UUID org, UUID campaign, TransportMode mode, Instant departure,
            Instant expected, String reference, String note, Instant now) {
        if (mode == null || departure == null || departure.isAfter(now)
                || (expected != null && expected.isBefore(departure))) throw invalidDates();
        return new Shipment(UUID.randomUUID(), org, campaign, mode, micros(departure), micros(expected), null,
                clean(reference,160), clean(note,2000), micros(now), micros(now));
    }
    public Shipment arrive(Instant arrival, Instant now) {
        if (arrivedAt != null) throw new ApiException(409,"SHIPMENT_ALREADY_ARRIVED","L’arrivée est déjà enregistrée.");
        Instant at = arrival == null ? now : arrival;
        if (at.isBefore(departedAt) || at.isAfter(now)) throw invalidDates();
        return new Shipment(id, organizationId, campaignId, transportMode, departedAt, expectedArrivalAt,
                micros(at), trackingReference, note, createdAt, micros(now));
    }
    public ShipmentView view() {
        return new ShipmentView(id,organizationId,campaignId,transportMode,departedAt,expectedArrivalAt,
                arrivedAt,trackingReference,note,createdAt,updatedAt);
    }
    private static Instant micros(Instant at){return at==null?null:at.truncatedTo(ChronoUnit.MICROS);}
    private static String clean(String value,int limit) {
        if(value==null || value.isBlank())return null;
        if(value.length()>limit || value.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\r'))
            throw new ApiException(400,"INVALID_SHIPMENT_INFORMATION","Référence ou note invalide.");
        return value.strip();
    }
    private static ApiException invalidDates(){return new ApiException(400,"INVALID_SHIPMENT_DATES","Vérifiez les dates de départ et d’arrivée.");}
}
