package sn.groupia.ordering.api.dto;
import java.util.UUID;
public record OrganizerOrderView(UUID id, UUID organizationId, UUID campaignId, UUID customerId,
        CustomerContact customerContactSnapshot, OrderReceipt order, long version) {}
