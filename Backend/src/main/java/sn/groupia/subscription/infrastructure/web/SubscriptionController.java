package sn.groupia.subscription.infrastructure.web;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.organization.api.OrganizationPermission;
import sn.groupia.subscription.api.dto.SubscriptionBillingContactView;
import sn.groupia.subscription.api.dto.SubscriptionDetails;
import sn.groupia.subscription.api.dto.SubscriptionPeriodView;
import sn.groupia.subscription.application.service.SubscriptionManagementService;

@RestController
@RequestMapping("/api/organizations/{organizationId}/subscription")
class SubscriptionController {
    private final OrganizationAccess access;
    private final SubscriptionManagementService subscriptions;

    SubscriptionController(OrganizationAccess access, SubscriptionManagementService subscriptions) {
        this.access = access;
        this.subscriptions = subscriptions;
    }

    @GetMapping
    ResponseEntity<SubscriptionDetails> details(@PathVariable UUID organizationId) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_SUBSCRIPTION);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(subscriptions.details(organizationId));
    }

    @GetMapping("/billing-contact")
    ResponseEntity<SubscriptionBillingContactView> billingContact(@PathVariable UUID organizationId) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_SUBSCRIPTION);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(subscriptions.details(organizationId).billingContact());
    }

    @PutMapping("/billing-contact")
    SubscriptionBillingContactView updateBillingContact(@PathVariable UUID organizationId,
            @Valid @RequestBody BillingContactInput input) {
        return subscriptions.updateBillingContact(organizationId, input.name(), input.phoneE164(), input.email());
    }

    @PostMapping("/start")
    ResponseEntity<SubscriptionDetails> start(@PathVariable UUID organizationId) {
        access.requirePermission(organizationId, OrganizationPermission.MANAGE_SUBSCRIPTION);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(subscriptions.start(organizationId));
    }

    @PostMapping("/cancel")
    ResponseEntity<SubscriptionDetails> cancel(@PathVariable UUID organizationId) {
        access.requirePermission(organizationId, OrganizationPermission.MANAGE_SUBSCRIPTION);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(subscriptions.cancel(organizationId));
    }

    @GetMapping("/history")
    ResponseEntity<List<SubscriptionPeriodView>> history(@PathVariable UUID organizationId) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_SUBSCRIPTION);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(subscriptions.history(organizationId));
    }

    record BillingContactInput(@NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "^\\+221[0-9]{9}$") String phoneE164,
            @Email @Size(max = 254) String email) {}
}
