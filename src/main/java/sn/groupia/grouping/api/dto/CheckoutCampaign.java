package sn.groupia.grouping.api.dto;
import java.util.UUID;
public record CheckoutCampaign(UUID organizationId, UUID campaignId, String countryCode) {}
