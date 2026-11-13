package sn.groupia.settlement.infrastructure.provider;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import sn.groupia.settlement.domain.model.MerchantPayout;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * LIVE merchant-wallet payout bridge. One persisted Groupia intent and provider Idempotency-Key per
 * payout; an initiation ACK is never evidence of settlement.
 *
 * <p>Disabled until SasPay authorizes marketplace withdrawals, approves beneficiary evidence, and
 * Groupia funds payout fees separately from merchant principal.
 */
@Component
@ConditionalOnProperty(name = "groupia.settlement.payout-provider", havingValue = "SASPAY")
public class SaspayMerchantPayoutProvider implements MerchantPayoutProvider {
    // A production deployment must not be able to transfer money just by flipping
    // flags: verified recipient identity and separately funded payout fees remain
    // contractually unresolved. Test-only loopback fixtures exercise the protocol.
    private static final boolean PRODUCTION_PAYOUT_RELEASED = false;
    private final boolean syntheticTransport;
    private BigDecimal approvedFeeReservationCeiling = BigDecimal.ZERO;
    private final Connection payin;
    private final RestClient http;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean enabled,
            marketplaceAuthorized,
            beneficiaryProofApproved,
            feeBudgetApproved;

    @Autowired
    public SaspayMerchantPayoutProvider(ObjectMapper json, Clock clock, Environment env) {
        this(
                Connection.from(env),
                json,
                clock,
                env.getProperty("groupia.settlement.saspay-payouts.enabled", Boolean.class, false),
                env.getProperty(
                        "groupia.settlement.saspay-payouts.marketplace-authorized",
                        Boolean.class,
                        false),
                env.getProperty(
                        "groupia.settlement.saspay-payouts.beneficiary-proof-approved",
                        Boolean.class,
                        false),
                env.getProperty(
                        "groupia.settlement.saspay-payouts.company-fee-budget-approved",
                        Boolean.class,
                        false),
                makeHttp(Connection.from(env)),
                false);
        // Operator-approved *ceiling*, not a SasPay quote or proof of wallet funding.
        // Defaults to zero and must remain zero until independent rate validation.
        try {
            var cap =
                    new BigDecimal(
                            env.getProperty(
                                    "groupia.settlement.saspay-payouts.approved-fee-ceiling-xof",
                                    "0"));
            if (cap.signum() > 0 && cap.scale() <= 2 && cap.precision() <= 10)
                approvedFeeReservationCeiling = cap;
        } catch (NumberFormatException ex) {
            approvedFeeReservationCeiling = BigDecimal.ZERO;
        }
    }

    SaspayMerchantPayoutProvider(
            Connection payin,
            ObjectMapper json,
            Clock clock,
            boolean enabled,
            boolean marketplaceAuthorized,
            boolean beneficiaryProofApproved,
            boolean feeBudgetApproved,
            RestClient http) {
        this(
                payin,
                json,
                clock,
                enabled,
                marketplaceAuthorized,
                beneficiaryProofApproved,
                feeBudgetApproved,
                http,
                true);
    }

    private SaspayMerchantPayoutProvider(
            Connection payin,
            ObjectMapper json,
            Clock clock,
            boolean enabled,
            boolean marketplaceAuthorized,
            boolean beneficiaryProofApproved,
            boolean feeBudgetApproved,
            RestClient http,
            boolean syntheticTransport) {
        this.syntheticTransport = syntheticTransport;
        // Tests use only injected localhost transports, never production credentials.
        // Their synthetic approved ceiling is independent of live configuration.
        if (syntheticTransport) approvedFeeReservationCeiling = new BigDecimal("200");
        this.payin = payin;
        this.json = json;
        this.clock = clock;
        this.enabled = enabled;
        this.marketplaceAuthorized = marketplaceAuthorized;
        this.beneficiaryProofApproved = beneficiaryProofApproved;
        this.feeBudgetApproved = feeBudgetApproved;
        this.http = http;
    }

    record Connection(
            String apiKey,
            UUID merchantId,
            boolean enabled,
            String waveNetwork,
            String orangeNetwork) {
        static Connection from(Environment env) {
            String key = env.getProperty("groupia.payment.saspay.api-key", "");
            String merchant = env.getProperty("groupia.payment.saspay.merchant-id", "");
            UUID id = UUID.fromString(merchant);
            boolean active =
                    env.getProperty("groupia.payment.saspay.enabled", Boolean.class, false);
            // No unverified guess: require provider-confirmed payout network codes.
            return new Connection(
                    key,
                    id,
                    active,
                    env.getProperty("groupia.settlement.saspay-payouts.wave-network", ""),
                    env.getProperty("groupia.settlement.saspay-payouts.orange-network", ""));
        }

        boolean isLive() {
            return apiKey != null && apiKey.startsWith("sk_live_");
        }

        @Override
        public String toString() {
            return "SaspayPayoutConnection[redacted]";
        }
    }

