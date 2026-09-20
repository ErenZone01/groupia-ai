package sn.groupia.grouping.api.dto;
import java.math.BigDecimal;
import java.util.UUID;
public record CheckoutSelection(UUID offerId, int quantity, BigDecimal expectedUnitPrice, long expectedPriceRevision) {}
