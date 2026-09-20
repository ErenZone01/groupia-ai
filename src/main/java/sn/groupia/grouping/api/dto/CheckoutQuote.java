package sn.groupia.grouping.api.dto;
import java.util.*;
import sn.groupia.shipping.api.dto.ShippingTerms;
public record CheckoutQuote(UUID organizationId, UUID campaignId, String currency, String status,
        boolean orderable, ShippingTerms shippingTerms, List<CheckoutOffer> offers) {
    public CheckoutQuote { offers = List.copyOf(offers); }
}