    static RestClient makeHttp(Connection p) {
        var transport =
                HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(java.time.Duration.ofSeconds(10));
        return RestClient.builder()
                .baseUrl("https://api.saspay.me/api/v1")
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + p.apiKey())
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Override
    public boolean enabled() {
        return (syntheticTransport || PRODUCTION_PAYOUT_RELEASED)
                && enabled
                && payin.enabled()
                && payin.isLive()
                && marketplaceAuthorized
                && beneficiaryProofApproved
                && feeBudgetApproved;
    }

    @Override
    public String name() {
        return "SASPAY";
    }

    @Override
    public String account() {
        return payin.merchantId().toString();
    }

    @Override
    public String fundingProvider() {
        return "SASPAY";
    }

    @Override
    public String fundingAccount() {
        return account();
    }

    @Override
    public BigDecimal feeReservationCeiling(MerchantPayout p) {
        return approvedFeeReservationCeiling;
    }

    @Override
    public String initiate(MerchantPayout p) {
        requireEnabled();
        assertIdentity(p);
        String recipientPhone = phone(p.phoneE164());
        var body = new LinkedHashMap<String, Object>();
        body.put("amount", p.amount().setScale(2).toPlainString());
        body.put("currency", "XOF");
        body.put("country", "SN");
        body.put("method", network(p.method()));
        body.put("customer", Map.of("phone", recipientPhone));
        body.put("recipient", Map.of("msisdn", recipientPhone.substring(4)));
        body.put("description", "Versement Groupia");
        body.put("fee_charge_mode", "ADD_ON");
        body.put(
                "metadata",
                Map.of(
                        "groupia_payout_id", p.id().toString(),
                        "groupia_reference", p.businessReference()));
        // Before any irreversible transfer, validate the currently advertised payout
        // rate and SN/XOF wallet liquidity. This does NOT establish fund ownership:
        // claim() has already reserved the fee in Groupia's independent budget.
        String payoutNetwork = network(p.method());
        JsonNode rates = exchange(http.get().uri("/pricing/my-rates/"), 200);
        BigDecimal quotedFee =
                SaspayPayoutPreflight.checkRates(rates, p, payoutNetwork, feeReservationCeiling(p));
        JsonNode balances = exchange(http.get().uri("/merchant-balances/"), 200);
        SaspayPayoutPreflight.checkWallet(balances, p, quotedFee, account());
        JsonNode ack =
                exchange(
                        http.post()
                                .uri("/payouts/initialize/")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", p.providerIdempotencyKey().toString())
                                .body(body),
                        201);
        return uuid(text(ack, "id"));
    }

    @Override
    public Proof verify(MerchantPayout p) {
        requireEnabled();
        assertIdentity(p);
        String id = uuid(p.providerTransactionId());
        var verification = exchange(http.get().uri("/payouts/{id}/verify/", id), 200);
        Instant checkedAt = clock.instant();
        // The verification endpoint refreshes a pending gateway status. Its example
        // response does not contain the credited phone number; a successful payout
        // needs independent, immutable transaction-detail evidence as well.
        if (!"SUCCESS".equals(text(verification, "status")))
            return validateProof(p, verification, checkedAt);
        var details = exchange(http.get().uri("/transactions/{id}/", id), 200);
        return validateProof(p, verification, details, checkedAt);
    }

