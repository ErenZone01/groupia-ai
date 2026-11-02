package sn.groupia.directsales.infrastructure;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.directsales.application.DirectDeliveryCoverageService;
import sn.groupia.shared.api.ApiException;

@RestController
class DirectDeliveryCoverageController {
    record PointRequest(Double latitude, Double longitude) {}

    private final DirectDeliveryCoverageService coverage;

    DirectDeliveryCoverageController(DirectDeliveryCoverageService coverage) {
        this.coverage = coverage;
    }

    @PostMapping("/api/public/direct-stores/{publicIdentifier}/delivery-coverage")
    @io.swagger.v3.oas.annotations.Operation(operationId = "previewDirectDeliveryCoverage")
    ResponseEntity<DirectDeliveryCoverageService.Coverage> preview(
            @PathVariable UUID publicIdentifier, @RequestBody PointRequest request) {
        if (request == null) {
            throw new ApiException(400, "INVALID_GEO_POINT", "Position géographique invalide.");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(coverage.evaluate(publicIdentifier, request.latitude(), request.longitude()));
    }
}
