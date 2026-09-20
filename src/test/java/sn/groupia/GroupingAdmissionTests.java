package sn.groupia;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sn.groupia.grouping.domain.model.GroupingCampaign;
import sn.groupia.shipping.api.*;
import sn.groupia.shipping.api.dto.ShippingTerms;
import static org.assertj.core.api.Assertions.assertThat;

class GroupingAdmissionTests {
    private static final Instant DEADLINE = Instant.parse("2027-01-01T12:00:00Z");
    private static final ShippingTerms TERMS = new ShippingTerms(null, TransportMode.ROAD,
            ShippingPricingMethod.FIXED, ShippingCollectionMode.AFTER_FINAL_ASSESSMENT,
            BigDecimal.ZERO, "XOF", "HALF_UP");

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    void deadlineIsExclusive(long secondsFromDeadline) {
        var campaign = GroupingCampaign.create(UUID.randomUUID(), "Campagne", null, DEADLINE, TERMS, DEADLINE.minusSeconds(10));
        campaign.open(1, DEADLINE.minusSeconds(5));
        assertThat(campaign.isOrderableAt(DEADLINE.plusSeconds(secondsFromDeadline), true, true))
                .isEqualTo(secondsFromDeadline < 0);
    }

    @Test
    void openWithoutDeadlineStillRequiresOrganizationAndUsableOffers() {
        var campaign = GroupingCampaign.create(UUID.randomUUID(), "Campagne", null, null, TERMS, DEADLINE);
        assertThat(campaign.isOrderableAt(DEADLINE, true, true)).isFalse();
        campaign.open(1, DEADLINE);
        assertThat(campaign.isOrderableAt(DEADLINE.plusSeconds(86400), true, true)).isTrue();
        assertThat(campaign.isOrderableAt(DEADLINE, false, true)).isFalse();
        assertThat(campaign.isOrderableAt(DEADLINE, true, false)).isFalse();
        campaign.close(DEADLINE);
        assertThat(campaign.isOrderableAt(DEADLINE, true, true)).isFalse();
        campaign.cancel("Annulation", DEADLINE);
        assertThat(campaign.isOrderableAt(DEADLINE, true, true)).isFalse();
    }
}
