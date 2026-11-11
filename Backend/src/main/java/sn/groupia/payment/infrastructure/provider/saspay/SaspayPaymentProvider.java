package sn.groupia.payment.infrastructure.provider.saspay;

import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import sn.groupia.payment.api.PaymentStatus;
import sn.groupia.payment.domain.model.PaymentFeeQuote;
import sn.groupia.payment.domain.repository.PaymentQuoteStore;
import sn.groupia.payment.domain.service.*;
import tools.jackson.databind.*;

public final class SaspayPaymentProvider implements PaymentProvider {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SaspayPaymentProvider.class);
    private final SaspayClient client;
    private final SaspayProperties properties;
    private final PaymentQuoteStore quotes;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean allowFractionalXofFees;
    private final URI publicBaseUrl;
    private final boolean legacySoftpayForTests;

    SaspayPaymentProvider(
            SaspayClient client,
            SaspayProperties properties,
            PaymentQuoteStore quotes,
            ObjectMapper json,
            Clock clock) {
        this(client, properties, quotes, json, clock, false);
    }

    SaspayPaymentProvider(
            SaspayClient client,
            SaspayProperties properties,
            PaymentQuoteStore quotes,
            ObjectMapper json,
            Clock clock,
            boolean allowFractionalXofFees) {
        this(
                client,
                properties,
                quotes,
                json,
                clock,
                allowFractionalXofFees,
                URI.create("http://localhost:4200"));
    }

    SaspayPaymentProvider(
            SaspayClient client,
            SaspayProperties properties,
            PaymentQuoteStore quotes,
            ObjectMapper json,
            Clock clock,
            boolean allowFractionalXofFees,
            URI publicBaseUrl) {
        this(client, properties, quotes, json, clock, allowFractionalXofFees, publicBaseUrl, false);
    }

    /** Only synthetic regression fixtures may invoke old Softpay initiation. Never live. */
    SaspayPaymentProvider(
            SaspayClient client,
            SaspayProperties properties,
            PaymentQuoteStore quotes,
            ObjectMapper json,
            Clock clock,
            boolean allowFractionalXofFees,
            URI publicBaseUrl,
            boolean legacySoftpayForTests) {
        if (legacySoftpayForTests && properties.isLive())
            throw new IllegalArgumentException(
                    "Legacy Softpay fixture must never use LIVE credentials");
        this.legacySoftpayForTests = legacySoftpayForTests;
        this.client = client;
        this.properties = properties;
        this.quotes = quotes;
        this.json = json;
        this.clock = clock;
        this.allowFractionalXofFees = allowFractionalXofFees;
        this.publicBaseUrl = publicBaseUrl;
    }

    public String name() {
        return "SASPAY";
    }

    public String accountReference() {
        return properties.merchantId().toString();
    }

    public boolean requiresFeeQuote() {
        return false; // Network selection and fees are now owned by hosted SasPay checkout.
    }

    @Override
    public boolean canQuoteForOrganization(UUID organizationId) {
        return !properties.isLive() || properties.livePilotOrganizationId().equals(organizationId);
    }

    @Override
    public String initiationConfigurationIssue() {
        if (legacySoftpayForTests) return null;
        URI destination = publicBaseUrl.resolve("/paiement/retour");
        if ((properties.isLive() && !"https".equals(destination.getScheme()))
                || destination.getHost() == null
                || destination.getUserInfo() != null
                || destination.getFragment() != null
                || !Set.of("https", "http").contains(destination.getScheme()))
            return "SASPAY_RETURN_URL_INVALID";
        // SasPay's documented hosted checkout uses this domain. A Softpay-era
        // network allowlist may not contain it; detect that BEFORE any POST,
        // rather than creating a session whose checkout URL will be hidden.
        if (!properties.checkoutHosts().contains("pay.saspay.me"))
            return "SASPAY_HOSTED_CHECKOUT_HOST_NOT_ALLOWED";
        return null;
    }

    private void enforceLivePilotLimit(BigDecimal amount, BigDecimal fee) {
        if (properties.isLive()
                && (amount == null
                        || fee == null
                        || amount.add(fee).compareTo(properties.livePilotMaxChargedXof()) > 0))
            throw new SaspayException("SASPAY_LIVE_PILOT_LIMIT");
    }

    public ProviderFee quote(BigDecimal amount, String currency, String network) {
        var fee =
                SaspayPricing.quote(
                        client.rates(), amount, currency, network, allowFractionalXofFees);
        enforceLivePilotLimit(amount, fee.fee());
        return fee;
    }

    public List<ProviderFee> quotes(BigDecimal amount, String currency) {
        var rates = client.rates();
        if (!rates.isArray()) throw new SaspayException("SASPAY_INVALID_RATES");
        var result = new ArrayList<ProviderFee>();
        int countryCurrencyMatches = 0;
        for (var rate : rates) {
            if (!"SN".equals(text(rate, "country_code"))
                    || !currency.equals(text(rate, "currency"))) continue;
            countryCurrencyMatches++;
            var network = text(rate, "network_code");
            var label = text(rate, "network_name");
            // Log only validated provider identifiers and internal error codes; never rates or PII.
            var safeNetwork =
                    network != null && network.matches("[a-z0-9_]{1,64}")
                            ? network
                            : "(invalid-network-code)";
            if (label == null || label.isBlank() || label.length() > 120) {
                log.debug(
                        "SasPay quote skipped for network {}: INVALID_NETWORK_LABEL", safeNetwork);
                continue;
            }
            try {
                var fee =
                        SaspayPricing.quote(
                                rates, amount, currency, network, allowFractionalXofFees);
                enforceLivePilotLimit(amount, fee.fee());
                result.add(new ProviderFee(fee.country(), fee.network(), label, fee.fee()));
            } catch (SaspayException unsupported) {
                // Only fixed internal error codes are retained; do not log external rate values.
                log.debug(
                        "SasPay quote skipped for network {}: {}",
                        safeNetwork,
                        unsupported.getMessage());
            }
        }
        log.debug(
                "SasPay quote scan complete: rates={}, countryCurrencyMatches={}, acceptedNetworks={}",
                rates.size(),
                countryCurrencyMatches,
                result.size());
        return List.copyOf(result);
    }

    /**
     * Hosted SasPay checkout: create a session, not a financial transaction. A session ID MUST NOT
     * be stored as the provider payment transaction ID.
     */
    public ProviderObservation initiate(ProviderInstruction i) {
        if (legacySoftpayForTests) return initiateLegacySoftpayForTests(i);
        if (i.checkout() == null
                || i.checkout().feeQuoteId() != null
                || i.checkout().customerName() == null
                || i.checkout().customerName().isBlank()
                || i.checkout().email() == null
                || !i.checkout().email().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                || i.checkout().email().length() > 254)
            throw new SaspayException("SASPAY_HOSTED_CONTACT_REQUIRED");
        if (i.amount() == null || i.amount().signum() <= 0 || !"XOF".equals(i.currency()))
            throw new SaspayException("SASPAY_HOSTED_AMOUNT_INVALID");
        enforceLivePilotLimit(i.amount(), BigDecimal.ZERO);
        String issue = initiationConfigurationIssue();
        if (issue != null) throw new SaspayException(issue);
        URI returnUrl = publicBaseUrl.resolve("/paiement/retour");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("amount", i.amount().setScale(2).toPlainString());
        payload.put("currency", i.currency());
        payload.put("country", "SN");
        payload.put("description", i.checkout().businessReference());
        payload.put("customer_email", i.checkout().email());
        payload.put("customer_name", i.checkout().customerName());
        if (i.checkout().phone() != null && !i.checkout().phone().isBlank())
            payload.put("customer_phone", i.checkout().phone());
        payload.put("fee_charge_mode", "ADD_ON");
        payload.put("return_url", returnUrl.toString());
        payload.put(
                "metadata",
                Map.of(
                        "groupia_payment_attempt_id", i.paymentId().toString(),
                        "groupia_reference", i.merchantReference(),
                        "groupia_purpose", i.purpose().name()));
        // Non-idempotent endpoint: do not retry this POST on a timeout or 5xx.
        var created = client.createHostedCheckout(payload);
        var sessionId = SaspayClient.uuid(text(created, "id"));
        if (!accountReference().equals(text(created, "merchant"))
                || money(created, "amount").compareTo(i.amount()) != 0
                || !i.currency().equals(text(created, "currency")))
            throw new SaspayException("SASPAY_HOSTED_SESSION_MISMATCH");
        var url = text(created, "checkout_url");
        // If the external session was created with a disallowed redirect,
        // preserve its ID; never expose that URL nor create another session.
        ProviderMetadata.ReviewReason review = null;
        if (url == null || url.isBlank()) {
            review = ProviderMetadata.ReviewReason.PROVIDER_STATUS_UNKNOWN;
            url = null;
        } else {
            try {
                validateCheckout(url);
            } catch (SaspayException invalid) {
                log.warn("SasPay hosted checkout URL rejected for session (host not approved)");
                review = ProviderMetadata.ReviewReason.PROVIDER_STATUS_UNKNOWN;
                url = null;
            }
        }
        return new ProviderObservation(
                name(),
                accountReference(),
                i.merchantReference(),
                null,
                i.paymentRequestId(),
                i.orderId(),
                i.purpose(),
                i.amount(),
                i.currency(),
                PaymentStatus.PENDING,
                null,
                new ProviderMetadata(i.checkout().businessReference(), url, sessionId, review),
                i.target());
    }

    private ProviderObservation initiateLegacySoftpayForTests(ProviderInstruction i) {
        var q = accepted(i);
        enforceLivePilotLimit(q.amount(), q.fee());
        var context = i.checkout();
        if (context == null
                || context.customerName() == null
                || context.phone() == null
                || context.email() == null) throw new SaspayException("SASPAY_CUSTOMER_REQUIRED");
        var names = context.customerName().strip().split("\\s+", 2);
        if (names.length != 2 || names[1].isBlank())
            throw new SaspayException("SASPAY_CUSTOMER_REQUIRED");
        var fee = quote(i.amount(), i.currency(), q.network());
        if (fee.fee().compareTo(q.fee()) != 0) throw new SaspayException("SASPAY_RATE_CHANGED");
        var body = new LinkedHashMap<String, Object>();
        body.put("amount", i.amount().setScale(2).toPlainString());
        body.put("currency", i.currency());
        body.put("country", q.country());
        body.put("network", q.network());
        body.put("fee_charge_mode", "ADD_ON");
        body.put("description", context.businessReference());
        body.put(
                "customer",
                Map.of(
                        "email",
                        context.email(),
                        "first_name",
                        names[0],
                        "last_name",
                        names[1],
                        "phone",
                        context.phone()));
        body.put(
                "metadata",
                Map.of(
                        "groupia_reference", i.merchantReference(),
                        "groupia_purpose", i.purpose().name()));
        var data = client.initiate(body, i.providerIdempotencyKey());
        var id = SaspayClient.uuid(text(data, "id"));
        var url = text(data, "checkout_url");
        ProviderMetadata.ReviewReason review = null;
        if (url != null && !url.isBlank()) {
            try {
                validateCheckout(url);
            } catch (SaspayException ex) {
                url = null;
                review = ProviderMetadata.ReviewReason.PROVIDER_STATUS_UNKNOWN;
            }
        }
        return new ProviderObservation(
                name(),
                accountReference(),
                i.merchantReference(),
                id,
                i.paymentRequestId(),
                i.orderId(),
                i.purpose(),
                i.amount(),
                i.currency(),
                PaymentStatus.PENDING,
                null,
                new ProviderMetadata(context.businessReference(), url, null, review),
                i.target());
    }

    public ProviderObservation verify(ProviderInstruction i) {
        if (i.providerSessionId() == null) {
            if (i.providerTransactionId() == null
                    && i.checkout() != null
                    && i.checkout().feeQuoteId() == null) return recoverHostedCheckout(i);
            // Legacy Softpay payments may still be pending during migration. Keep
            // verification (not new initiation) available for old financial records.
            return verifyLegacySoftpay(i);
        }
        String sessionId = SaspayClient.uuid(i.providerSessionId());
        var session = client.hostedCheckoutDetail(sessionId);
        if (!sessionId.equals(SaspayClient.uuid(text(session, "id")))
                || !accountReference().equals(text(session, "merchant"))
                || money(session, "amount").compareTo(i.amount()) != 0
                || !i.currency().equals(text(session, "currency"))
                || session.path("metadata").isMissingNode()
                || !i.paymentId()
                        .toString()
                        .equals(text(session.path("metadata"), "groupia_payment_attempt_id"))
                || !i.merchantReference()
                        .equals(text(session.path("metadata"), "groupia_reference")))
            throw new SaspayException("SASPAY_HOSTED_SESSION_MISMATCH");

        var status = client.hostedCheckoutStatus(sessionId);
        if (!sessionId.equals(SaspayClient.uuid(text(status, "id"))))
            throw new SaspayException("SASPAY_HOSTED_SESSION_MISMATCH");
        String sessionStatus = text(status, "status");
        if (Set.of("PENDING", "CANCELLED", "EXPIRED").contains(sessionStatus)) {
            // These are session states, not financial transaction confirmations.
            // For a terminal unpaid session there is no transaction ID to invent.
            PaymentStatus state =
                    switch (sessionStatus) {
                        case "CANCELLED" -> PaymentStatus.CANCELLED;
                        case "EXPIRED" -> PaymentStatus.EXPIRED;
                        default -> PaymentStatus.PENDING;
                    };
            return new ProviderObservation(
                    name(),
                    accountReference(),
                    i.merchantReference(),
                    null,
                    i.paymentRequestId(),
                    i.orderId(),
                    i.purpose(),
                    i.amount(),
                    i.currency(),
                    state,
                    null,
                    new ProviderMetadata(
                            i.checkout() == null ? null : i.checkout().businessReference(),
                            null,
                            sessionId,
                            null),
                    i.target());
        }
        if (!"PAID".equals(sessionStatus))
            throw new SaspayException("SASPAY_HOSTED_SESSION_STATUS_UNSUPPORTED");
        if (!"SUCCESS".equals(text(status, "transaction_status")))
            throw new SaspayException("SASPAY_HOSTED_TRANSACTION_NOT_VERIFIED");
        String txnId = SaspayClient.uuid(text(status, "transaction_id"));
        if (i.providerTransactionId() != null
                && !txnId.equals(SaspayClient.uuid(i.providerTransactionId())))
            throw new SaspayException("SASPAY_HOSTED_TRANSACTION_MISMATCH");
        var data = client.verify(txnId);
        if (!txnId.equals(SaspayClient.uuid(text(data, "id")))
                || !accountReference().equals(text(data, "merchant"))
                || !"SUCCESS".equals(text(data, "status"))
                || !"PAIEMENT".equals(text(data, "transaction_type"))
                || !"INBOUND".equals(text(data, "flow_direction")))
            throw new SaspayException("SASPAY_HOSTED_TRANSACTION_MISMATCH");
        var amount = money(data, "requested_amount");
        var fee = money(data, "client_fee");
        var charged = money(data, "debited_amount");
        var net = money(data, "net_amount");
        try {
            fee = fee.setScale(2, java.math.RoundingMode.UNNECESSARY);
            charged = charged.setScale(2, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException unsupportedPrecision) {
            throw new SaspayException("SASPAY_XOF_PRECISION_UNCONFIRMED");
        }
        if (!"ADD_ON".equals(text(data, "fee_charge_mode"))
                || !i.currency().equals(text(data, "currency"))
                || amount.compareTo(i.amount()) != 0
                || net.compareTo(i.amount()) != 0
                || fee.signum() < 0
                || charged.compareTo(i.amount().add(fee)) != 0)
            throw new SaspayException("SASPAY_FINANCIAL_MISMATCH");
        enforceLivePilotLimit(amount, fee);
        return new ProviderObservation(
                name(),
                accountReference(),
                i.merchantReference(),
                txnId,
                i.paymentRequestId(),
                i.orderId(),
                i.purpose(),
                net,
                text(data, "currency"),
                PaymentStatus.SUCCEEDED,
                null,
                new ProviderMetadata(
                        i.checkout() == null ? null : i.checkout().businessReference(),
                        null,
                        sessionId,
                        null,
                        fee,
                        charged),
                i.target());
    }

    /**
     * A timeout after POST /checkout-sessions/ may have created a session even though Groupia did
     * not receive the response. Never repeat that POST. Search bounded GET pages for the unique
     * server-generated payment attempt ID.
     */
    private ProviderObservation recoverHostedCheckout(ProviderInstruction i) {
        JsonNode match = null;
        boolean exhausted = false;
        for (int page = 1; page <= 3; page++) {
            var pageData = client.hostedCheckoutSessions(page);
            if (!pageData.path("results").isArray())
                throw new SaspayException("SASPAY_HOSTED_SESSION_LIST_INVALID");
            for (var candidate : pageData.path("results")) {
                if (i.paymentId()
                                .toString()
                                .equals(
                                        text(
                                                candidate.path("metadata"),
                                                "groupia_payment_attempt_id"))
                        && i.merchantReference()
                                .equals(text(candidate.path("metadata"), "groupia_reference"))
                        && accountReference().equals(text(candidate, "merchant"))) {
                    if (match != null)
                        throw new SaspayException("SASPAY_HOSTED_DUPLICATE_SESSIONS");
                    match = candidate;
                }
            }
            var next = pageData.path("next");
            if (next.isNull()
                    || next.isMissingNode()
                    || (next.isTextual() && next.asText().isBlank())) {
                exhausted = true;
                break;
            }
        }
        if (!exhausted) throw new SaspayException("SASPAY_HOSTED_RECOVERY_NOT_EXHAUSTIVE");
        if (match == null) throw new SaspayException("SASPAY_HOSTED_SESSION_NOT_FOUND");
        if (money(match, "amount").compareTo(i.amount()) != 0
                || !i.currency().equals(text(match, "currency")))
            throw new SaspayException("SASPAY_HOSTED_SESSION_MISMATCH");
        var id = SaspayClient.uuid(text(match, "id"));
        String url = text(match, "checkout_url");
        ProviderMetadata.ReviewReason review = null;
        if (url == null || url.isBlank()) {
            review = ProviderMetadata.ReviewReason.PROVIDER_STATUS_UNKNOWN;
            url = null;
        } else {
            try {
                validateCheckout(url);
            } catch (SaspayException badUrl) {
                url = null;
                review = ProviderMetadata.ReviewReason.PROVIDER_STATUS_UNKNOWN;
            }
        }
        return new ProviderObservation(
                name(),
                accountReference(),
                i.merchantReference(),
                null,
                i.paymentRequestId(),
                i.orderId(),
                i.purpose(),
                i.amount(),
                i.currency(),
                PaymentStatus.PENDING,
                null,
                new ProviderMetadata(i.checkout().businessReference(), url, id, review),
                i.target());
    }

    private ProviderObservation verifyLegacySoftpay(ProviderInstruction i) {
        var q = accepted(i);
        var data = client.verify(i.providerTransactionId());
        var id = SaspayClient.uuid(text(data, "id"));
        if (!id.equals(SaspayClient.uuid(i.providerTransactionId()))
                || !accountReference().equals(text(data, "merchant"))
                || !"PAIEMENT".equals(text(data, "transaction_type"))
                || !"INBOUND".equals(text(data, "flow_direction")))
            throw new SaspayException("SASPAY_TRANSACTION_MISMATCH");
        var amount = money(data, "requested_amount");
        var fee = money(data, "client_fee");
        var charged = money(data, "debited_amount");
        var net = money(data, "net_amount");
        if (!"ADD_ON".equals(text(data, "fee_charge_mode"))
                || !q.currency().equals(text(data, "currency"))
                || amount.compareTo(q.amount()) != 0
                || net.compareTo(q.amount()) != 0
                || fee.compareTo(q.fee()) != 0
                || charged.compareTo(q.amount().add(q.fee())) != 0)
            throw new SaspayException("SASPAY_FINANCIAL_MISMATCH");
        return new ProviderObservation(
                name(),
                accountReference(),
                i.merchantReference(),
                id,
                i.paymentRequestId(),
                i.orderId(),
                i.purpose(),
                net,
                text(data, "currency"),
                status(text(data, "status")),
                null,
                null,
                i.target());
    }

    public boolean verifyAuthenticity(byte[] payload, Map<String, String> headers) {
        return SaspayWebhookAuthentication.verify(
                payload, headers, properties.webhookSecret(), clock);
    }

    public NormalizedWebhook parse(byte[] payload) {
        var root = json.readTree(payload);
        var event = text(root, "event");
        if (!Set.of("transaction.created", "transaction.success", "transaction.failed")
                .contains(event == null ? "" : event))
            throw new SaspayException("SASPAY_UNSUPPORTED_EVENT");
        var data = root.path("data");
        if (!"PAIEMENT".equals(text(data, "type"))
                || !"ADD_ON".equals(text(data, "fee_charge_mode")))
            throw new SaspayException("SASPAY_UNSUPPORTED_FLOW");
        var state = status(text(data, "status"));
        String expected =
                switch (state) {
                    case SUCCEEDED -> "transaction.success";
                    case FAILED -> "transaction.failed";
                    default -> "transaction.created";
                };
        if (!expected.equals(event)) throw new SaspayException("SASPAY_INCONSISTENT_EVENT");
        var amount = money(data, "amount");
        var fee = money(data, "fee");
        var net = money(data, "net_amount");
        if (net.compareTo(amount) != 0
                || money(data, "charged").compareTo(amount.add(fee)) != 0
                || !"XOF".equals(text(data, "currency")))
            throw new SaspayException("SASPAY_FINANCIAL_MISMATCH");
        // No documented unique event ID: the existing durable receipt hashes the raw body.
        return new NormalizedWebhook(
                null,
                new ProviderObservation(
                        name(),
                        accountReference(),
                        null,
                        SaspayClient.uuid(text(data, "id")),
                        null,
                        null,
                        null,
                        net,
                        text(data, "currency"),
                        state,
                        null));
    }

    private PaymentFeeQuote accepted(ProviderInstruction i) {
        var q =
                quotes.find(i.checkout() == null ? null : i.checkout().feeQuoteId())
                        .orElseThrow(() -> new SaspayException("SASPAY_QUOTE_REQUIRED"));
        if (!q.requestId().equals(i.paymentRequestId())
                || !name().equals(i.provider())
                || !name().equals(q.provider())
                || !accountReference().equals(i.providerAccountReference())
                || !accountReference().equals(q.account())
                || q.amount().compareTo(i.amount()) != 0
                || !q.currency().equals(i.currency()))
            throw new SaspayException("SASPAY_QUOTE_MISMATCH");
        return q;
    }

    private void validateCheckout(String url) {
        try {
            var uri = URI.create(url);
            if (url.length() > 4096
                    || !"https".equals(uri.getScheme())
                    || !properties.checkoutHosts().contains(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443))
                throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw new SaspayException("SASPAY_INVALID_CHECKOUT_URL");
        }
    }

    private static BigDecimal money(JsonNode node, String field) {
        return SaspayPricing.decimal(node, field);
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText() : null;
    }

    private static PaymentStatus status(String value) {
        if (value == null) throw new SaspayException("SASPAY_STATUS_UNKNOWN");
        return switch (value) {
            case "SUCCESS" -> PaymentStatus.SUCCEEDED;
            case "PENDING" -> PaymentStatus.PENDING;
            case "FAILED" -> PaymentStatus.FAILED;
            default -> throw new SaspayException("SASPAY_STATUS_UNKNOWN");
        };
    }
}
