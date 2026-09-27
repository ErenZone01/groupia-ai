package sn.groupia.ordering.api.dto;
import java.util.UUID;
/** Stable historical identity alongside the organizer's public snapshot. */
public record SupplierProjectionLine(UUID productId, UUID productVariantId, UUID groupingOfferId, SupplierSummaryLine snapshot) {}
