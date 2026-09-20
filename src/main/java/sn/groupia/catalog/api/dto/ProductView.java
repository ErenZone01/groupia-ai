package sn.groupia.catalog.api.dto;
import java.time.Instant;
import java.util.*;
public record ProductView(UUID id, UUID organizationId, String name, String description, String status,
        Instant createdAt, Instant updatedAt, long version, List<ProductVariantView> variants) {}
