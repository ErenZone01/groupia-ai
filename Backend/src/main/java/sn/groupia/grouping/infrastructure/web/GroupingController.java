package sn.groupia.grouping.infrastructure.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.grouping.api.dto.*;
import sn.groupia.grouping.application.service.GroupingService;
import sn.groupia.shared.api.ApiException;
import sn.groupia.shipping.api.*;
import sn.groupia.shipping.api.dto.ShippingTerms;

@RestController
@RequestMapping("/api/organizations/{organizationId}/grouping-campaigns")
class GroupingController {
    private final GroupingService grouping;
    GroupingController(GroupingService grouping) { this.grouping = grouping; }

    @PostMapping
    ResponseEntity<CampaignView> create(@PathVariable UUID organizationId, @Valid @RequestBody CreateCampaign input) {
        var campaign = grouping.create(organizationId, input.title(), input.description(), input.closesAt(), terms(input.shippingTerms()));
        return ResponseEntity.created(URI.create("/api/organizations/" + organizationId + "/grouping-campaigns/" + campaign.id())).body(campaign);
    }
    @GetMapping
    CampaignPage list(@PathVariable UUID organizationId, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return grouping.list(organizationId, status, page, size);
    }
    @GetMapping("/{campaignId}")
    CampaignView get(@PathVariable UUID organizationId, @PathVariable UUID campaignId) {
        return grouping.get(organizationId, campaignId);
    }
    @PutMapping("/{campaignId}")
    CampaignView update(@PathVariable UUID organizationId, @PathVariable UUID campaignId, @Valid @RequestBody UpdateCampaign input) {
        return grouping.update(organizationId, campaignId, input.version(), input.title(), input.description(), input.closesAt(), terms(input.shippingTerms()));
    }
    @PostMapping("/{campaignId}/open")
    CampaignView open(@PathVariable UUID organizationId, @PathVariable UUID campaignId, @Valid @RequestBody CampaignVersion input) {
        return grouping.open(organizationId, campaignId, input.version());
    }
    @PostMapping("/{campaignId}/close")
    CampaignView close(@PathVariable UUID organizationId, @PathVariable UUID campaignId, @Valid @RequestBody CampaignVersion input) {
        return grouping.close(organizationId, campaignId, input.version());
    }
    @PostMapping("/{campaignId}/cancel")
    CampaignView cancel(@PathVariable UUID organizationId, @PathVariable UUID campaignId, @Valid @RequestBody CancelCampaign input) {
        return grouping.cancel(organizationId, campaignId, input.version(), input.reason());
    }
    @PostMapping("/{campaignId}/offers")
    CampaignView addOffer(@PathVariable UUID organizationId, @PathVariable UUID campaignId, @Valid @RequestBody AddOffer input) {
        return grouping.addOffer(organizationId, campaignId, input.campaignVersion(), input.productVariantId(), input.commercialLabel(), input.unitPrice());
    }
    @PutMapping("/{campaignId}/offers/{offerId}")
    CampaignView updateOffer(@PathVariable UUID organizationId, @PathVariable UUID campaignId, @PathVariable UUID offerId,
            @Valid @RequestBody UpdateOffer input) {
        return grouping.updateOffer(organizationId, campaignId, offerId, input.campaignVersion(), input.version(), input.commercialLabel(), input.unitPrice());
    }
    @PostMapping("/{campaignId}/offers/{offerId}/deactivate")
    CampaignView deactivate(@PathVariable UUID organizationId, @PathVariable UUID campaignId, @PathVariable UUID offerId,
            @Valid @RequestBody OfferVersions input) {
        return grouping.deactivateOffer(organizationId, campaignId, offerId, input.campaignVersion(), input.version());
    }
    @PostMapping("/{campaignId}/offers/{offerId}/activate")
    CampaignView activate(@PathVariable UUID organizationId, @PathVariable UUID campaignId, @PathVariable UUID offerId,
            @Valid @RequestBody OfferVersions input) {
        return grouping.activateOffer(organizationId, campaignId, offerId, input.campaignVersion(), input.version());
    }

    private static ShippingTerms terms(ShippingInput input) {
        if (input == null) { return null; }
        try {
            return new ShippingTerms(input.originCountry(), TransportMode.valueOf(input.transportMode()),
                    ShippingPricingMethod.valueOf(input.shippingPricingMethod()),
                    ShippingCollectionMode.valueOf(input.shippingCollectionMode()), input.shippingRate(),
                    input.shippingCurrency() == null ? "XOF" : input.shippingCurrency(),
                    input.roundingRule() == null ? "HALF_UP" : input.roundingRule(), input.volumetricDivisor());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ApiException(400, "INVALID_SHIPPING_TERMS", "Conditions de transport invalides.");
        }
    }
    record ShippingInput(String originCountry, String transportMode, String shippingPricingMethod,
            String shippingCollectionMode, BigDecimal shippingRate, String shippingCurrency, String roundingRule, BigDecimal volumetricDivisor) {}
    record CreateCampaign(@NotBlank @Size(max = 160) String title, @Size(max = 5000) String description,
            Instant closesAt, @Valid ShippingInput shippingTerms) {}
    record UpdateCampaign(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 160) String title,
            @Size(max = 5000) String description, Instant closesAt, @Valid ShippingInput shippingTerms) {}
    record CampaignVersion(@NotNull @PositiveOrZero Long version) {}
    record CancelCampaign(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 500) String reason) {}
    record AddOffer(@NotNull @PositiveOrZero Long campaignVersion, @NotNull UUID productVariantId,
            @Size(max = 160) String commercialLabel, @NotNull BigDecimal unitPrice) {}
    record UpdateOffer(@NotNull @PositiveOrZero Long campaignVersion, @NotNull @PositiveOrZero Long version,
            @Size(max = 160) String commercialLabel, @NotNull BigDecimal unitPrice) {}
    record OfferVersions(@NotNull @PositiveOrZero Long campaignVersion, @NotNull @PositiveOrZero Long version) {}
}
