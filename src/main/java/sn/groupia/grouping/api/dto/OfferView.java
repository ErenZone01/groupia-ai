package sn.groupia.grouping.api.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record OfferView(UUID id, UUID productVariantId, String commercialLabel, BigDecimal unitPrice,
        String currency, boolean active, long version, long priceRevision, Instant createdAt, Instant updatedAt) {}
