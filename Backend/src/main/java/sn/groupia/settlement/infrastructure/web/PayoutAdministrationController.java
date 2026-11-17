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

    /**
     * Simplified, audited review of a previously recorded SasPay HTTP 403 authorization
     * rejection. Available only to the configured administrative operator.
     */
    record RecordedRejection(
            @PositiveOrZero long expectedVersion,
            @NotBlank String providerCode,
            @NotBlank @Size(max = 120) String evidenceReference,
            @NotBlank @Size(min = 20, max = 500) String reason,
            boolean allAttemptsReviewed,
            boolean definitiveNonExecution,
            boolean futureExecutionImpossible,
            boolean confirmed) {
        @JsonAnySetter
        public void unknown(String key, Object value) {
            throw new IllegalArgumentException("Unsupported administrative field");
        }
    }

    @PostMapping("/recorded-rejection")
    ResponseEntity<PayoutAdministrationService.Result> closeRecordedRejection(
            @PathVariable UUID organizationId,
            @PathVariable UUID payoutId,
            @Valid @RequestBody RecordedRejection request) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        service.closeRecordedRejection(
                                organizationId,
                                payoutId,
                                request.expectedVersion(),
                                request.providerCode(),
                                request.evidenceReference(),
                                request.reason(),
                                request.allAttemptsReviewed(),
                                request.definitiveNonExecution(),
                                request.futureExecutionImpossible(),
                                request.confirmed()));
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
