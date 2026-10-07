package sn.groupia.settlement.api.dto;
import java.time.Instant;
import java.util.UUID;
public record PayoutDestinationView(UUID id,String method,String holderName,String maskedPhone,String status,
    long version,Instant createdAt) {}
