package sn.groupia.grouping.api.dto;
import java.time.Instant;
import java.util.*;
import sn.groupia.shipping.api.dto.ShippingTerms;
public record CampaignView(UUID id, UUID organizationId, UUID publicIdentifier, String slug, String title,
        String description, String status, String currency, Instant closesAt, ShippingTerms shippingTerms,
        Instant openedAt, Instant closedAt, Instant cancelledAt, String cancellationReason,
        Instant createdAt, Instant updatedAt, long version, List<OfferView> offers) {}
