package sn.groupia.ordering.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.ordering.api.dto.GroupingCompletionView;
import sn.groupia.ordering.application.service.GroupingCompletion;

@RestController
@RequestMapping("/api/organizations/{organizationId}/grouping-campaigns/{campaignId}")
class GroupingCompletionController {
    private final GroupingCompletion service;

    GroupingCompletionController(GroupingCompletion service) {
        this.service = service;
    }

    @GetMapping("/completion")
    @Operation(operationId = "groupingCompletion", summary = "Diagnostic de fin du groupage")
    ResponseEntity<GroupingCompletionView> read(
            @PathVariable UUID organizationId, @PathVariable UUID campaignId) {
        return response(service.read(organizationId, campaignId));
    }

    @PostMapping("/complete")
    @Operation(operationId = "completeGrouping", summary = "Terminer définitivement le groupage")
    ResponseEntity<GroupingCompletionView> complete(
            @PathVariable UUID organizationId,
            @PathVariable UUID campaignId,
            @Valid @RequestBody ConfirmCompletion body) {
        return response(
                service.complete(
                        organizationId,
                        campaignId,
                        body.version(),
                        Boolean.TRUE.equals(body.confirmed())));
    }

    private ResponseEntity<GroupingCompletionView> response(GroupingCompletionView body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }

    record ConfirmCompletion(@NotNull @PositiveOrZero Long version, Boolean confirmed) {}
}
