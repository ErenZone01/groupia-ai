package sn.groupia.settlement.infrastructure.provider;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sn.groupia.settlement.domain.model.MerchantPayout;
import tools.jackson.databind.ObjectMapper;

class SaspayMerchantPayoutProviderTests {
    private static final UUID MERCHANT = UUID.fromString("6f1e2b3a-8c4d-4a2f-9e1b-2d3c4f5a6b7c");
    private static final UUID ID = UUID.fromString("c9b67211-150f-4c71-885c-123450000003");
    private static final UUID TX = UUID.fromString("b9b67211-150f-4c71-885c-123450000002");

    private final ObjectMapper json = new ObjectMapper();

    private SaspayMerchantPayoutProvider.Connection settings() {
        return new SaspayMerchantPayoutProvider.Connection(
                "sk_live_synthetic", MERCHANT, true, "wave_sn", "orange_sn");
    }

    private MerchantPayout payout(String transaction) {
        UUID org = UUID.randomUUID();
        return new MerchantPayout(
                ID,
                org,
                UUID.randomUUID(),
                new BigDecimal("1000"),
                "XOF",
                "PENDING",
                UUID.randomUUID(),
                "WAVE",
                "+221771234567",
                "Awa Test",
                "SASPAY",
                MERCHANT.toString(),
                "MERCHANT_PAYOUT:" + org + ":" + UUID.randomUUID(),
                UUID.randomUUID(),
                transaction,
                UUID.randomUUID(),
                Instant.parse("2026-11-11T00:00:00Z"),
                null,
                null,
                true,
                "PAYOUT_PROVIDER_UNCERTAIN",
                0);
    }

    private SaspayMerchantPayoutProvider adapter(boolean enabled, String origin) {
        var http =
                SaspayMerchantPayoutProvider.makeHttp(settings())
                        .mutate()
                        .baseUrl(origin + "/api/v1")
                        .build();
        return new SaspayMerchantPayoutProvider(
                settings(), json, Clock.systemUTC(), enabled, true, true, http);
    }

    @Test
    void disabledByDefaultNoProviderMutation() {
        var p = adapter(false, "http://127.0.0.1:1");
        assertThat(p.enabled()).isFalse();
        assertThatThrownBy(() -> p.initiate(payout(null)))
                .hasMessage("SASPAY_PAYOUT_NOT_AUTHORIZED");
    }

    @Test
    void productionWiringCannotReleaseRealMoneyJustByFlippingEnvironmentFlags() {
        var env =
                new org.springframework.mock.env.MockEnvironment()
                        .withProperty("groupia.payment.saspay.api-key", "sk_live_synthetic")
                        .withProperty("groupia.payment.saspay.merchant-id", MERCHANT.toString())
                        .withProperty("groupia.payment.saspay.enabled", "true")
                        .withProperty("groupia.settlement.saspay-payouts.wave-network", "wave_sn")
                        .withProperty(
                                "groupia.settlement.saspay-payouts.orange-network", "orange_sn")
                        .withProperty("groupia.settlement.saspay-payouts.enabled", "true")
                        .withProperty(
                                "groupia.settlement.saspay-payouts.marketplace-authorized", "true")
                        .withProperty(
                                "groupia.settlement.saspay-payouts.beneficiary-proof-approved",
                                "true")
                        .withProperty(
                                "groupia.settlement.saspay-payouts.company-fee-budget-approved",
                                "true");
        var provider = new SaspayMerchantPayoutProvider(json, Clock.systemUTC(), env);
        assertThat(provider.enabled()).isFalse();
        assertThatThrownBy(() -> provider.initiate(payout(null)))
                .hasMessage("SASPAY_PAYOUT_NOT_AUTHORIZED");
    }

    private MerchantPayout directPilotPayout(
            UUID org, UUID order, String method, BigDecimal amount) {
        var p = payout(null);
        return new MerchantPayout(
                p.id(),
                org,
                null,
                amount,
                p.currency(),
                p.status(),
                p.destinationId(),
                method,
                p.phoneE164(),
                p.holderName(),
                p.provider(),
                p.providerAccountReference(),
                p.businessReference(),
                p.providerIdempotencyKey(),
                p.providerTransactionId(),
                p.requestedBy(),
                p.requestedAt(),
                p.providerOccurredAt(),
                p.succeededAt(),
                p.reconciliationRequired(),
                p.reconciliationReason(),
                p.version(),
                order);
    }

