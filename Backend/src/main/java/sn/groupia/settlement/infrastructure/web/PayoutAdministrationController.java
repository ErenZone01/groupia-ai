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
     * Simplified, audited review of a previously recorded SasPay HTTP 403 authorization rejection.
     * Available only to the configured administrative operator.
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

    /**
     * Narrow exception for a single configured historical unresolved payout. All external
     * evidence is supplied by the operator; this API never calls the provider.
     */
    record HistoricalUnlinked(
            @PositiveOrZero long expectedVersion,
            @NotBlank @Size(max = 120) String evidenceReference,
            @NotBlank @Size(min = 20, max = 500) String reason,
            boolean ownerConfirmsNeverExecuted,
            boolean acceptsDuplicatePayoutRisk,
            boolean confirmed) {
        @JsonAnySetter
        public void unknown(String key, Object value) {
            throw new IllegalArgumentException("Unsupported historical review field");
        }
    }

    @GetMapping("/historical-unlinked")
    ResponseEntity<PayoutAdministrationService.HistoricalUnlinkedStatus> historicalUnlinkedStatus(
            @PathVariable UUID organizationId, @PathVariable UUID payoutId) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(service.historicalUnlinkedStatus(organizationId, payoutId));
    }

    @PostMapping("/historical-unlinked")
    ResponseEntity<PayoutAdministrationService.Result> closeHistoricalUnlinked(
            @PathVariable UUID organizationId,
            @PathVariable UUID payoutId,
            @Valid @RequestBody HistoricalUnlinked request) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        service.closeHistoricalUnlinked(
                                organizationId,
                                payoutId,
                                request.expectedVersion(),
                                request.evidenceReference(),
                                request.reason(),
                                request.ownerConfirmsNeverExecuted(),
                                request.acceptsDuplicatePayoutRisk(),
                                request.confirmed()));
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