    Proof validateProof(
            MerchantPayout p, JsonNode verification, JsonNode details, Instant checkedAt) {
        String verifiedStatus = validateFinancialState(p, verification);
        if (!"SUCCEEDED".equals(verifiedStatus))
            throw new IllegalStateException("SASPAY_PAYOUT_STATUS_CONFLICT");
        Proof detailProof = validateProof(p, details, checkedAt);
        if (!"SUCCEEDED".equals(detailProof.status()))
            throw new IllegalStateException("SASPAY_PAYOUT_STATUS_CONFLICT");
        // Both authenticated GET responses must independently agree on every
        // financial value, not merely on the principal owed to the merchant.
        for (String field :
                List.of("requested_amount", "client_fee", "debited_amount", "net_amount")) {
            if (money(verification, field).compareTo(money(details, field)) != 0)
                throw new IllegalStateException("SASPAY_PAYOUT_FINANCIAL_MISMATCH");
        }
        String phoneFromVerify = text(verification, "operation_msisdn");
        if (phoneFromVerify == null) {
            phoneFromVerify = text(verification.path("recipient"), "msisdn");
            if (phoneFromVerify == null) phoneFromVerify = text(verification, "recipient_msisdn");
        }
        if (phoneFromVerify != null && !detailProof.phoneE164().equals(phone(phoneFromVerify)))
            throw new IllegalStateException("SASPAY_PAYOUT_RECIPIENT_MISMATCH");
        String networkFromVerify = text(verification, "network_code");
        if (networkFromVerify != null && !network(p.method()).equals(networkFromVerify))
            throw new IllegalStateException("SASPAY_PAYOUT_NETWORK_EVIDENCE_MISSING");
        return detailProof;
    }

    Proof validateProof(MerchantPayout p, JsonNode data, Instant checkedAt) {
        String status = validateFinancialState(p, data);
        // For non-successful statuses, preserve any supplied destination evidence,
        // but never invent it. SUCCESS must have a credited MSISDN and network.
        String recipient = text(data, "operation_msisdn");
        if (recipient == null) recipient = text(data.path("recipient"), "msisdn");
        if (recipient == null) recipient = text(data, "recipient_msisdn");
        String verifiedPhone = null;
        if (recipient != null) {
            verifiedPhone = phone(recipient);
            if (!p.phoneE164().equals(verifiedPhone))
                throw new IllegalStateException("SASPAY_PAYOUT_RECIPIENT_MISMATCH");
        } else if ("SUCCEEDED".equals(status)) {
            throw new IllegalStateException("SASPAY_PAYOUT_RECIPIENT_EVIDENCE_MISSING");
        }
        String verifiedNetwork = text(data, "network_code");
        if (verifiedNetwork != null) {
            if (!network(p.method()).equals(verifiedNetwork))
                throw new IllegalStateException("SASPAY_PAYOUT_NETWORK_EVIDENCE_MISSING");
        } else if ("SUCCEEDED".equals(status)) {
            throw new IllegalStateException("SASPAY_PAYOUT_NETWORK_EVIDENCE_MISSING");
        }
        Instant occurredAt = checkedAt;
        String updated = text(data, "updated_at");
        if (updated != null)
            try {
                occurredAt = Instant.parse(updated);
            } catch (RuntimeException ex) {
                throw new IllegalStateException("SASPAY_PAYOUT_INVALID_TIMESTAMP");
            }
        return new Proof(
                true,
                name(),
                account(),
                p.businessReference(),
                uuid(text(data, "id")),
                p.amount(),
                "XOF",
                p.method(),
                verifiedPhone,
                status,
                occurredAt,
                money(data, "client_fee"));
    }

    private String validateFinancialState(MerchantPayout p, JsonNode data) {
        String transaction = uuid(text(data, "id"));
        if (!transaction.equals(uuid(p.providerTransactionId()))
                || !account().equals(uuid(text(data, "merchant")))
                || !"RETRAIT".equals(text(data, "transaction_type"))
                || !"OUTBOUND".equals(text(data, "flow_direction"))
                || !"XOF".equals(text(data, "currency"))
                || !"ADD_ON".equals(text(data, "fee_charge_mode")))
            throw new IllegalStateException("SASPAY_PAYOUT_EVIDENCE_MISMATCH");
        BigDecimal amount = money(data, "requested_amount");
        BigDecimal fee = money(data, "client_fee");
        BigDecimal charged = money(data, "debited_amount");
        BigDecimal net = money(data, "net_amount");
        if (p.amount().compareTo(amount) != 0
                || fee.signum() < 0
                || charged.compareTo(amount.add(fee)) != 0
                || net.compareTo(amount) != 0)
            throw new IllegalStateException("SASPAY_PAYOUT_FINANCIAL_MISMATCH");
        return switch (Objects.toString(text(data, "status"), "")) {
            case "PENDING" -> "PENDING";
            case "SUCCESS" -> "SUCCEEDED";
            case "FAILED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            default -> throw new IllegalStateException("SASPAY_PAYOUT_STATUS_UNKNOWN");
        };
    }

    private String network(String method) {
        String code =
                switch (method == null ? "" : method) {
                    case "WAVE" -> payin.waveNetwork();
                    case "ORANGE_MONEY" -> payin.orangeNetwork();
                    default ->
                            throw new IllegalArgumentException("SASPAY_PAYOUT_METHOD_UNSUPPORTED");
                };
        if (code == null || !code.matches("[a-z0-9_]{3,64}"))
            throw new IllegalStateException("SASPAY_PAYOUT_NETWORK_NOT_APPROVED");
        return code;
    }

