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
                ID, org, UUID.randomUUID(), new BigDecimal("1000"), "XOF", "PENDING",
                UUID.randomUUID(), "WAVE", "+221771234567", "Awa Test", "SASPAY",
                MERCHANT.toString(), "MERCHANT_PAYOUT:" + org + ":" + UUID.randomUUID(),
                UUID.randomUUID(), transaction, UUID.randomUUID(),
                Instant.parse("2026-11-11T00:00:00Z"), null, null, true,
                "PAYOUT_PROVIDER_UNCERTAIN", 0);
    }

    private SaspayMerchantPayoutProvider adapter(boolean enabled, String origin) {
        var http = SaspayMerchantPayoutProvider.makeHttp(settings()).mutate()
                .baseUrl(origin + "/api/v1").build();
        return new SaspayMerchantPayoutProvider(
                settings(), json, Clock.systemUTC(), enabled, true, true, true, http);
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
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("groupia.payment.saspay.api-key", "sk_live_synthetic")
                .withProperty("groupia.payment.saspay.merchant-id", MERCHANT.toString())
                .withProperty("groupia.payment.saspay.enabled", "true")
                .withProperty(
                        "groupia.settlement.saspay-payouts.wave-network", "wave_sn")
                .withProperty(
                        "groupia.settlement.saspay-payouts.orange-network", "orange_sn")
                .withProperty("groupia.settlement.saspay-payouts.enabled", "true")
                .withProperty("groupia.settlement.saspay-payouts.marketplace-authorized", "true")
                .withProperty(
                        "groupia.settlement.saspay-payouts.beneficiary-proof-approved", "true")
                .withProperty(
                        "groupia.settlement.saspay-payouts.company-fee-budget-approved", "true");
        var provider = new SaspayMerchantPayoutProvider(json, Clock.systemUTC(), env);
        assertThat(provider.enabled()).isFalse();
        assertThatThrownBy(() -> provider.initiate(payout(null)))
                .hasMessage("SASPAY_PAYOUT_NOT_AUTHORIZED");
    }

    @Test
    void initializedPayoutHasBoundNetworkNetAndDurableIdempotency() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        var original = payout(null);
        server.createContext("/api/v1/payouts/initialize/", exchange -> {
            requests.incrementAndGet();
            var body = json.readTree(exchange.getRequestBody().readAllBytes());
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key"))
                    .isEqualTo(original.providerIdempotencyKey().toString());
            assertThat(body.path("amount").asText()).isEqualTo("1000.00");
            assertThat(body.path("currency").asText()).isEqualTo("XOF");
            assertThat(body.path("country").asText()).isEqualTo("SN");
            assertThat(body.path("method").asText()).isEqualTo("wave_sn");
            assertThat(body.path("fee_charge_mode").asText()).isEqualTo("ADD_ON");
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
    void missingIndependentRecipientProofNeverBecomesSuccessfulPayout() throws Exception {
        var p = payout(TX.toString());
        var provider = adapter(true, "http://127.0.0.1:1");
        var e = json.readTree(verified(null, null));
        assertThatThrownBy(() -> provider.validateProof(p, e, Instant.now()))
                .hasMessage("SASPAY_PAYOUT_RECIPIENT_EVIDENCE_MISSING");
    }

    @Test
    void verifiedRecipientAndCompanyFundedFeesMustMatchExactly() throws Exception {
        var p = payout(TX.toString());
        var provider = adapter(true, "http://127.0.0.1:1");
        var proof = provider.validateProof(
                p, json.readTree(verified("771234567", "wave_sn")), Instant.now());
        assertThat(proof.authenticated()).isTrue();
        assertThat(proof.status()).isEqualTo("SUCCEEDED");
        assertThat(proof.transactionId()).isEqualTo(TX.toString());
        assertThat(proof.phoneE164()).isEqualTo(p.phoneE164());
        assertThat(proof.amount()).isEqualByComparingTo("1000");
    }

    @Test
    void wrongMerchantAndNetAreRejectedBeforeSettlement() throws Exception {
        var p = payout(TX.toString());
        var provider = adapter(true, "http://127.0.0.1:1");
        String valid = verified("771234567", "wave_sn");
        assertThatThrownBy(() -> provider.validateProof(
                p, json.readTree(valid.replace(MERCHANT.toString(), UUID.randomUUID().toString())),
                Instant.now())).hasMessage("SASPAY_PAYOUT_EVIDENCE_MISMATCH");
        assertThatThrownBy(() -> provider.validateProof(
                p, json.readTree(valid.replace("\"net_amount\":\"1000.00\"",
                "\"net_amount\":\"948.00\"")), Instant.now()))
                .hasMessage("SASPAY_PAYOUT_FINANCIAL_MISMATCH");
    }

    private String verified(String phone, String network) {
        String recipient = phone == null ? "" : ",\"recipient_msisdn\":\"" + phone + "\"";
        String gateway = network == null ? "" : ",\"network_code\":\"" + network + "\"";
        return "{\"id\":\"" + TX + "\",\"merchant\":\"" + MERCHANT
                + "\",\"transaction_type\":\"RETRAIT\",\"flow_direction\":\"OUTBOUND\","
                + "\"currency\":\"XOF\",\"fee_charge_mode\":\"ADD_ON\","
                + "\"requested_amount\":\"1000.00\",\"client_fee\":\"52.00\","
                + "\"debited_amount\":\"1052.00\",\"net_amount\":\"1000.00\","
                + "\"status\":\"SUCCESS\"" + recipient + gateway + "}";
    }
}
