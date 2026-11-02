package sn.groupia.directsales.infrastructure;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.directsales.application.DirectReverseGeocodeService;

/** Address suggestion only, not a checkout, location proof, or delivery eligibility check. */
@RestController
class DirectReverseGeocodeController {
    private final DirectReverseGeocodeService lookup;

    DirectReverseGeocodeController(DirectReverseGeocodeService lookup) {
        this.lookup = lookup;
    }

    record PointRequest(Double latitude, Double longitude) {}

    @PostMapping("/api/public/direct-stores/{publicIdentifier}/reverse-geocode")
    @io.swagger.v3.oas.annotations.Operation(operationId = "suggestDirectDeliveryAddress")
    ResponseEntity<DirectReverseGeocodeService.Suggestion> suggest(
            @PathVariable UUID publicIdentifier, @RequestBody PointRequest request) {
        if (request == null || request.latitude() == null || request.longitude() == null) {
            throw new sn.groupia.shared.api.ApiException(400, "INVALID_GEO_POINT", "Position invalide.");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(lookup.suggest(publicIdentifier, request.latitude(), request.longitude()));
    }
}
