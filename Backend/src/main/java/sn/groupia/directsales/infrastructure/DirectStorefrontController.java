package sn.groupia.directsales.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.directsales.application.DirectStorefrontService;

@RestController
class DirectStorefrontController {
    private final DirectStorefrontService direct;

    DirectStorefrontController(DirectStorefrontService direct) {
        this.direct = direct;
    }

    record NameInput(@NotBlank @Size(max = 160) String displayName) {}

    record OfferInput(
            @NotNull UUID variantId, @NotNull @Min(1) @Max(999999999999L) Long priceXof) {}

    record UpdateInput(
            @NotNull @Min(0) Long version,
            @NotNull @Min(1) @Max(999999999999L) Long priceXof,
            @NotNull Boolean published) {}

    @GetMapping("/api/organizations/{organizationId}/direct-store")
    DirectStorefrontService.Storefront manager(@PathVariable UUID organizationId) {
        return direct.manager(organizationId);
    }

    @PutMapping("/api/organizations/{organizationId}/direct-store")
    DirectStorefrontService.Storefront name(
            @PathVariable UUID organizationId, @Valid @RequestBody NameInput request) {
        return direct.setName(organizationId, request.displayName());
    }

    @PostMapping("/api/organizations/{organizationId}/direct-store/offers")
    DirectStorefrontService.Storefront add(
            @PathVariable UUID organizationId, @Valid @RequestBody OfferInput request) {
        return direct.add(organizationId, request.variantId(), request.priceXof());
    }

    @PutMapping("/api/organizations/{organizationId}/direct-store/offers/{offerId}")
    DirectStorefrontService.Storefront update(
            @PathVariable UUID organizationId,
            @PathVariable UUID offerId,
            @Valid @RequestBody UpdateInput request) {
        return direct.update(
                organizationId,
                offerId,
                request.version(),
                request.priceXof(),
                request.published());
    }

    @GetMapping("/api/public/direct-stores/{publicIdentifier}")
    ResponseEntity<DirectStorefrontService.Storefront> publicStore(
            @PathVariable UUID publicIdentifier) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(direct.publicStore(publicIdentifier));
    }
}
