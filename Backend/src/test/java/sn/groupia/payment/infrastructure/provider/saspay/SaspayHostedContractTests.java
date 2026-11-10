package sn.groupia.payment.infrastructure.provider.saspay;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.PaymentCheckoutContext;
import sn.groupia.payment.domain.service.*;
import tools.jackson.databind.ObjectMapper;

/** Synthetic HTTP-only contract tests: no SasPay LIVE calls or payment side effects. */
class SaspayHostedContractTests {
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);
    private static final String SESSION = "a9b67211-150f-4c71-885c-123450000001";
    private static final String PAYMENT = "b9b67211-150f-4c71-885c-123450000002";

    private SaspayPaymentProvider provider(SaspayHttpStub stub) {
        var props = SaspayTestConfiguration.properties();
        return new SaspayPaymentProvider(
                new SaspayClient(
                        SaspayClient.builder(props).baseUrl(stub.url() + "/api/v1").build(),
                        json, clock),
                props, null, json, clock, false, URI.create("http://localhost:4200"));
    }

    private ProviderInstruction instruction(String sessionId) {
        return new ProviderInstruction(
                UUID.fromString("c9b67211-150f-4c71-885c-123450000003"),
                UUID.randomUUID(),
                null,
                PaymentPurpose.DIRECT_ORDER_INITIAL,
                "SASPAY",
                SaspayTestConfiguration.MERCHANT,
                "GP-test-reference",
                null,
                UUID.randomUUID().toString(),
                new BigDecimal("1000"),
                "XOF",
                new PaymentCheckoutContext(
                        "DIR-TEST-1", "Awa Ndiaye", "+221771234567", "awa@example.com"),
                PaymentTarget.directOrder(UUID.randomUUID()),
                sessionId);
    }

    private String created() {
        return "{\"id\":\"" + SESSION + "\",\"merchant\":\""
                + SaspayTestConfiguration.MERCHANT
                + "\",\"amount\":\"1000.00\",\"currency\":\"XOF\",\"status\":\"PENDING\","
                + "\"checkout_url\":\"https://pay.saspay.me/checkout/synthetic\"}";
    }

    private String detail(ProviderInstruction i) {
        return "{\"id\":\"" + SESSION + "\",\"merchant\":\""
                + SaspayTestConfiguration.MERCHANT
                + "\",\"amount\":\"1000.00\",\"currency\":\"XOF\","
                + "\"metadata\":{\"groupia_payment_attempt_id\":\"" + i.paymentId()
                + "\",\"groupia_reference\":\"GP-test-reference\"}}";
    }

    private String status(String value) {
        return "{\"id\":\"" + SESSION + "\",\"status\":\"" + value
                + "\",\"transaction_id\":\"" + PAYMENT
                + "\",\"transaction_status\":\"SUCCESS\"}";
    }

    private String verified(String feeMode) {
        return "{\"id\":\"" + PAYMENT + "\",\"merchant\":\""
                + SaspayTestConfiguration.MERCHANT
                + "\",\"requested_amount\":\"1000.00\",\"net_amount\":\"1000.00\","
                + "\"client_fee\":\"37.50\",\"debited_amount\":\"1037.50\","
                + "\"currency\":\"XOF\",\"status\":\"SUCCESS\",\"fee_charge_mode\":\""
                + feeMode + "\",\"transaction_type\":\"PAIEMENT\",\"flow_direction\":\"INBOUND\"}";
    }

    @Test
    void createsHostedSessionOnlyAndPreservesSessionSeparatelyFromTransaction() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            var i = instruction(null);
            stub.reply(201, created());
            var result = provider(stub).initiate(i);
            assertThat(result.status()).isEqualTo(PaymentStatus.PENDING);
            assertThat(result.providerTransactionId()).isNull();
            assertThat(result.metadata().chargeId()).isEqualTo(SESSION);
            assertThat(result.metadata().checkoutUrl())
                    .isEqualTo("https://pay.saspay.me/checkout/synthetic");
            assertThat(stub.requests).hasSize(1);
            assertThat(stub.requests.getFirst().method()).isEqualTo("POST");
            assertThat(stub.requests.getFirst().path())
                    .isEqualTo("/api/v1/checkout-sessions/");
            assertThat(stub.requests.getFirst().body()).contains("\"fee_charge_mode\":\"ADD_ON\"");
            assertThat(stub.requests.getFirst().body())
                    .contains("\"return_url\":\"http://localhost:4200/paiement/retour\"");
            assertThat(stub.requests.getFirst().idempotencyKey()).isNull();
        }
    }

    @Test
    void pendingSessionNeverPretendsToBeFinanciallySettled() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            var i = instruction(SESSION);
            stub.reply(200, detail(i));
            stub.reply(200, status("PENDING"));
            var o = provider(stub).verify(i);
            assertThat(o.status()).isEqualTo(PaymentStatus.PENDING);
            assertThat(o.providerTransactionId()).isNull();
            assertThat(o.metadata().chargeId()).isEqualTo(SESSION);
            assertThat(stub.requests).hasSize(2);
            assertThat(stub.requests).allSatisfy(r -> assertThat(r.method()).isEqualTo("GET"));
        }
    }

    @Test
    void paidSessionRequiresIndependentFinancialProofAndKeepsExactDecimals()
            throws Exception {
        try (var stub = new SaspayHttpStub()) {
            var i = instruction(SESSION);
            stub.reply(200, detail(i));
            stub.reply(200, status("PAID"));
            stub.reply(200, verified("ADD_ON"));
            var o = provider(stub).verify(i);
            assertThat(o.status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(o.providerTransactionId()).isEqualTo(PAYMENT);
            assertThat(o.metadata().collectionFee()).isEqualByComparingTo("37.50");
            assertThat(o.metadata().customerCharged()).isEqualByComparingTo("1037.50");
            assertThat(stub.requests).hasSize(3);
            assertThat(stub.requests).allSatisfy(r -> assertThat(r.method()).isEqualTo("GET"));
        }
    }

    @Test
    void paidSessionDoesNotSettleIfFeeChargeModeIsDeducted() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            var i = instruction(SESSION);
            stub.reply(200, detail(i));
            stub.reply(200, status("PAID"));
            stub.reply(200, verified("DEDUCTED"));
            assertThatThrownBy(() -> provider(stub).verify(i))
                    .hasMessage("SASPAY_FINANCIAL_MISMATCH");
        }
    }

    @Test
    void externalSessionDoesNotMatchAnotherGroupiaPaymentAttempt() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            var i = instruction(SESSION);
            stub.reply(200, detail(instruction(SESSION)));
            assertThatThrownBy(() -> provider(stub).verify(i))
                    .hasMessage("SASPAY_HOSTED_SESSION_MISMATCH");
        }
    }
}
