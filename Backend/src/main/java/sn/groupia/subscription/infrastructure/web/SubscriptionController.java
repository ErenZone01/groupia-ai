package sn.groupia.subscription.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
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
    private final sn.groupia.subscription.application.service.SubscriptionInvoiceService invoices;
    private final sn.groupia.subscription.application.service.SubscriptionPayments payments;

    SubscriptionController(
            OrganizationAccess access,
            SubscriptionManagementService subscriptions,
            sn.groupia.subscription.application.service.SubscriptionInvoiceService invoices,
            sn.groupia.subscription.application.service.SubscriptionPayments payments) {
        this.invoices = invoices;
        this.payments = payments;
        this.access = access;
        this.subscriptions = subscriptions;
    }

    @GetMapping
    ResponseEntity<SubscriptionDetails> details(@PathVariable UUID organizationId) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_SUBSCRIPTION);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(subscriptions.details(organizationId));
    }

    @GetMapping("/billing-contact")
    ResponseEntity<SubscriptionBillingContactView> billingContact(
            @PathVariable UUID organizationId) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_SUBSCRIPTION);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(subscriptions.details(organizationId).billingContact());
    }

    @PutMapping("/billing-contact")
    SubscriptionBillingContactView updateBillingContact(
            @PathVariable UUID organizationId, @Valid @RequestBody BillingContactInput input) {
        return subscriptions.updateBillingContact(
                organizationId, input.name(), input.phoneE164(), input.email());
    }

    @PostMapping("/start")
    ResponseEntity<SubscriptionDetails> start(@PathVariable UUID organizationId) {
        access.requirePermission(organizationId, OrganizationPermission.MANAGE_SUBSCRIPTION);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(subscriptions.start(organizationId));
    }

    @PostMapping("/cancel")
    ResponseEntity<SubscriptionDetails> cancel(@PathVariable UUID organizationId) {
        access.requirePermission(organizationId, OrganizationPermission.MANAGE_SUBSCRIPTION);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(subscriptions.cancel(organizationId));
    }

    @GetMapping("/history")
    ResponseEntity<List<SubscriptionPeriodView>> history(@PathVariable UUID organizationId) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_SUBSCRIPTION);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(subscriptions.history(organizationId));
    }

    @GetMapping("/invoices")
    @io.swagger.v3.oas.annotations.Operation(operationId = "listSubscriptionInvoices")
    ResponseEntity<List<sn.groupia.subscription.api.dto.SubscriptionInvoiceView>> invoices(
            @PathVariable UUID organizationId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(invoices.list(organizationId));
    }

    @GetMapping("/invoices/{invoiceId}")
    @io.swagger.v3.oas.annotations.Operation(operationId = "getSubscriptionInvoice")
    ResponseEntity<sn.groupia.subscription.api.dto.SubscriptionInvoiceView> invoice(
            @PathVariable UUID organizationId, @PathVariable UUID invoiceId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(invoices.get(organizationId, invoiceId));
    }

    @GetMapping("/invoice-preview")
    @io.swagger.v3.oas.annotations.Operation(operationId = "previewSubscriptionInvoice")
    ResponseEntity<sn.groupia.subscription.api.dto.SubscriptionInvoicePreview> preview(
            @PathVariable UUID organizationId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(invoices.preview(organizationId));
    }

    @PostMapping("/invoices")
    @io.swagger.v3.oas.annotations.Operation(operationId = "createSubscriptionInvoice")
    ResponseEntity<sn.groupia.subscription.api.dto.SubscriptionInvoiceView> createInvoice(
            @PathVariable UUID organizationId, @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(invoices.create(organizationId, key));
    }

    @PostMapping("/invoices/{invoiceId}/payment")
    @io.swagger.v3.oas.annotations.Operation(operationId = "paySubscriptionInvoice")
    ResponseEntity<sn.groupia.payment.api.dto.PaymentAttemptView> payInvoice(
            @PathVariable UUID organizationId,
            @PathVariable UUID invoiceId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SubscriptionPaymentInput input) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(payments.initiate(organizationId, invoiceId, input.provider(), key));
    }

    record SubscriptionPaymentInput(@NotBlank @Size(max = 64) String provider) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void reject(String key, Object value) {
            throw new sn.groupia.shared.api.ApiException(
                    400, "INVALID_REQUEST", "Champ de paiement non autorisé.");
        }
    }

    record BillingContactInput(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "^\\+221[0-9]{9}$") String phoneE164,
            @Email @Size(max = 254) String email) {}
}
