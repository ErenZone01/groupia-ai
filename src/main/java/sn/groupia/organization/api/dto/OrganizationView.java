package sn.groupia.organization.api.dto;
import java.time.Instant;
import java.util.UUID;
public record OrganizationView(UUID id, String name, String status, String defaultCurrency,
        String countryCode, String timezone, Instant createdAt, Instant updatedAt) {}
