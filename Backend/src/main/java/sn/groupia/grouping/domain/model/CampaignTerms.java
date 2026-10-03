package sn.groupia.grouping.domain.model;

import java.math.BigDecimal;
import jakarta.persistence.*;
import sn.groupia.shipping.api.*;
import sn.groupia.shipping.api.dto.ShippingTerms;

@Embeddable
public class CampaignTerms {
    @Column(length = 2) private String originCountry;
    @Enumerated(EnumType.STRING) @Column(length = 16) private TransportMode transportMode;
    @Enumerated(EnumType.STRING) @Column(length = 16) private ShippingPricingMethod shippingPricingMethod;
    @Enumerated(EnumType.STRING) @Column(length = 32) private ShippingCollectionMode shippingCollectionMode;
    @Column(columnDefinition = "numeric") private BigDecimal shippingRate;
    @Column(length = 3) private String shippingCurrency;
    @Column(length = 16) private String roundingRule;

    @Column(columnDefinition="numeric") private BigDecimal volumetricDivisor;

    protected CampaignTerms() {}
    static CampaignTerms from(ShippingTerms source) {
        if (source == null) { return null; }
        var terms = new CampaignTerms();
        terms.originCountry = source.originCountry();
        terms.transportMode = source.transportMode();
        terms.shippingPricingMethod = source.shippingPricingMethod();
        terms.shippingCollectionMode = source.shippingCollectionMode();
        terms.shippingRate = source.shippingRate();
        terms.shippingCurrency = source.shippingCurrency();
        terms.roundingRule = source.roundingRule();
        terms.volumetricDivisor=source.volumetricDivisor();
        return terms;
    }
    ShippingTerms view() {
        return new ShippingTerms(originCountry, transportMode, shippingPricingMethod,
                shippingCollectionMode, shippingRate, shippingCurrency, roundingRule, volumetricDivisor);
    }
}