    @Test
    void livePilotCanOnlyTargetOneOrderInOneShopViaWaveWithoutTransferring() {
        var shopId = UUID.randomUUID();
        var order = UUID.randomUUID();
        var env =
                new org.springframework.mock.env.MockEnvironment()
                        .withProperty("groupia.payment.saspay.api-key", "sk_live_synthetic")
                        .withProperty("groupia.payment.saspay.merchant-id", MERCHANT.toString())
                        .withProperty("groupia.payment.saspay.enabled", "true")
                        .withProperty("groupia.settlement.saspay-payouts.enabled", "true")
                        .withProperty(
                                "groupia.settlement.saspay-payouts.marketplace-authorized", "true")
                        .withProperty(
                                "groupia.settlement.saspay-payouts.beneficiary-proof-approved",
                                "true")
                        .withProperty(
                                "groupia.settlement.saspay-payout-pilot.organization-id",
                                shopId.toString())
                        .withProperty(
                                "groupia.settlement.saspay-payout-live-pilot.direct-order-id",
                                order.toString())
                        .withProperty(
                                "groupia.settlement.saspay-payout-live-pilot.enabled", "true");

        var provider = new SaspayMerchantPayoutProvider(json, Clock.systemUTC(), env);
        assertThat(provider.enabled()).isTrue();
        provider.assertLivePilotTarget(
                directPilotPayout(shopId, order, "WAVE", new BigDecimal("1000")));
        for (var invalid :
                List.of(
                        directPilotPayout(UUID.randomUUID(), order, "WAVE", new BigDecimal("1000")),
                        directPilotPayout(
                                shopId, UUID.randomUUID(), "WAVE", new BigDecimal("1000")),
                        directPilotPayout(shopId, order, "ORANGE_MONEY", new BigDecimal("1000")),
                        directPilotPayout(shopId, order, "WAVE", new BigDecimal("1001")),
                        directPilotPayout(shopId, order, "WAVE", new BigDecimal("1000.01")),
                        payout(null))) {
            assertThatThrownBy(() -> provider.assertLivePilotTarget(invalid))
                    .hasMessage("SASPAY_PAYOUT_LIVE_PILOT_TARGET_MISMATCH");
        }
        // Removing the irreversible release never enables the real transport.
        env.withProperty("groupia.settlement.saspay-payout-live-pilot.enabled", "false");
        assertThat(new SaspayMerchantPayoutProvider(json, Clock.systemUTC(), env).enabled())
                .isFalse();

        // A malformed direct-order UUID also fails closed.
        env.withProperty("groupia.settlement.saspay-payout-live-pilot.enabled", "true");
        env.withProperty("groupia.settlement.saspay-payout-live-pilot.direct-order-id", "invalid");
        assertThat(new SaspayMerchantPayoutProvider(json, Clock.systemUTC(), env).enabled())
                .isFalse();
    }

    @Test
    void payoutPreflightRejectsUnapprovedFeesAndUnverifiedWallets() throws Exception {
        var p = payout(null);
        // The accepted seller quote uses DEDUCTED, with 2% of 1000 = 20 XOF.
        var rates = json.readTree(payoutRates("DEDUCTED"));
        var fee = SaspayPayoutPreflight.checkRates(rates, p, "wave_sn", new BigDecimal("20"));
        assertThat(fee).isEqualByComparingTo("20");
        // ADD_ON in my-rates is the merchant default; initialize overrides it.
        var addOnDefault = json.readTree(payoutRates("ADD_ON"));
        assertThat(SaspayPayoutPreflight.checkRates(addOnDefault, p, "wave_sn", fee))
                .isEqualByComparingTo("20");
        assertThatThrownBy(
                        () ->
                                SaspayPayoutPreflight.quoteRates(
                                        json.readTree(payoutRates("UNKNOWN")),
                                        p.amount(),
                                        "wave_sn"))
                .hasMessage("SASPAY_PAYOUT_FEE_MODE_UNKNOWN");
        assertThatThrownBy(
                        () ->
                                SaspayPayoutPreflight.checkRates(
                                        rates, p, "wave_sn", new BigDecimal("19")))
                .hasMessage("SASPAY_PAYOUT_QUOTE_CHANGED");
        assertThatThrownBy(
                        () ->
                                SaspayPayoutPreflight.checkRates(
                                        rates, p, "orange_sn", new BigDecimal("20")))
                .hasMessage("SASPAY_PAYOUT_NETWORK_UNAVAILABLE");
        SaspayPayoutPreflight.checkWallet(
                json.readTree(walletBalance("1020.00", false)), p, fee, MERCHANT.toString());
        assertThatThrownBy(
                        () ->
                                SaspayPayoutPreflight.checkWallet(
                                        json.readTree(walletBalance("999.00", false)),
                                        p,
                                        fee,
                                        MERCHANT.toString()))
                .hasMessage("SASPAY_PAYOUT_WALLET_INSUFFICIENT");
        assertThatThrownBy(
                        () ->
                                SaspayPayoutPreflight.checkWallet(
                                        json.readTree(walletBalance("1500.00", true)),
                                        p,
                                        fee,
                                        MERCHANT.toString()))
                .hasMessage("SASPAY_PAYOUT_WALLET_FROZEN");
        assertThatThrownBy(
                        () ->
                                SaspayPayoutPreflight.checkWallet(
                                        json.readTree(walletBalance("1500.00", false)),
                                        p,
                                        fee,
                                        UUID.randomUUID().toString()))
                .hasMessage("SASPAY_PAYOUT_WALLET_UNVERIFIED");
    }

