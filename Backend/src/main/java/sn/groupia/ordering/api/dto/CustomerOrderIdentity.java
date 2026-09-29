package sn.groupia.ordering.api.dto;
import java.time.Instant;
import java.util.UUID;
/** Internal authorization material. Never an HTTP response. */
public record CustomerOrderIdentity(UUID id,UUID organizationId,String orderNumber,String phoneNormalized,String status,Instant terminalAt) {
    @Override public String toString(){return "CustomerOrderIdentity[redacted]";}
}
