package sn.groupia.customeraccess.infrastructure.web;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import sn.groupia.customeraccess.api.dto.*;
import sn.groupia.customeraccess.application.service.*;
import sn.groupia.directsales.api.DirectOrderRecovery;
import sn.groupia.notification.api.DeliveryGateway;
import sn.groupia.ordering.api.dto.*;

@RestController
@RequestMapping("/api/public/customer-access")
class CustomerAccessController {
    private final CustomerShippingPayments payments;
    private final CustomerAccess access;
    private final AccessPolicy policy;
    private final DeliveryGateway delivery;
    private final DirectOrderRecovery directRecovery;

    CustomerAccessController(
            CustomerAccess access,
            AccessPolicy policy,
            DeliveryGateway delivery,
            CustomerShippingPayments payments,
            DirectOrderRecovery directRecovery) {
        this.payments = payments;
        this.access = access;
        this.policy = policy;
        this.delivery = delivery;
        this.directRecovery = directRecovery;
    }

    public record CsrfView(String token) {}

    public record PhoneInput(@NotBlank @Size(max = 64) String phone) {
        @Override
        public String toString() {
            return "PhoneInput[redacted]";
        }
    }

    public record OtpInput(@NotNull UUID challengeId, @NotBlank @Size(max = 32) String code) {
        @Override
        public String toString() {
            return "OtpInput[redacted]";
        }
    }

    public record MagicInput(@NotBlank @Size(max = 128) String token) {
        @Override
        public String toString() {
            return "MagicInput[redacted]";
        }
    }

    @GetMapping("/csrf")
    CsrfView customerCsrf(@io.swagger.v3.oas.annotations.Parameter(hidden = true) CsrfToken token) {
        return new CsrfView(token.getToken());
    }

    @PostMapping("/otp/request")
    OtpRequested requestCustomerOtp(
            @Valid @RequestBody PhoneInput body, HttpServletRequest request) {
        var issued = access.request(body.phone(), request.getRemoteAddr());
        // Challenge is committed before delivery. Never leak a provider response or exception.
        try {
            delivery.sendOtp(issued.response().challengeId(), issued.phone(), issued.code());
        } catch (RuntimeException unavailable) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn(
                            "code=OTP_DELIVERY_UNAVAILABLE challengeId={}",
                            issued.response().challengeId());
        }
        return issued.response();
    }

    @PostMapping("/otp/verify")
    ResponseEntity<CustomerSessionView> verifyCustomerOtp(
            @Valid @RequestBody OtpInput body, HttpServletRequest request) {
        return session(
                access.verify(
                        body.challengeId(), body.code(), cookie(request), request.getRemoteAddr()));
    }

    @PostMapping("/magic/exchange")
    ResponseEntity<CustomerSessionView> exchangeCustomerLink(
            @Valid @RequestBody MagicInput body, HttpServletRequest request) {
        return session(access.exchange(body.token(), cookie(request), request.getRemoteAddr()));
    }

    @GetMapping("/session")
    CustomerSessionView customerSession(HttpServletRequest request) {
        return access.sessionView(cookie(request));
    }

    @GetMapping("/direct-orders")
    ResponseEntity<DirectOrderRecovery.Page> verifiedDirectOrders(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result =
                directRecovery.forVerifiedPhone(access.verifiedPhone(cookie(request)), page, size);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(result);
    }

    @PostMapping("/direct-orders/{orderNumber}/rotate-link")
    ResponseEntity<DirectOrderRecovery.Rotated> rotateDirectLink(
            HttpServletRequest request, @PathVariable String orderNumber) {
        var result =
                directRecovery.rotateForVerifiedPhone(
                        access.verifiedPhoneForDirectRotation(
                                cookie(request), request.getRemoteAddr()),
                        orderNumber);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(result);
    }

    @GetMapping("/orders")
    MyOrdersPage customerOrders(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return access.list(cookie(request), page, size);
    }

    @GetMapping("/orders/{orderNumber}")
    OrderTrackingView customerOrder(HttpServletRequest request, @PathVariable String orderNumber) {
        return access.track(cookie(request), orderNumber);
    }

    public record ShippingPaymentInput(
            @NotBlank @Pattern(regexp = "(?i)BICTORYS|SASPAY") String provider,
            java.util.UUID feeQuoteId,
            String payerEmail) {
        public ShippingPaymentInput(String provider, java.util.UUID feeQuoteId) {
            this(provider, feeQuoteId, null);
        }
    }

    @PostMapping("/orders/{orderNumber}/shipping-balance/payment-quotes")
    java.util.List<sn.groupia.ordering.api.dto.CustomerFeeQuote> quoteCustomerShippingPayment(
            HttpServletRequest request, @PathVariable String orderNumber) {
        return payments.quotes(cookie(request), orderNumber);
    }

    @GetMapping("/orders/{orderNumber}/shipping-balance/payment-options")
    sn.groupia.ordering.api.dto.CustomerPaymentOptions customerShippingPaymentOptions(
            HttpServletRequest request, @PathVariable String orderNumber) {
        return payments.options(cookie(request), orderNumber);
    }

    @PostMapping("/orders/{orderNumber}/shipping-balance/payments")
    CustomerPaymentAttempt initiateCustomerShippingPayment(
            HttpServletRequest request,
            @PathVariable String orderNumber,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ShippingPaymentInput body) {
        return payments.initiate(
                cookie(request),
                orderNumber,
                body.provider(),
                key,
                body.feeQuoteId(),
                body.payerEmail());
    }

    @GetMapping("/orders/{orderNumber}/capabilities")
    CustomerOrderCapabilities customerOrderCapabilities(
            HttpServletRequest request, @PathVariable String orderNumber) {
        return access.capabilities(cookie(request), orderNumber);
    }

    @PostMapping("/orders/{orderNumber}/resend-link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resendCustomerLink(HttpServletRequest request, @PathVariable String orderNumber) {
        access.resend(cookie(request), orderNumber);
    }

    @PostMapping("/orders/{orderNumber}/revoke-link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeCustomerLink(HttpServletRequest request, @PathVariable String orderNumber) {
        access.revokeOrderLink(cookie(request), orderNumber);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logoutCustomer(HttpServletRequest request) {
        access.revoke(cookie(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookie("", 0))
                .build();
    }

    private ResponseEntity<CustomerSessionView> session(CustomerAccess.SessionIssued issued) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        sessionCookie(issued.token(), policy.settings.sessionSeconds()))
                .body(issued.view());
    }

    private String sessionCookie(String value, int seconds) {
        return ResponseCookie.from(policy.cookieName(), value)
                .httpOnly(true)
                .secure(policy.settings.secureCookie())
                .sameSite("Lax")
                .path("/")
                .maxAge(seconds)
                .build()
                .toString();
    }

    private String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> c.getName().equals(policy.cookieName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
