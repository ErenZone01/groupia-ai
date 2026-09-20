package sn.groupia.catalog.api.dto;
import java.time.Instant;
import java.util.UUID;
public record ProductSummary(UUID id, UUID organizationId, String name, String status,
        Instant createdAt, Instant updatedAt, long version) {}
