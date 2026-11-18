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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sn.groupia.settlement.domain.model.MerchantPayout;
import sn.groupia.settlement.domain.service.PayoutProviderFailure;
import sn.groupia.settlement.domain.service.PayoutProviderFailure.*;
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
    void closedNonExecutedIntentIsRejectedBeforeAnyProviderRequest() {
        var p = payout(null);
        var closed =
                new MerchantPayout(
                        p.id(),
                        p.organizationId(),
                        p.campaignId(),
                        p.amount(),
                        p.currency(),
                        "NON_EXECUTE",
                        p.destinationId(),
                        p.method(),
                        p.phoneE164(),
                        p.holderName(),
                        p.provider(),
                        p.providerAccountReference(),
                        p.businessReference(),
                        p.providerIdempotencyKey(),
                        null,
                        p.requestedBy(),
                        p.requestedAt(),
                        null,
                        null,
                        false,
                        null,
                        p.version() + 1);
        assertThatThrownBy(() -> adapter(true, "http://127.0.0.1:1").initiate(closed))
                .isInstanceOfSatisfying(
                        PayoutProviderFailure.class,
                        failure -> {
                            assertThat(failure.getMessage())
                                    .isEqualTo("SASPAY_PAYOUT_INTENT_INVALID");
                            assertThat(failure.submission()).isEqualTo(Submission.NOT_ATTEMPTED);
                        });
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
        var closed = new SaspayMerchantPayoutProvider(json, Clock.systemUTC(), env);
        assertThat(closed.enabled()).isFalse();
        assertThat(closed.administrativeApprovalEligible()).isTrue();
        assertThatThrownBy(
                        () ->
                                closed.initiate(
                                        directPilotPayout(
                                                shopId, order, "WAVE", new BigDecimal("1000"))))
                .hasMessage("SASPAY_PAYOUT_NOT_AUTHORIZED");
        for (String permission :
                List.of(
                        "groupia.payment.saspay.enabled",
                        "groupia.settlement.saspay-payouts.marketplace-authorized",
                        "groupia.settlement.saspay-payouts.beneficiary-proof-approved")) {
            env.withProperty(permission, "false");
            assertThat(
                            new SaspayMerchantPayoutProvider(json, Clock.systemUTC(), env)
                                    .administrativeApprovalEligible())
                    .isFalse();
            env.withProperty(permission, "true");
        }

        // A malformed direct-order UUID also fails closed.
        env.withProperty("groupia.settlement.saspay-payout-live-pilot.enabled", "true");
        env.withProperty("groupia.settlement.saspay-payout-live-pilot.direct-order-id", "invalid");
        assertThat(
                        new SaspayMerchantPayoutProvider(json, Clock.systemUTC(), env)
                                .administrativeApprovalEligible())
                .isFalse();
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

    private static String paginatedWallet(int page, boolean duplicate) {
        var rows = new ArrayList<String>();
        for (int index = 0; index < (page == 1 ? 20 : 7); index++) {
            boolean target = (page == 1 && index == 0) || (duplicate && page == 2 && index == 0);
            UUID merchant =
                    target
                            ? MERCHANT
                            : UUID.nameUUIDFromBytes(
                                    ("other-wallet-" + page + "-" + index)
                                            .getBytes(StandardCharsets.UTF_8));
            rows.add(
                    "{\"merchant\":\""
                            + merchant
                            + "\",\"country\":\"SN\",\"currency\":\"XOF\","
                            + "\"available_amount\":\"1000.00\",\"is_frozen\":false}");
        }
        return "{\"count\":27,\"next\":"
                + (page == 1 ? "\"?page=2\"" : "null")
                + ",\"results\":["
                + String.join(",", rows)
                + "]}";
    }

    @Test
    void paginatedWalletPreflightRequiresAll27RowsAndRejectsDuplicates() throws Exception {
        var first = json.readTree(paginatedWallet(1, false));
        var last = json.readTree(paginatedWallet(2, false));
        var p = payout(null);
        SaspayPayoutPreflight.checkWallet(
                List.of(first, last), p, new BigDecimal("37"), MERCHANT.toString());
        assertThatThrownBy(
                        () ->
                                SaspayPayoutPreflight.checkWallet(
                                        first, p, new BigDecimal("37"), MERCHANT.toString()))
                .hasMessage("SASPAY_PAYOUT_WALLET_UNVERIFIED");
        assertThatThrownBy(
                        () ->
                                SaspayPayoutPreflight.checkWallet(
                                        List.of(first, json.readTree(paginatedWallet(2, true))),
                                        p,
                                        new BigDecimal("37"),
                                        MERCHANT.toString()))
                .hasMessage("SASPAY_PAYOUT_WALLET_UNVERIFIED");
        assertThatThrownBy(
                        () ->
                                SaspayPayoutPreflight.checkWallet(
                                        List.of(
                                                first,
                                                json.readTree(
                                                        paginatedWallet(2, false)
                                                                .replace(
                                                                        "\"count\":27",
                                                                        "\"count\":28"))),
                                        p,
                                        new BigDecimal("37"),
                                        MERCHANT.toString()))
                .hasMessage("SASPAY_PAYOUT_WALLET_UNVERIFIED");
    }

    @Test
    void paginatedWalletIsReadOnlyUntilAllPagesValidated() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var walletGets = new AtomicInteger();
        var posts = new AtomicInteger();
        var input = payout(null);
        server.createContext(
                "/api/v1/pricing/my-rates/", exchange -> respond(exchange, payoutRates("ADD_ON")));
        server.createContext(
                "/api/v1/merchant-balances/",
                exchange -> {
                    assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                    walletGets.incrementAndGet();
                    int page = "page=2".equals(exchange.getRequestURI().getRawQuery()) ? 2 : 1;
                    respond(exchange, paginatedWallet(page, false));
                });
        server.createContext(
                "/api/v1/payouts/initialize/",
                exchange -> {
                    posts.incrementAndGet();
                    assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                    byte[] body = ("{\"id\":\"" + TX + "\"}").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(201, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            var provider = adapter(true, "http://127.0.0.1:" + server.getAddress().getPort());
            assertThat(provider.initiate(input)).isEqualTo(TX.toString());
            assertThat(walletGets).hasValue(2);
            assertThat(posts).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void duplicateWalletOnPageTwoBlocksAnyProviderPost() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var posts = new AtomicInteger();
        server.createContext(
                "/api/v1/pricing/my-rates/",
                exchange -> respond(exchange, payoutRates("DEDUCTED")));
        server.createContext(
                "/api/v1/merchant-balances/",
                exchange -> {
                    int page = "page=2".equals(exchange.getRequestURI().getRawQuery()) ? 2 : 1;
                    respond(exchange, paginatedWallet(page, true));
                });
        server.createContext(
                "/api/v1/payouts/initialize/",
                exchange -> {
                    posts.incrementAndGet();
                    respond(exchange, "{\"id\":\"" + TX + "\"}");
                });
        server.start();
        try {
            var provider = adapter(true, "http://127.0.0.1:" + server.getAddress().getPort());
            assertThatThrownBy(() -> provider.initiate(payout(null)))
                    .hasMessage("SASPAY_PAYOUT_WALLET_UNVERIFIED");
            assertThat(posts).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void paginationRejectsCrossOriginAndMalformedNextUrls() {
        assertThat(
                        SaspayMerchantPayoutProvider.safeWalletNext(
                                "https://api.saspay.me/api/v1/merchant-balances/?page=2"))
                .isEqualTo("/merchant-balances/?page=2");
        assertThat(SaspayMerchantPayoutProvider.safeWalletNext("?page=2"))
                .isEqualTo("/merchant-balances/?page=2");
        for (var url :
                List.of(
                        "https://attacker.example/api/v1/merchant-balances/?page=2",
                        "http://api.saspay.me/api/v1/merchant-balances/?page=2",
                        "https://api.saspay.me/other/?page=2",
                        "//attacker.example/api/v1/merchant-balances/?page=2",
                        "https://api.saspay.me/api/v1/merchant-balances/?page=2#bad",
                        "?page=2&redirect=https://attacker.example")) {
            assertThatThrownBy(() -> SaspayMerchantPayoutProvider.safeWalletNext(url))
                    .hasMessage("SASPAY_PAYOUT_WALLET_UNVERIFIED");
        }
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
                    assertThat(body.path("customer").path("phone").asText())
                            .isEqualTo(original.phoneE164());
                    assertThat(body.path("metadata").path("groupia_reference").asText())
                            .isEqualTo(original.businessReference());
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
    void liveTransferPayoutIsReconciledByTwoGetsWithoutDispatch() throws Exception {
        var source =
                verified(null, null)
                        .replace(
                                "\"transaction_type\":\"RETRAIT\"",
                                "\"transaction_type\":\"TRANSFERT\"");
        var detail =
                transactionDetail("771234567", "wave_sn")
                        .replace(
                                "\"transaction_type\":\"RETRAIT\"",
                                "\"transaction_type\":\"TRANSFERT\"");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var gets = new AtomicInteger();
        server.createContext(
                "/api/v1/payouts/" + TX + "/verify/",
                e -> {
                    assertThat(e.getRequestMethod()).isEqualTo("GET");
                    gets.incrementAndGet();
                    respond(e, source);
                });
        server.createContext(
                "/api/v1/transactions/" + TX + "/",
                e -> {
                    assertThat(e.getRequestMethod()).isEqualTo("GET");
                    gets.incrementAndGet();
                    respond(e, detail);
                });
        server.start();
        try {
            var proof =
                    adapter(true, "http://127.0.0.1:" + server.getAddress().getPort())
                            .verify(payout(TX.toString()));
            assertThat(proof.status()).isEqualTo("SUCCEEDED");
            assertThat(proof.phoneE164()).isEqualTo("+221771234567");
            assertThat(proof.providerFee()).isEqualByComparingTo("52");
            assertThat(gets.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classificationMismatchBetweenTwoGetsCannotConfirmPayout() throws Exception {
        assertInvalidDualGet(
                verified(null, null)
                        .replace(
                                "\"transaction_type\":\"RETRAIT\"",
                                "\"transaction_type\":\"TRANSFERT\""),
                transactionDetail("771234567", "wave_sn"),
                "SASPAY_PAYOUT_EVIDENCE_MISMATCH");
    }

    @Test
    void incomingAndUnrelatedTransactionTypesRemainIneligibleForPayout() throws Exception {
        var p = payout(TX.toString());
        var provider = adapter(true, "http://127.0.0.1:1");
        for (String type : List.of("PAIEMENT", "RECHARGE", "WALLET_TRANSFER")) {
            String source =
                    verified("771234567", "wave_sn")
                            .replace(
                                    "\"transaction_type\":\"RETRAIT\"",
                                    "\"transaction_type\":\"" + type + "\"");
            assertThatThrownBy(
                            () -> provider.validateProof(p, json.readTree(source), Instant.now()))
                    .hasMessage("SASPAY_PAYOUT_EVIDENCE_MISMATCH");
        }
        String incoming =
                verified("771234567", "wave_sn")
                        .replace(
                                "\"flow_direction\":\"OUTBOUND\"",
                                "\"flow_direction\":\"INBOUND\"");
        assertThatThrownBy(() -> provider.validateProof(p, json.readTree(incoming), Instant.now()))
                .hasMessage("SASPAY_PAYOUT_EVIDENCE_MISMATCH");
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void oldIntentRemainsVerifiableAfterRetargetOrDispatchShutdown(boolean dispatchEnabled) {
        var old = payout(TX.toString());
        var env =
                new org.springframework.mock.env.MockEnvironment()
                        .withProperty("groupia.payment.saspay.api-key", "sk_live_synthetic")
                        .withProperty("groupia.payment.saspay.merchant-id", MERCHANT.toString())
                        .withProperty("groupia.payment.saspay.enabled", "true")
                        .withProperty(
                                "groupia.settlement.saspay-payouts.enabled",
                                Boolean.toString(dispatchEnabled))
                        .withProperty(
                                "groupia.settlement.saspay-payouts.marketplace-authorized", "true")
                        .withProperty(
                                "groupia.settlement.saspay-payouts.beneficiary-proof-approved",
                                "true")
                        .withProperty("groupia.settlement.saspay-payouts.wave-network", "wave_sn")
                        .withProperty(
                                "groupia.settlement.saspay-payout-pilot.organization-id",
                                old.organizationId().toString())
                        .withProperty(
                                "groupia.settlement.saspay-payout-live-pilot.direct-order-id",
                                UUID.randomUUID().toString())
                        .withProperty(
                                "groupia.settlement.saspay-payout-live-pilot.enabled",
                                Boolean.toString(dispatchEnabled));
        var provider = new SaspayMerchantPayoutProvider(json, Clock.systemUTC(), env);
        var builder =
                org.springframework.web.client.RestClient.builder()
                        .baseUrl("http://localhost/api/v1");
        var server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        org.springframework.test.util.ReflectionTestUtils.setField(
                provider, "http", builder.build());
        server.expect(
                        org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                                "http://localhost/api/v1/payouts/" + TX + "/verify/"))
                .andExpect(
                        org.springframework.test.web.client.match.MockRestRequestMatchers.method(
                                org.springframework.http.HttpMethod.GET))
                .andRespond(
                        org.springframework.test.web.client.response.MockRestResponseCreators
                                .withSuccess(
                                        verified(null, null),
                                        org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(
                        org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                                "http://localhost/api/v1/transactions/" + TX + "/"))
                .andExpect(
                        org.springframework.test.web.client.match.MockRestRequestMatchers.method(
                                org.springframework.http.HttpMethod.GET))
                .andRespond(
                        org.springframework.test.web.client.response.MockRestResponseCreators
                                .withSuccess(
                                        transactionDetail("771234567", "wave_sn"),
                                        org.springframework.http.MediaType.APPLICATION_JSON));
        assertThat(provider.enabled()).isEqualTo(dispatchEnabled);
        assertThat(provider.verificationEnabled()).isTrue();
        assertThat(provider.verify(old).status()).isEqualTo("SUCCEEDED");
        assertThatThrownBy(() -> provider.initiate(old))
                .isInstanceOfSatisfying(
                        PayoutProviderFailure.class,
                        failure ->
                                assertThat(failure.submission())
                                        .isEqualTo(Submission.NOT_ATTEMPTED));
        assertThatThrownBy(() -> provider.verify(payout(TX.toString())))
                .hasMessage("SASPAY_PAYOUT_LIVE_PILOT_TARGET_MISMATCH");
        server.verify();
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

    @ParameterizedTest
    @ValueSource(strings = {"payout_not_enabled", "ip_not_whitelisted", "merchant_suspended"})
    void explicit403AuthorizationRefusalIsTerminalNotAnUnknownTimeout(String code)
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var posts = new AtomicInteger();
        server.createContext("/api/v1/pricing/my-rates/", e -> respond(e, payoutRates("ADD_ON")));
        server.createContext(
                "/api/v1/merchant-balances/", e -> respond(e, walletBalance("1000.00", false)));
        server.createContext(
                "/api/v1/payouts/initialize/",
                e -> {
                    posts.incrementAndGet();
                    raw(e, 403, "application/json", "{\"error\":{\"code\":\"" + code + "\"}}");
                });
        server.start();
        try {
            var provider = adapter(true, "http://127.0.0.1:" + server.getAddress().getPort());
            assertThatThrownBy(() -> provider.initiate(payout(null)))
                    .isInstanceOfSatisfying(
                            PayoutProviderFailure.class,
                            f -> {
                                assertThat(f.stage()).isEqualTo(Stage.INITIALIZE);
                                assertThat(f.httpStatus()).isEqualTo(403);
                                assertThat(f.providerCode().name()).isEqualTo(code);
                                assertThat(f.submission()).isEqualTo(Submission.REJECTED);
                            });
            assertThat(posts).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void standardModeCanHandleMultipleOrdersWithoutPilotCapWhileGatesRemainMandatory() {
        var env =
                new org.springframework.mock.env.MockEnvironment()
                        .withProperty("groupia.payment.saspay.api-key", "sk_live_synthetic")
                        .withProperty("groupia.payment.saspay.merchant-id", MERCHANT.toString())
                        .withProperty("groupia.payment.saspay.enabled", "true")
                        .withProperty("groupia.settlement.saspay-payouts.enabled", "true")
                        .withProperty("groupia.settlement.saspay-payout-general.enabled", "true")
                        .withProperty(
                                "groupia.settlement.saspay-payouts.marketplace-authorized", "true")
                        .withProperty(
                                "groupia.settlement.saspay-payouts.beneficiary-proof-approved",
                                "true");
        var provider = new SaspayMerchantPayoutProvider(json, Clock.systemUTC(), env);
        assertThat(provider.enabled()).isTrue();
        provider.assertLivePilotTarget(
                directPilotPayout(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ORANGE_MONEY",
                        new BigDecimal("2500")));
        provider.assertLivePilotTarget(payout(null));
        // Verification must not require the old pilot shop when standard payouts are enabled.
        assertThatThrownBy(() -> provider.verify(payout("invalid-transaction-id")))
                .hasMessage("SASPAY_PAYOUT_IDENTIFIER_INVALID");
        env.withProperty("groupia.settlement.saspay-payouts.enabled", "false");
        assertThat(new SaspayMerchantPayoutProvider(json, Clock.systemUTC(), env).enabled())
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 202, 400, 401, 403, 409, 422, 429, 500, 502, 503})
    void unexpectedInitializeStatusesRemainUncertainAndAreNeverRetried(int status)
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var posts = new AtomicInteger();
        server.createContext("/api/v1/pricing/my-rates/", e -> respond(e, payoutRates("ADD_ON")));
        server.createContext(
                "/api/v1/merchant-balances/", e -> respond(e, walletBalance("1000.00", false)));
        server.createContext(
                "/api/v1/payouts/initialize/",
                e -> {
                    posts.incrementAndGet();
                    raw(
                            e,
                            status,
                            "application/json",
                            "{\"success\":false,\"error\":{\"code\":\"invalid_customer\",\"message\":\"SECRET +221771234567\"}}");
                });
        server.start();
        try {
            var provider = adapter(true, "http://127.0.0.1:" + server.getAddress().getPort());
            assertThatThrownBy(() -> provider.initiate(payout(null)))
                    .isInstanceOfSatisfying(
                            PayoutProviderFailure.class,
                            f -> {
                                assertThat(f.stage()).isEqualTo(Stage.INITIALIZE);
                                assertThat(f.stage().method).isEqualTo("POST");
                                assertThat(f.httpStatus()).isEqualTo(status);
                                assertThat(f.providerCode())
                                        .isEqualTo(ProviderCode.invalid_customer);
                                assertThat(f.submission()).isEqualTo(Submission.UNKNOWN);
                                assertThat(f.getCause()).isNull();
                                assertThat(f.toString()).doesNotContain("SECRET", "221771234567");
                            });
            assertThat(posts).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 409, 422, 429, 500, 503})
    void preflightHttpFailuresIdentifyTheGetAndProveNoPostAttempted(int status) throws Exception {
        for (boolean failRates : List.of(true, false)) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var posts = new AtomicInteger();
            String error = "{\"code\":\"wallet_frozen\",\"message\":\"confidential\"}";
            server.createContext(
                    "/api/v1/pricing/my-rates/",
                    e -> {
                        if (failRates) raw(e, status, "application/json", error);
                        else respond(e, payoutRates("DEDUCTED"));
                    });
            server.createContext(
                    "/api/v1/merchant-balances/", e -> raw(e, status, "application/json", error));
            server.createContext(
                    "/api/v1/payouts/initialize/",
                    e -> {
                        posts.incrementAndGet();
                        e.close();
                    });
            server.start();
            try {
                var provider = adapter(true, "http://127.0.0.1:" + server.getAddress().getPort());
                assertThatThrownBy(() -> provider.initiate(payout(null)))
                        .isInstanceOfSatisfying(
                                PayoutProviderFailure.class,
                                f -> {
                                    assertThat(f.stage())
                                            .isEqualTo(failRates ? Stage.RATES : Stage.WALLETS);
                                    assertThat(f.stage().method).isEqualTo("GET");
                                    assertThat(f.httpStatus()).isEqualTo(status);
                                    assertThat(f.providerCode())
                                            .isEqualTo(ProviderCode.wallet_frozen);
                                    assertThat(f.submission()).isEqualTo(Submission.NOT_ATTEMPTED);
                                });
                assertThat(posts).hasValue(0);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void malformedAcksHavePreciseSafeDiagnosticsAndCannotCountAsSuccess() throws Exception {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("{bad secret +221771234567", "INVALID_JSON");
        cases.put("null", "INVALID_JSON");
        cases.put("{\"success\":\"true\",\"data\":{}}", "INVALID_ENVELOPE");
        cases.put("{\"success\":true,\"data\":null}", "INVALID_ENVELOPE");
        cases.put(
                "{\"success\":false,\"error\":{\"code\":\"SASPAY_PAYOUT_PHONE_221771234567\"}}",
                "PROVIDER_REPORTED_FAILURE");
        cases.put("{}", "IDENTIFIER_INVALID");
        cases.put("{\"id\":\"bad\"}", "IDENTIFIER_INVALID");
        cases.put(" ".repeat(65537), "RESPONSE_TOO_LARGE");
        for (var entry : cases.entrySet()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/api/v1/pricing/my-rates/", e -> respond(e, payoutRates("DEDUCTED")));
            server.createContext(
                    "/api/v1/merchant-balances/", e -> respond(e, walletBalance("1000.00", false)));
            server.createContext(
                    "/api/v1/payouts/initialize/",
                    e -> raw(e, 201, "application/json", entry.getKey()));
            server.start();
            try {
                var provider = adapter(true, "http://127.0.0.1:" + server.getAddress().getPort());
                assertThatThrownBy(() -> provider.initiate(payout(null)))
                        .hasMessage("SASPAY_PAYOUT_" + entry.getValue())
                        .isInstanceOfSatisfying(
                                PayoutProviderFailure.class,
                                f -> {
                                    assertThat(f.submission()).isEqualTo(Submission.UNKNOWN);
                                    assertThat(f.httpStatus()).isEqualTo(201);
                                    assertThat(f.providerCode().name())
                                            .doesNotContain("221771234567");
                                    assertThat(f.getCause()).isNull();
                                });
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void transportTimeoutBeforeAndAfterPossiblePostHaveDifferentCertainty() {
        for (boolean beforePost : List.of(true, false)) {
            var builder =
                    org.springframework.web.client.RestClient.builder()
                            .baseUrl("http://localhost/api/v1");
            var server =
                    org.springframework.test.web.client.MockRestServiceServer.bindTo(builder)
                            .build();
            var rates =
                    server.expect(
                            org.springframework.test.web.client.match.MockRestRequestMatchers
                                    .requestTo("http://localhost/api/v1/pricing/my-rates/"));
            if (beforePost)
                rates.andRespond(
                        org.springframework.test.web.client.response.MockRestResponseCreators
                                .withException(new java.net.SocketTimeoutException("secret")));
            else {
                rates.andRespond(
                        org.springframework.test.web.client.response.MockRestResponseCreators
                                .withSuccess(
                                        payoutRates("DEDUCTED"),
                                        org.springframework.http.MediaType.APPLICATION_JSON));
                server.expect(
                                org.springframework.test.web.client.match.MockRestRequestMatchers
                                        .requestTo("http://localhost/api/v1/merchant-balances/"))
                        .andRespond(
                                org.springframework.test.web.client.response
                                        .MockRestResponseCreators.withSuccess(
                                        walletBalance("1000.00", false),
                                        org.springframework.http.MediaType.APPLICATION_JSON));
                server.expect(
                                org.springframework.test.web.client.match.MockRestRequestMatchers
                                        .requestTo("http://localhost/api/v1/payouts/initialize/"))
                        .andExpect(
                                org.springframework.test.web.client.match.MockRestRequestMatchers
                                        .method(org.springframework.http.HttpMethod.POST))
                        .andRespond(
                                org.springframework.test.web.client.response
                                        .MockRestResponseCreators.withException(
                                        new java.net.SocketTimeoutException("secret")));
            }
            var provider =
                    new SaspayMerchantPayoutProvider(
                            settings(), json, Clock.systemUTC(), true, true, true, builder.build());
            assertThatThrownBy(() -> provider.initiate(payout(null)))
                    .hasMessage("SASPAY_PAYOUT_TRANSPORT_UNCERTAIN")
                    .isInstanceOfSatisfying(
                            PayoutProviderFailure.class,
                            f -> {
                                assertThat(f.submission())
                                        .isEqualTo(
                                                beforePost
                                                        ? Submission.NOT_ATTEMPTED
                                                        : Submission.UNKNOWN);
                                assertThat(f.httpStatus()).isZero();
                                assertThat(f.category()).isEqualTo(Category.TRANSPORT);
                                assertThat(f.getCause()).isNull();
                            });
            server.verify();
        }
    }

    private static void raw(
            com.sun.net.httpserver.HttpExchange e, int status, String contentType, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().add("Content-Type", contentType);
        e.sendResponseHeaders(status, bytes.length);
        try (var out = e.getResponseBody()) {
            out.write(bytes);
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
