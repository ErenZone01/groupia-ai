package sn.groupia.organization.infrastructure.web;

import java.net.URI;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import sn.groupia.organization.api.dto.OrganizationView;
import sn.groupia.organization.application.service.OrganizationService;

@RestController
@RequestMapping("/api/organizations")
class OrganizationController {
    private final OrganizationService organizations;
    OrganizationController(OrganizationService organizations) { this.organizations = organizations; }

    @PostMapping
    ResponseEntity<OrganizationView> create(@Valid @RequestBody CreateOrganizationRequest request) {
        var organization = organizations.create(request.name());
        return ResponseEntity.created(URI.create("/api/organizations/" + organization.id())).body(organization);
    }

    @GetMapping
    List<OrganizationView> list(@RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return organizations.list(page, size);
    }

    @GetMapping("/{organizationId}")
    OrganizationView get(@PathVariable UUID organizationId) { return organizations.get(organizationId); }

    record CreateOrganizationRequest(@NotBlank @Size(max = 160) String name) {}
}
