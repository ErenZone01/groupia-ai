package sn.groupia.customeraccess.domain.model;
import java.time.Instant;
import java.util.UUID;
public record CustomerSession(UUID id,AccessScope scope,UUID linkId,String phoneNormalized,Instant expiresAt,Instant revokedAt) {
    public boolean activeAt(Instant now){return revokedAt==null && now.isBefore(expiresAt);}
    @Override public String toString(){return "CustomerSession[redacted]";}
}
