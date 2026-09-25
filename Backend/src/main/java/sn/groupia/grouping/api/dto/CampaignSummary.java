package sn.groupia.grouping.api.dto;
import java.time.Instant;
import java.util.UUID;
public record CampaignSummary(UUID id, UUID publicIdentifier, String slug, String title, String status,
        Instant closesAt, Instant createdAt, long version) {}
