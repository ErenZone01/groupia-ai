package sn.groupia.settlement.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.settlement.application.service.PayoutAdministrationService;

@RestController
@RequestMapping("/api/organizations/{organizationId}/merchant-payouts/{payoutId}/administration")
class PayoutAdministrationController {
    private final PayoutAdministrationService service;

    PayoutAdministrationController(PayoutAdministrationService service) {
        this.service = service;
    }

    record Decision(
            @NotBlank String action,
            @NotBlank @Size(max = 120) String approvalReference,
            @PositiveOrZero long expectedVersion,
            @NotBlank @Size(min = 10, max = 500) String reason,
            boolean confirmed) {
        @JsonAnySetter
        public void unknown(String key, Object value) {
            throw new IllegalArgumentException("Unsupported administrative field");
        }
    }

    @PostMapping
    ResponseEntity<PayoutAdministrationService.Result> decide(
            @PathVariable UUID organizationId,
            @PathVariable UUID payoutId,
            @Valid @RequestBody Decision request) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        service.decide(
                                organizationId,
                                payoutId,
                                request.action(),
                                request.approvalReference(),
                                request.expectedVersion(),
                                request.reason(),
                                request.confirmed()));
    }
}
