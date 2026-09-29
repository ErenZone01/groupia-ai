package sn.groupia.customeraccess.domain.model;
import java.time.Instant;
import java.util.UUID;
public record OrderAccessLink(UUID id,UUID organizationId,UUID orderId,long generation,Instant createdAt,Instant revokedAt,Instant expiresAt,Instant lastUsedAt) {
    public boolean activeAt(Instant now){return revokedAt==null && (expiresAt==null || now.isBefore(expiresAt));}
}