    private static String phone(String raw) {
        if (raw == null) throw new IllegalArgumentException("SASPAY_PAYOUT_PHONE_INVALID");
        // Provider evidence may contain the national (9 digits), country-prefixed
        // (221 + 9 digits), or E.164 format. Compare only canonical E.164 values.
        String normalized = raw;
        if (raw.matches("[0-9]{9}")) normalized = "+221" + raw;
        else if (raw.matches("221[0-9]{9}")) normalized = "+" + raw;
        if (!normalized.matches("\\+221[0-9]{9}"))
            throw new IllegalArgumentException("SASPAY_PAYOUT_PHONE_INVALID");
        return normalized;
    }

    private void assertIdentity(MerchantPayout p) {
        if (!"SASPAY".equals(p.provider())
                || !account().equals(p.providerAccountReference())
                || !"XOF".equals(p.currency())
                || p.amount().signum() <= 0
                || p.amount().stripTrailingZeros().scale() > 0
                || p.providerIdempotencyKey() == null
                || p.businessReference() == null
                || !p.businessReference().startsWith("MERCHANT_PAYOUT:"))
            throw new IllegalArgumentException("SASPAY_PAYOUT_INTENT_INVALID");
    }

    private void requireEnabled() {
        if (!enabled()) throw new IllegalStateException("SASPAY_PAYOUT_NOT_AUTHORIZED");
    }

    private JsonNode exchange(RestClient.RequestHeadersSpec<?> request, int status) {
        try {
            return request.exchange(
                    (sent, response) -> {
                        if (response.getStatusCode().value() != status)
                            throw new IllegalStateException("SASPAY_PAYOUT_PROVIDER_REJECTED");
                        var ct = response.getHeaders().getContentType();
                        if (ct == null || !MediaType.APPLICATION_JSON.isCompatibleWith(ct))
                            throw new IllegalStateException("SASPAY_PAYOUT_INVALID_CONTENT_TYPE");
                        var bytes = response.getBody().readNBytes(65537);
                        if (bytes.length > 65536)
                            throw new IllegalStateException("SASPAY_PAYOUT_RESPONSE_TOO_LARGE");
                        JsonNode root = json.readTree(bytes);
                        if (root == null || (!root.isObject() && !root.isArray()))
                            throw new IllegalStateException("SASPAY_PAYOUT_INVALID_JSON");
                        if (root.has("success")) {
                            if (!root.path("success").isBoolean()
                                    || !root.path("success").booleanValue()
                                    || (!root.path("data").isObject()
                                            && !root.path("data").isArray()))
                                throw new IllegalStateException("SASPAY_PAYOUT_PROVIDER_REJECTED");
                            root = root.path("data");
                        }
                        return root;
                    });
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // An ambiguous transport failure never frees the reserved payout.
            throw new IllegalStateException("SASPAY_PAYOUT_TRANSPORT_UNCERTAIN");
        }
    }

    private static String uuid(String id) {
        // Same strict 32/36-character UUID normalization as the SasPay pay-in client.
        if (id == null) throw new IllegalStateException("SASPAY_PAYOUT_IDENTIFIER_INVALID");
        String canonical = id;
        if (id.matches("[0-9a-fA-F]{32}"))
            canonical =
                    id.substring(0, 8)
                            + "-"
                            + id.substring(8, 12)
                            + "-"
                            + id.substring(12, 16)
                            + "-"
                            + id.substring(16, 20)
                            + "-"
                            + id.substring(20);
        if (!canonical.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalStateException("SASPAY_PAYOUT_IDENTIFIER_INVALID");
        return UUID.fromString(canonical).toString();
    }

    private static String text(JsonNode n, String field) {
        var value = n.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static BigDecimal money(JsonNode n, String field) {
        var value = n.path(field);
        if (!value.isNumber() && !value.isTextual())
            throw new IllegalStateException("SASPAY_PAYOUT_AMOUNT_INVALID");
        try {
            var amount = new BigDecimal(value.asText());
            if (amount.signum() < 0 || amount.scale() > 2 || amount.precision() > 16)
                throw new IllegalStateException("SASPAY_PAYOUT_AMOUNT_INVALID");
            return amount;
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("SASPAY_PAYOUT_AMOUNT_INVALID");
        }
    }
}
