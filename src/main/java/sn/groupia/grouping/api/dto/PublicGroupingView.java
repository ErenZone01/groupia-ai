package sn.groupia.grouping.api.dto;
import java.time.Instant;
import java.util.*;
import sn.groupia.shipping.api.dto.ShippingTerms;
/** Deliberately excludes campaign/organization internal ids, actor ids, audit and cancellation reason. */
public record PublicGroupingView(UUID publicIdentifier, String slug, String title, String description,
        String status, String currency, boolean orderable, Instant closesAt, long version,
        ShippingTerms shippingTerms, List<PublicOfferView> offers) {}
