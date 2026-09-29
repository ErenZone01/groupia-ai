package sn.groupia.customeraccess.domain.model;
import java.time.Instant;
import java.util.UUID;
public record OtpChallenge(UUID id,String phoneNormalized,String codeHash,Instant createdAt,Instant expiresAt,
    int attemptCount,Instant consumedAt,Instant revokedAt,Instant resendAfter) {
    public boolean usable(Instant now,int max){return consumedAt==null && revokedAt==null && now.isBefore(expiresAt) && attemptCount<max;}
    @Override public String toString(){return "OtpChallenge[redacted]";}
}