    @Test
    void payoutPreflightPricesObservedAddOnDefaultAtThreePointSevenPercent() throws Exception {
        var payout = payout(null);
        var rates = json.readTree(payoutRates("ADD_ON").replace("2.000", "3.700"));
        var fee = SaspayPayoutPreflight.quoteRates(rates, payout.amount(), "wave_sn");
        assertThat(fee).isEqualByComparingTo("37.00");
        assertThat(payout.amount().subtract(fee)).isEqualByComparingTo("963.00");
        assertThatThrownBy(
                        () ->
                                SaspayPayoutPreflight.checkRates(
                                        rates, payout, "wave_sn", new BigDecimal("36.00")))
                .hasMessage("SASPAY_PAYOUT_QUOTE_CHANGED");
    }

    @Test
    void initializedPayoutOverridesAddOnTariffAndPreservesIdempotency() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        var original = payout(null);
        server.createContext(
                "/api/v1/pricing/my-rates/",
                exchange -> {
                    assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                    respond(exchange, payoutRates("ADD_ON"));
                });
        server.createContext(
                "/api/v1/merchant-balances/",
                exchange -> {
                    assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                    respond(exchange, walletBalance("1500.00", false));
                });
        server.createContext(
                "/api/v1/payouts/initialize/",
                exchange -> {
                    requests.incrementAndGet();
                    var body = json.readTree(exchange.getRequestBody().readAllBytes());
                    assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                    assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key"))
                            .isEqualTo(original.providerIdempotencyKey().toString());
                    assertThat(body.path("amount").asText()).isEqualTo("1000.00");
                    assertThat(body.path("currency").asText()).isEqualTo("XOF");
                    assertThat(body.path("country").asText()).isEqualTo("SN");
                    assertThat(body.path("method").asText()).isEqualTo("wave_sn");
                    assertThat(body.path("fee_charge_mode").asText()).isEqualTo("DEDUCTED");
                    assertThat(body.path("recipient").path("msisdn").asText())
                            .isEqualTo("771234567");
                    assertThat(body.path("metadata").path("groupia_payout_id").asText())
                            .isEqualTo(ID.toString());
                    byte[] response = ("{\"id\":\"" + TX + "\"}").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(201, response.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                });
        server.start();
        try {
            var provider = adapter(true, "http://127.0.0.1:" + server.getAddress().getPort());
            assertThat(provider.initiate(original)).isEqualTo(TX.toString());
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void verifiesExistingPayoutUsingTwoIndependentGetsWithoutAnotherTransfer() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var reads = new AtomicInteger();
        var details = new AtomicInteger();
        server.createContext(
                "/api/v1/payouts/" + TX + "/verify/",
                exchange -> {
                    reads.incrementAndGet();
                    assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                    respond(exchange, verified(null, null));
                });
        server.createContext(
                "/api/v1/transactions/" + TX + "/",
                exchange -> {
                    details.incrementAndGet();
                    assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                    respond(exchange, transactionDetail("771234567", "wave_sn"));
                });
        server.start();
        try {
            var provider = adapter(true, "http://127.0.0.1:" + server.getAddress().getPort());
            var proof = provider.verify(payout(TX.toString()));
            assertThat(proof.status()).isEqualTo("SUCCEEDED");
            assertThat(proof.phoneE164()).isEqualTo("+221771234567");
            assertThat(reads.get()).isEqualTo(1);
            assertThat(details.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pendingAndFailureRequireOnlyGatewayVerificationGet() throws Exception {
        for (String status : List.of("PENDING", "FAILED", "CANCELLED")) {
            var source =
                    verified(null, null)
                            .replace("\"status\":\"SUCCESS\"", "\"status\":\"" + status + "\"");
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var details = new AtomicInteger();
            server.createContext(
                    "/api/v1/payouts/" + TX + "/verify/", exchange -> respond(exchange, source));
            server.createContext(
                    "/api/v1/transactions/" + TX + "/",
                    exchange -> {
                        details.incrementAndGet();
                        respond(exchange, transactionDetail("771234567", "wave_sn"));
                    });
            server.start();
            try {
                var provider = adapter(true, "http://127.0.0.1:" + server.getAddress().getPort());
                var proof = provider.verify(payout(TX.toString()));
                assertThat(proof.status()).isEqualTo(status);
                assertThat(proof.phoneE164()).isNull();
                assertThat(details.get()).isZero();
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void successfulGatewayStatusCannotOverrideMissingOrContradictoryTransactionEvidence()
            throws Exception {
        assertInvalidDualGet(
                verified(null, null),
                transactionDetail(null, "wave_sn"),
                "SASPAY_PAYOUT_RECIPIENT_EVIDENCE_MISSING");
        assertInvalidDualGet(
                verified(null, null),
                transactionDetail("771234568", "wave_sn"),
                "SASPAY_PAYOUT_RECIPIENT_MISMATCH");
        assertInvalidDualGet(
                verified(null, null),
                transactionDetail("771234567", "orange_sn"),
                "SASPAY_PAYOUT_NETWORK_EVIDENCE_MISSING");
        assertInvalidDualGet(
                verified(null, null),
                transactionDetail("771234567", "wave_sn")
                        .replace("\"status\":\"SUCCESS\"", "\"status\":\"PENDING\""),
                "SASPAY_PAYOUT_STATUS_CONFLICT");
        assertInvalidDualGet(
                verified(null, null),
                transactionDetail("771234567", "wave_sn")
                        .replace("\"client_fee\":\"52.00\"", "\"client_fee\":\"53.00\"")
                        .replace("\"net_amount\":\"948.00\"", "\"net_amount\":\"947.00\""),
                "SASPAY_PAYOUT_FINANCIAL_MISMATCH");
        assertInvalidDualGet(
                verified(null, null),
                transactionDetail("771234567", "wave_sn")
                        .replace(MERCHANT.toString(), UUID.randomUUID().toString()),
                "SASPAY_PAYOUT_EVIDENCE_MISMATCH");
    }

    private void assertInvalidDualGet(String verification, String transaction, String error)
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/payouts/" + TX + "/verify/", exchange -> respond(exchange, verification));
        server.createContext(
                "/api/v1/transactions/" + TX + "/", exchange -> respond(exchange, transaction));
        server.start();
        try {
            var provider = adapter(true, "http://127.0.0.1:" + server.getAddress().getPort());
            assertThatThrownBy(() -> provider.verify(payout(TX.toString()))).hasMessage(error);
        } finally {
            server.stop(0);
        }
    }

    private String payoutRates(String mode) {
        return "[{\"country_code\":\"SN\",\"currency\":\"XOF\","
                + "\"network_code\":\"wave_sn\",\"payout\":{"
                + "\"available\":true,\"otp_required\":false,"
                + "\"tiers\":[{\"min_amount\":\"0.00\","
                + "\"max_amount\":\"500000.00\",\"currency\":\"XOF\","
                + "\"fee_charge_mode\":\""
                + mode
                + "\","
                + "\"percent\":\"2.000\",\"fixed\":\"0.00\","
                + "\"floor_amount\":null,\"cap_amount\":null}]}}]";
    }

    private String walletBalance(String available, boolean frozen) {
        return "{\"count\":1,\"next\":null,\"results\":[{"
                + "\"merchant\":\""
                + MERCHANT
                + "\","
                + "\"country\":\"SN\",\"currency\":\"XOF\","
                + "\"available_amount\":\""
                + available
                + "\","
                + "\"is_frozen\":"
                + frozen
                + "}]}";
    }

    @Test
    void payoutPreflightRefusesUnsupportedRateOrInsufficientWalletBeforePost() throws Exception {
        for (String mode : List.of("DEDUCTED", "ADD_ON", "UNKNOWN")) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var posts = new AtomicInteger();
            server.createContext(
                    "/api/v1/pricing/my-rates/", exchange -> respond(exchange, payoutRates(mode)));
            server.createContext(
                    "/api/v1/merchant-balances/",
                    exchange -> respond(exchange, walletBalance("999.00", false)));
            server.createContext(
                    "/api/v1/payouts/initialize/",
                    exchange -> {
                        posts.incrementAndGet();
                        respond(exchange, "{\"id\":\"" + TX + "\"}");
                    });
            server.start();
            try {
                var provider = adapter(true, "http://127.0.0.1:" + server.getAddress().getPort());
                String expected =
                        "UNKNOWN".equals(mode)
                                ? "SASPAY_PAYOUT_FEE_MODE_UNKNOWN"
                                : "SASPAY_PAYOUT_WALLET_INSUFFICIENT";
                assertThatThrownBy(() -> provider.initiate(payout(null))).hasMessage(expected);
                assertThat(posts.get()).isZero();
            } finally {
                server.stop(0);
            }
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String payload)
            throws java.io.IOException {
        byte[] response =
                ("{\"success\":true,\"data\":" + payload + "}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private String transactionDetail(String phone, String network) {
        String payload = verified(null, network);
        return phone == null
                ? payload
                : payload.replace(
                        "\"status\":\"SUCCESS\"",
                        "\"status\":\"SUCCESS\",\"operation_msisdn\":\"" + phone + "\"");
    }

    @Test
    void missingIndependentRecipientProofNeverBecomesSuccessfulPayout() throws Exception {
        var p = payout(TX.toString());
        var provider = adapter(true, "http://127.0.0.1:1");
        var e = json.readTree(verified(null, null));
        assertThatThrownBy(() -> provider.validateProof(p, e, Instant.now()))
                .hasMessage("SASPAY_PAYOUT_RECIPIENT_EVIDENCE_MISSING");
    }

    @Test
    void pendingFailedAndCancelledCanBeReconciledWithoutRecipientEvidence() throws Exception {
        var p = payout(TX.toString());
        var provider = adapter(true, "http://127.0.0.1:1");
        for (String status : List.of("PENDING", "FAILED", "CANCELLED")) {
            var payload =
                    verified(null, null)
                            .replace("\"status\":\"SUCCESS\"", "\"status\":\"" + status + "\"");
            var proof = provider.validateProof(p, json.readTree(payload), Instant.now());
            assertThat(proof.status()).isEqualTo(status);
            assertThat(proof.phoneE164()).isNull();
            assertThat(proof.transactionId()).isEqualTo(TX.toString());
            assertThat(proof.providerFee()).isEqualByComparingTo("52");
        }
    }

    @Test
    void anySuppliedPayoutDestinationEvidenceMustStillMatchOnNonSuccess() throws Exception {
        var p = payout(TX.toString());
        var provider = adapter(true, "http://127.0.0.1:1");
        var wrongPhone =
                verified("771234568", "wave_sn")
                        .replace("\"status\":\"SUCCESS\"", "\"status\":\"FAILED\"");
        assertThatThrownBy(
                        () -> provider.validateProof(p, json.readTree(wrongPhone), Instant.now()))
                .hasMessage("SASPAY_PAYOUT_RECIPIENT_MISMATCH");
        var wrongNetwork =
                verified(null, "orange_sn")
                        .replace("\"status\":\"SUCCESS\"", "\"status\":\"PENDING\"");
        assertThatThrownBy(
                        () -> provider.validateProof(p, json.readTree(wrongNetwork), Instant.now()))
                .hasMessage("SASPAY_PAYOUT_NETWORK_EVIDENCE_MISSING");
    }

    @Test
    void verifiedRecipientAndCompanyFundedFeesMustMatchExactly() throws Exception {
        var p = payout(TX.toString());
        var provider = adapter(true, "http://127.0.0.1:1");
        var proof =
                provider.validateProof(
                        p, json.readTree(verified("771234567", "wave_sn")), Instant.now());
        assertThat(proof.authenticated()).isTrue();
        assertThat(proof.status()).isEqualTo("SUCCEEDED");
        assertThat(proof.transactionId()).isEqualTo(TX.toString());
        assertThat(proof.phoneE164()).isEqualTo(p.phoneE164());
        assertThat(proof.amount()).isEqualByComparingTo("1000");
        assertThat(proof.providerFee()).isEqualByComparingTo("52");
    }

    @Test
    void recipientProofAcceptsNationalCountryPrefixedAndE164Formats() throws Exception {
        var p = payout(TX.toString());
        var provider = adapter(true, "http://127.0.0.1:1");
        for (String phone : List.of("771234567", "221771234567", "+221771234567")) {
            var proof =
                    provider.validateProof(
                            p, json.readTree(verified(phone, "wave_sn")), Instant.now());
            assertThat(proof.authenticated()).isTrue();
            assertThat(proof.phoneE164()).isEqualTo(p.phoneE164());
        }
    }

    @Test
    void malformedOrDifferentRecipientProofNeverSettlesMerchantPayout() throws Exception {
        var p = payout(TX.toString());
        var provider = adapter(true, "http://127.0.0.1:1");
        for (String phone :
                List.of(
                        "77123456",
                        "22177123456",
                        "+220771234567",
                        "00221771234567",
                        "77 1234567",
                        "+2217712345670")) {
            assertThatThrownBy(
                            () ->
                                    provider.validateProof(
                                            p,
                                            json.readTree(verified(phone, "wave_sn")),
                                            Instant.now()))
                    .hasMessage("SASPAY_PAYOUT_PHONE_INVALID");
        }
        for (String phone : List.of("771234568", "221771234568", "+221771234568")) {
            assertThatThrownBy(
                            () ->
                                    provider.validateProof(
                                            p,
                                            json.readTree(verified(phone, "wave_sn")),
                                            Instant.now()))
                    .hasMessage("SASPAY_PAYOUT_RECIPIENT_MISMATCH");
        }
    }

    @Test
    void compactUuidResponsesAreNormalizedWithoutWeakeningIdentifierValidation() throws Exception {
        var p = payout(TX.toString());
        var provider = adapter(true, "http://127.0.0.1:1");
        String compactId = TX.toString().replace("-", "");
        String compactMerchant = MERCHANT.toString().replace("-", "");
        String source =
                verified("771234567", "wave_sn")
                        .replace(TX.toString(), compactId)
                        .replace(MERCHANT.toString(), compactMerchant);
        assertThat(provider.validateProof(p, json.readTree(source), Instant.now()).transactionId())
                .isEqualTo(TX.toString());
        String corrupted = source.replace(compactId, "not-a-uuid");
        assertThatThrownBy(() -> provider.validateProof(p, json.readTree(corrupted), Instant.now()))
                .hasMessage("SASPAY_PAYOUT_IDENTIFIER_INVALID");
    }

    @Test
    void wrongMerchantAndNetAreRejectedBeforeSettlement() throws Exception {
        var p = payout(TX.toString());
        var provider = adapter(true, "http://127.0.0.1:1");
        String valid = verified("771234567", "wave_sn");
        assertThatThrownBy(
                        () ->
                                provider.validateProof(
                                        p,
                                        json.readTree(
                                                valid.replace(
                                                        MERCHANT.toString(),
                                                        UUID.randomUUID().toString())),
                                        Instant.now()))
                .hasMessage("SASPAY_PAYOUT_EVIDENCE_MISMATCH");
        assertThatThrownBy(
                        () ->
                                provider.validateProof(
                                        p,
                                        json.readTree(
                                                valid.replace(
                                                        "\"net_amount\":\"948.00\"",
                                                        "\"net_amount\":\"949.00\"")),
                                        Instant.now()))
                .hasMessage("SASPAY_PAYOUT_FINANCIAL_MISMATCH");
    }

    private String verified(String phone, String network) {
        String recipient = phone == null ? "" : ",\"recipient_msisdn\":\"" + phone + "\"";
        String gateway = network == null ? "" : ",\"network_code\":\"" + network + "\"";
        return "{\"id\":\""
                + TX
                + "\",\"merchant\":\""
                + MERCHANT
                + "\",\"transaction_type\":\"RETRAIT\",\"flow_direction\":\"OUTBOUND\","
                + "\"currency\":\"XOF\",\"fee_charge_mode\":\"DEDUCTED\","
                + "\"requested_amount\":\"1000.00\",\"client_fee\":\"52.00\","
                + "\"debited_amount\":\"1000.00\",\"net_amount\":\"948.00\","
                + "\"status\":\"SUCCESS\""
                + recipient
                + gateway
                + "}";
    }
}
