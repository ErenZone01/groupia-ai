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
        this.client = client;
        this.properties = properties;
        this.quotes = quotes;
        this.json = json;
        this.clock = clock;
        this.allowFractionalXofFees = allowFractionalXofFees;
    }

    public String name() {
        return "SASPAY";
    }

    public String accountReference() {
        return properties.merchantId().toString();
    }

    public boolean requiresFeeQuote() {
        return true;
    }

    @Override
    public boolean canQuoteForOrganization(UUID organizationId) {
        return !properties.isLive() || properties.livePilotOrganizationId().equals(organizationId);
    }

    private void enforceLivePilotLimit(BigDecimal amount, BigDecimal fee) {
        if (properties.isLive()
                && (amount == null || fee == null
                        || amount.add(fee).compareTo(properties.livePilotMaxChargedXof()) > 0))
            throw new SaspayException("SASPAY_LIVE_PILOT_LIMIT");
    }

    public ProviderFee quote(BigDecimal amount, String currency, String network) {
        var fee = SaspayPricing.quote(client.rates(), amount, currency, network, allowFractionalXofFees);
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
                log.debug("SasPay quote skipped for network {}: INVALID_NETWORK_LABEL", safeNetwork);
                continue;
            }
            try {
                var fee = SaspayPricing.quote(rates, amount, currency, network, allowFractionalXofFees);
                enforceLivePilotLimit(amount, fee.fee());
                result.add(new ProviderFee(fee.country(), fee.network(), label, fee.fee()));
            } catch (SaspayException unsupported) {
                // Only fixed internal error codes are retained; do not log external rate values.
                log.debug("SasPay quote skipped for network {}: {}", safeNetwork, unsupported.getMessage());
            }
        }
        log.debug(
                "SasPay quote scan complete: rates={}, countryCurrencyMatches={}, acceptedNetworks={}",
                rates.size(),
                countryCurrencyMatches,
                result.size());
        return List.copyOf(result);
    }

    public ProviderObservation initiate(ProviderInstruction i) {
        var q = accepted(i);
        if (!canQuoteForOrganization(q.organizationId()))
            throw new SaspayException("SASPAY_LIVE_PILOT_ORGANIZATION");
        enforceLivePilotLimit(q.amount(), q.fee());
        if (!clock.instant().isBefore(q.expiresAt()))
            throw new SaspayException("SASPAY_QUOTE_EXPIRED");
        var context = i.checkout();
        if (context.customerName() == null
                || context.customerName().isBlank()
                || context.phone() == null
                || !context.phone().matches("\\+221[0-9]{9}")
                || context.email() == null
                || context.email().isBlank()) throw new SaspayException("SASPAY_CUSTOMER_REQUIRED");
        var names = context.customerName().strip().split("\\s+", 2);
        if (names.length != 2 || names[1].isBlank())
            throw new SaspayException("SASPAY_CUSTOMER_REQUIRED");
        var fresh = quote(i.amount(), i.currency(), q.network());
        if (fresh.fee().compareTo(q.fee()) != 0) throw new SaspayException("SASPAY_RATE_CHANGED");
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
                        "groupia_reference",
                        i.merchantReference(),
                        "groupia_purpose",
                        i.purpose().name()));
        var data = client.initiate(body, i.providerIdempotencyKey());
        // The provider has already created a real transaction. Never discard its ID
        // when its redirect target is unexpected: otherwise webhooks cannot correlate
        // and a retry could produce another charge.
        var id = SaspayClient.uuid(text(data, "id"));
        var url = text(data, "checkout_url");
        if (url != null && url.isBlank()) url = null;
        ProviderMetadata.ReviewReason review = null;
        if (url != null) {
            try {
                validateCheckout(url);
            } catch (SaspayException rejected) {
                String host = "(invalid)";
                try {
                    var actualHost = URI.create(url).getHost();
                    if (actualHost != null && actualHost.matches("[a-z0-9]+(?:[.-][a-z0-9]+)*\\.[a-z]{2,}"))
                        host = actualHost;
                } catch (RuntimeException ignored) {
                    // Don't log provider-supplied URLs, query strings or customer tokens.
                }
                log.warn(
                        "SasPay redirect blocked after initiation: network={}, host={}, code=SASPAY_INVALID_CHECKOUT_URL",
                        q.network(),
                        host);
                url = null;
                review = ProviderMetadata.ReviewReason.PROVIDER_STATUS_UNKNOWN;
            }
        }
        // Initiation never proves success, including a SUCCESS acknowledgement.
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
        var q = accepted(i);
        var data = client.verify(i.providerTransactionId());
        var id = SaspayClient.uuid(text(data, "id"));
        if (!id.equals(i.providerTransactionId())
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
