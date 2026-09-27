package sn.groupia.grouping.api.dto;
import java.util.UUID;
public record CampaignLifecycleView(UUID id, UUID organizationId, String status, long version) {}
