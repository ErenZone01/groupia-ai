package sn.groupia.payment.infrastructure.provider.saspay;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.shared.api.ApiException;

@RestController
@RequestMapping("/api/platform/saspay/incidents")
class SaspayIncidentConsoleController {
    private final SaspayIncidentConsoleService service;

    SaspayIncidentConsoleController(SaspayIncidentConsoleService service) {
        this.service = service;
    }

    record DecisionRequest(
            @NotBlank String action,
            @NotBlank @Size(min = 5, max = 120) String evidenceReference,
            @NotBlank @Size(min = 20, max = 500) String reason,
            @Positive long expectedOccurrenceCount,
            boolean confirmed) {
        @JsonAnySetter
        public void reject(String key, Object value) {
            throw new ApiException(
                    400, "SASPAY_INCIDENT_FIELD_UNKNOWN", "Champ administratif inconnu.");
        }
    }

    @GetMapping("/access")
    ResponseEntity<SaspayIncidentConsoleService.Access> access() {
        return response(service.access());
    }

    @GetMapping
    ResponseEntity<SaspayIncidentConsoleService.Page> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String code,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return response(service.list(status, code, page, size));
    }

    @GetMapping("/{incidentId}")
    ResponseEntity<SaspayIncidentConsoleService.IncidentDetail> get(@PathVariable UUID incidentId) {
        return response(service.get(incidentId));
    }

    @PostMapping("/{incidentId}/decisions")
    ResponseEntity<SaspayIncidentConsoleService.CommandResult> decide(
            @PathVariable UUID incidentId, @Valid @RequestBody DecisionRequest body) {
        return response(
                service.decide(
                        incidentId,
                        body.action(),
                        body.evidenceReference(),
                        body.reason(),
                        body.expectedOccurrenceCount(),
                        body.confirmed()));
    }

    private static <T> ResponseEntity<T> response(T value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(value);
    }
}
