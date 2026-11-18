package sn.groupia.settlement.infrastructure.provider;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import sn.groupia.settlement.domain.model.MerchantPayout;
import sn.groupia.settlement.domain.model.MerchantPayoutFeeQuote;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;
import sn.groupia.settlement.domain.service.PayoutProviderFailure;
import sn.groupia.settlement.domain.service.PayoutProviderFailure.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * LIVE merchant-wallet payout bridge. One persisted Groupia intent and provider Idempotency-Key per
 * payout; an initiation ACK is never evidence of settlement.
 *
 * <p>Disabled until SasPay authorizes marketplace withdrawals, approves beneficiary evidence, and
 * The seller explicitly confirms DEDUCTED fees before the provider is called.
 */
@Component
@ConditionalOnProperty(name = "groupia.settlement.payout-provider", havingValue = "SASPAY")
public class SaspayMerchantPayoutProvider implements MerchantPayoutProvider {
    // No general LIVE payouts. Only a separately opted-in one-order, one-shop
    // pilot can use production transport; synthetic tests remain isolated.
    private final boolean syntheticTransport;
    private final boolean livePilotReleased;
    private final boolean generalPayoutsEnabled;
    private final UUID livePilotOrganizationId;
    private final UUID livePilotDirectOrderId;
    private final Connection payin;
    private final RestClient http;
    private final ObjectMapper json;
    private final Clock clock;
    private MerchantFinanceStore financeStore;
    private final boolean enabled, marketplaceAuthorized, beneficiaryProofApproved;

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
                makeHttp(Connection.from(env)),
                false,
                env.getProperty(
                        "groupia.settlement.saspay-payout-live-pilot.enabled",
                        Boolean.class,
                        false),
                optionalUuid(
                        env.getProperty("groupia.settlement.saspay-payout-pilot.organization-id")),
                optionalUuid(
                        env.getProperty(
                                "groupia.settlement.saspay-payout-live-pilot.direct-order-id")),
                env.getProperty(
                        "groupia.settlement.saspay-payout-general.enabled", Boolean.class, false));
    }

    @Autowired
    public SaspayMerchantPayoutProvider(
            ObjectMapper json, Clock clock, Environment env, MerchantFinanceStore financeStore) {
        this(json, clock, env);
        this.financeStore = financeStore;
    }

    SaspayMerchantPayoutProvider(
            Connection payin,
            ObjectMapper json,
            Clock clock,
            boolean enabled,
            boolean marketplaceAuthorized,
            boolean beneficiaryProofApproved,
            RestClient http) {
        this(
                payin,
                json,
                clock,
                enabled,
                marketplaceAuthorized,
                beneficiaryProofApproved,
                http,
                true,
                false,
                null,
                null,
                false);
    }

    private SaspayMerchantPayoutProvider(
            Connection payin,
            ObjectMapper json,
            Clock clock,
            boolean enabled,
            boolean marketplaceAuthorized,
            boolean beneficiaryProofApproved,
            RestClient http,
            boolean syntheticTransport,
            boolean livePilotReleased,
            UUID livePilotOrganizationId,
            UUID livePilotDirectOrderId,
            boolean generalPayoutsEnabled) {
        this.generalPayoutsEnabled = generalPayoutsEnabled;
        this.syntheticTransport = syntheticTransport;
        this.livePilotReleased = livePilotReleased;
        this.livePilotOrganizationId = livePilotOrganizationId;
        this.livePilotDirectOrderId = livePilotDirectOrderId;
        this.payin = payin;
        this.json = json;
        this.clock = clock;
        this.enabled = enabled;
        this.marketplaceAuthorized = marketplaceAuthorized;
        this.beneficiaryProofApproved = beneficiaryProofApproved;
        this.http = http;
    }

    private static UUID optionalUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            // Invalid pilot target must fail closed without exposing its value.
            return null;
        }
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
        return (syntheticTransport
                        || generalPayoutsEnabled
                        || (livePilotReleased
                                && livePilotOrganizationId != null
                                && livePilotDirectOrderId != null))
                && enabled
                && payin.enabled()
                && payin.isLive()
                && marketplaceAuthorized
                && beneficiaryProofApproved;
    }

    @Override
    public boolean administrativeApprovalEligible() {
        return livePilotOrganizationId != null
                && livePilotDirectOrderId != null
                && payin.enabled()
                && payin.isLive()
                && marketplaceAuthorized
                && beneficiaryProofApproved;
    }

    @Override
    public boolean verificationEnabled() {
        return (syntheticTransport || generalPayoutsEnabled || livePilotOrganizationId != null)
                && payin.enabled()
                && payin.isLive()
                && marketplaceAuthorized
                && beneficiaryProofApproved;
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
    public BigDecimal payoutFeeQuote(BigDecimal grossAmount, String method) {
        return at(
                Stage.QUOTE,
                () -> {
                    requireEnabled();
                    JsonNode rates =
                            exchange(http.get().uri("/pricing/my-rates/"), Stage.QUOTE, 200);
                    return at(
                            Stage.QUOTE,
                            200,
                            () ->
                                    SaspayPayoutPreflight.quoteRates(
                                            rates, grossAmount, network(method)));
                });
    }

    @Override
    public String initiate(MerchantPayout p) {
        return at(Stage.INTENT, () -> initialize(p));
    }

    private String initialize(MerchantPayout p) {
        requireEnabled();
        if (p.nonExecuted()) throw new IllegalStateException("SASPAY_PAYOUT_INTENT_INVALID");
        assertIdentity(p);
        assertLivePilotTarget(p);
        String recipientPhone = phone(p.phoneE164());
        var body = new LinkedHashMap<String, Object>();
        body.put("amount", p.amount().setScale(2).toPlainString());
        body.put("currency", "XOF");
        body.put("country", "SN");
        body.put("method", network(p.method()));
        body.put("customer", Map.of("phone", recipientPhone));
        body.put("recipient", Map.of("msisdn", recipientPhone.substring(4)));
        body.put("description", "Versement Groupia");
        body.put("fee_charge_mode", "DEDUCTED");
        body.put(
                "metadata",
                Map.of(
                        "groupia_payout_id", p.id().toString(),
                        "groupia_reference", p.businessReference()));
        // A provider quotation never substitutes for the persisted quote explicitly
        // accepted by the seller. Any rate change aborts before the irreversible POST.
        MerchantPayoutFeeQuote accepted = null;
        if (!syntheticTransport) {
            if (financeStore == null)
                throw new IllegalStateException("SASPAY_PAYOUT_FEE_QUOTE_MISSING");
            accepted = financeStore.payoutFeeQuote(p.id());
            if (accepted == null)
                throw new IllegalStateException("SASPAY_PAYOUT_FEE_QUOTE_MISSING");
            if (!p.organizationId().equals(accepted.organizationId())
                    || !p.destinationId().equals(accepted.destinationId())
                    || !p.method().equals(accepted.method())
                    || !p.phoneE164().equals(accepted.phoneE164())
                    || p.amount().compareTo(accepted.grossAmount()) != 0
                    || !account().equals(accepted.providerAccountReference()))
                throw new IllegalStateException("SASPAY_PAYOUT_FEE_QUOTE_MISMATCH");
        }
        String payoutNetwork = network(p.method());
        MerchantPayoutFeeQuote quote = accepted;
        BigDecimal quotedFee =
                at(
                        Stage.RATES,
                        () -> {
                            JsonNode rates =
                                    exchange(
                                            http.get().uri("/pricing/my-rates/"), Stage.RATES, 200);
                            return at(
                                    Stage.RATES,
                                    200,
                                    () ->
                                            quote == null
                                                    ? SaspayPayoutPreflight.quoteRates(
                                                            rates, p.amount(), payoutNetwork)
                                                    : SaspayPayoutPreflight.checkRates(
                                                            rates,
                                                            p,
                                                            payoutNetwork,
                                                            quote.providerFee()));
                        });
        at(
                Stage.WALLETS,
                () -> {
                    var pages = merchantWalletPages();
                    at(
                            Stage.WALLETS,
                            200,
                            () -> {
                                SaspayPayoutPreflight.checkWallet(pages, p, quotedFee, account());
                                return null;
                            });
                    return null;
                });
        // Complete serialization before a POST can be transmitted.
        byte[] serialized;
        try {
            serialized = json.writeValueAsBytes(body);
        } catch (RuntimeException ex) {
            throw failure(Stage.SERIALIZE, 0, Category.SERIALIZATION, Reason.SERIALIZATION_FAILED);
        }
        return at(
                Stage.INITIALIZE,
                () -> {
                    JsonNode ack =
                            exchange(
                                    http.post()
                                            .uri("/payouts/initialize/")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .header(
                                                    "Idempotency-Key",
                                                    p.providerIdempotencyKey().toString())
                                            .body(serialized),
                                    Stage.INITIALIZE,
                                    201);
                    try {
                        return uuid(text(ack, "id"));
                    } catch (RuntimeException ex) {
                        throw failure(
                                Stage.INITIALIZE,
                                201,
                                Category.CONTRACT,
                                Reason.IDENTIFIER_INVALID);
                    }
                });
    }

    private List<JsonNode> merchantWalletPages() {
        var pages = new ArrayList<JsonNode>();
        var visited = new HashSet<String>();
        String path = "/merchant-balances/";
        for (int i = 0; i < 10; i++) {
            JsonNode page = exchange(http.get().uri(path), Stage.WALLETS, 200);
            pages.add(page);
            if (page == null || !page.isObject())
                throw new IllegalStateException("SASPAY_PAYOUT_WALLET_UNVERIFIED");
            JsonNode next = page.path("next");
            if (next.isNull() || next.isMissingNode()) return pages;
            if (!next.isTextual() || next.asText().isBlank())
                throw new IllegalStateException("SASPAY_PAYOUT_WALLET_UNVERIFIED");
            path = safeWalletNext(next.asText());
            if (!visited.add(path))
                throw new IllegalStateException("SASPAY_PAYOUT_WALLET_UNVERIFIED");
        }
        throw new IllegalStateException("SASPAY_PAYOUT_WALLET_UNVERIFIED");
    }

    /**
     * Never follow a provider-supplied next URL directly with a bearer token. Only return the
     * vetted relative path for the same SasPay endpoint.
     */
    static String safeWalletNext(String next) {
        try {
            if (next == null || next.length() > 1024)
                throw new IllegalArgumentException("Invalid next");
            URI base = URI.create("https://api.saspay.me/api/v1/merchant-balances/");
            URI uri = base.resolve(URI.create(next));
            String query = uri.getRawQuery();
            if (!"https".equals(uri.getScheme())
                    || !"api.saspay.me".equals(uri.getHost())
                    || uri.getPort() != -1
                    || uri.getUserInfo() != null
                    || uri.getRawFragment() != null
                    || !"/api/v1/merchant-balances/".equals(uri.getRawPath())
                    || query == null
                    || !query.matches("[a-zA-Z0-9_%=+&.,~-]{1,500}"))
                throw new IllegalArgumentException("Untrusted wallet pagination URL");
            return "/merchant-balances/?" + query;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("SASPAY_PAYOUT_WALLET_UNVERIFIED");
        }
    }

    @Override
    public Proof verify(MerchantPayout p) {
        return at(Stage.VERIFY, () -> verifyExisting(p));
    }

    private Proof verifyExisting(MerchantPayout p) {
        if (!verificationEnabled()) throw new IllegalStateException("SASPAY_PAYOUT_NOT_AUTHORIZED");
        assertIdentity(p);
        // Retargeting or closing the initiation gate must not strand an older intention.
        // The organization, account and immutable reference still constrain every GET.
        if (!syntheticTransport
                && !generalPayoutsEnabled
                && !livePilotOrganizationId.equals(p.organizationId()))
            throw new IllegalStateException("SASPAY_PAYOUT_LIVE_PILOT_TARGET_MISMATCH");
        String id = uuid(p.providerTransactionId());
        var verification = exchange(http.get().uri("/payouts/{id}/verify/", id), Stage.VERIFY, 200);
        Instant checkedAt = clock.instant();
        // The verification endpoint refreshes a pending gateway status. Its example
        // response does not contain the credited phone number; a successful payout
        // needs independent, immutable transaction-detail evidence as well.
        if (!"SUCCESS".equals(text(verification, "status")))
            return at(Stage.VERIFY, 200, () -> validateProof(p, verification, checkedAt));
        return at(
                Stage.DETAILS,
                () -> {
                    var details =
                            exchange(http.get().uri("/transactions/{id}/", id), Stage.DETAILS, 200);
                    return at(
                            Stage.DETAILS,
                            200,
                            () -> validateProof(p, verification, details, checkedAt));
                });
    }

    Proof validateProof(
            MerchantPayout p, JsonNode verification, JsonNode details, Instant checkedAt) {
        String verifiedStatus = validateFinancialState(p, verification);
        if (!"SUCCEEDED".equals(verifiedStatus))
            throw new IllegalStateException("SASPAY_PAYOUT_STATUS_CONFLICT");
        // LIVE SasPay uses TRANSFERT for Wave payouts; legacy responses use RETRAIT.
        // Classification must agree across both independently authenticated GETs.
        if (!Objects.equals(
                text(verification, "transaction_type"), text(details, "transaction_type")))
            throw new IllegalStateException("SASPAY_PAYOUT_EVIDENCE_MISMATCH");
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
                || !Set.of("RETRAIT", "TRANSFERT").contains(text(data, "transaction_type"))
                || !"OUTBOUND".equals(text(data, "flow_direction"))
                || !"XOF".equals(text(data, "currency"))
                || !"DEDUCTED".equals(text(data, "fee_charge_mode")))
            throw new IllegalStateException("SASPAY_PAYOUT_EVIDENCE_MISMATCH");
        BigDecimal amount = money(data, "requested_amount");
        BigDecimal fee = money(data, "client_fee");
        BigDecimal charged = money(data, "debited_amount");
        BigDecimal net = money(data, "net_amount");
        if (p.amount().compareTo(amount) != 0
                || fee.signum() < 0
                || fee.compareTo(amount) >= 0
                || charged.compareTo(amount) != 0
                || net.compareTo(amount.subtract(fee)) != 0)
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

    // Last line of defense before initiation. No grouping or other order/network can be sent.
    void assertLivePilotTarget(MerchantPayout p) {
        if (syntheticTransport || generalPayoutsEnabled) return;
        if (!livePilotReleased
                || livePilotOrganizationId == null
                || livePilotDirectOrderId == null
                || !livePilotOrganizationId.equals(p.organizationId())
                || !p.directSale()
                || !livePilotDirectOrderId.equals(p.directOrderId())
                || !"WAVE".equals(p.method())
                || p.amount() == null
                || p.amount().signum() <= 0
                || p.amount().compareTo(new BigDecimal("1000")) > 0
                || p.amount().stripTrailingZeros().scale() > 0)
            throw new IllegalStateException("SASPAY_PAYOUT_LIVE_PILOT_TARGET_MISMATCH");
    }

    private void requireEnabled() {
        if (!enabled()) throw new IllegalStateException("SASPAY_PAYOUT_NOT_AUTHORIZED");
    }

    private static <T> T at(Stage stage, Supplier<T> action) {
        return at(stage, 0, action);
    }

    private static <T> T at(Stage stage, int httpStatus, Supplier<T> action) {
        try {
            return action.get();
        } catch (PayoutProviderFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            Reason reason = Reason.LOCAL_VALIDATION_FAILED;
            String message = ex.getMessage();
            if (message != null && message.startsWith("SASPAY_PAYOUT_")) {
                try {
                    reason = Reason.valueOf(message.substring("SASPAY_PAYOUT_".length()));
                } catch (IllegalArgumentException ignored) {
                    /* Closed vocabulary only. */
                }
            }
            throw failure(stage, httpStatus, Category.VALIDATION, reason);
        }
    }

    private static PayoutProviderFailure failure(
            Stage stage, int status, Category category, Reason reason) {
        return new PayoutProviderFailure(stage, status, category, reason, ProviderCode.NONE);
    }

    private static ProviderCode providerCode(JsonNode root) {
        JsonNode code = root.path("error").path("code");
        if (!code.isTextual()) code = root.path("code");
        if (!code.isTextual()) return ProviderCode.NONE;
        // Only exact documented codes, not arbitrary strings matching a regex.
        return switch (code.asText()) {
            case "ip_not_whitelisted",
                    "payout_not_enabled",
                    "currency_country_mismatch",
                    "invalid_country",
                    "invalid_customer",
                    "invalid_method",
                    "no_route_available",
                    "wallet_frozen",
                    "insufficient_balance",
                    "merchant_suspended",
                    "not_found" ->
                    ProviderCode.valueOf(code.asText());
            default -> ProviderCode.UNRECOGNIZED;
        };
    }

    private static Category httpCategory(int status) {
        return switch (status) {
            case 401 -> Category.AUTHENTICATION;
            case 403 -> Category.AUTHORIZATION;
            case 409 -> Category.CONFLICT;
            case 429 -> Category.RATE_LIMIT;
            default ->
                    status >= 500
                            ? Category.SERVER_ERROR
                            : status >= 400 ? Category.PROVIDER_REJECTION : Category.CONTRACT;
        };
    }

    private JsonNode exchange(RestClient.RequestHeadersSpec<?> request, Stage stage, int expected) {
        try {
            return request.exchange(
                    (sent, response) -> {
                        int status = response.getStatusCode().value();
                        MediaType ct;
                        try {
                            ct = response.getHeaders().getContentType();
                        } catch (RuntimeException ex) {
                            throw failure(
                                    stage, status, Category.CONTRACT, Reason.INVALID_CONTENT_TYPE);
                        }
                        if (ct == null || !MediaType.APPLICATION_JSON.isCompatibleWith(ct))
                            throw failure(
                                    stage, status, Category.CONTRACT, Reason.INVALID_CONTENT_TYPE);
                        byte[] bytes;
                        try {
                            bytes = response.getBody().readNBytes(65537);
                        } catch (java.io.IOException | RuntimeException ex) {
                            throw failure(
                                    stage, status, Category.TRANSPORT, Reason.RESPONSE_READ_FAILED);
                        }
                        if (bytes.length > 65536)
                            throw failure(
                                    stage, status, Category.CONTRACT, Reason.RESPONSE_TOO_LARGE);
                        JsonNode root;
                        try {
                            root = json.readTree(bytes);
                        } catch (RuntimeException ex) {
                            throw failure(stage, status, Category.CONTRACT, Reason.INVALID_JSON);
                        }
                        if (root == null || (!root.isObject() && !root.isArray()))
                            throw failure(stage, status, Category.CONTRACT, Reason.INVALID_JSON);
                        if (status != expected) {
                            Reason reason =
                                    status >= 500
                                            ? Reason.HTTP_SERVER_ERROR
                                            : status >= 400
                                                    ? Reason.HTTP_REJECTED
                                                    : Reason.UNEXPECTED_HTTP_STATUS;
                            ProviderCode code = providerCode(root);
                            // Recognized explicit authorization refusals are negative ACKs.
                            // Never extend this to 409, 429, 5xx or unknown/malformed bodies.
                            boolean rejected =
                                    stage == Stage.INITIALIZE
                                            && status == 403
                                            && Set.of(
                                                            ProviderCode.payout_not_enabled,
                                                            ProviderCode.ip_not_whitelisted,
                                                            ProviderCode.merchant_suspended)
                                                    .contains(code);
                            throw new PayoutProviderFailure(
                                    stage,
                                    status,
                                    httpCategory(status),
                                    reason,
                                    code,
                                    rejected ? Submission.REJECTED : stage.submission);
                        }
                        if (root.has("success")) {
                            if (!root.path("success").isBoolean())
                                throw failure(
                                        stage, status, Category.CONTRACT, Reason.INVALID_ENVELOPE);
                            if (!root.path("success").booleanValue())
                                throw new PayoutProviderFailure(
                                        stage,
                                        status,
                                        Category.PROVIDER_REJECTION,
                                        Reason.PROVIDER_REPORTED_FAILURE,
                                        providerCode(root));
                            if (!root.path("data").isObject() && !root.path("data").isArray())
                                throw failure(
                                        stage, status, Category.CONTRACT, Reason.INVALID_ENVELOPE);
                            root = root.path("data");
                        }
                        return root;
                    });
        } catch (PayoutProviderFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw failure(stage, 0, Category.TRANSPORT, Reason.TRANSPORT_UNCERTAIN);
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
