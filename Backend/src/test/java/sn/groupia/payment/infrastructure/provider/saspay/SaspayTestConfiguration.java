package sn.groupia.payment.infrastructure.provider.saspay;

import java.time.*;
import java.util.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import sn.groupia.payment.domain.repository.PaymentQuoteStore;
import sn.groupia.payment.domain.service.*;
import tools.jackson.databind.ObjectMapper;

@TestConfiguration(proxyBeanMethods = false)
public class SaspayTestConfiguration {
    public static final String MERCHANT = "6f1e2b3a-8c4d-4a2f-9e1b-2d3c4f5a6b7c";
    public static final String SECRET = "synthetic-saspay-signing-secret";

    @Bean(destroyMethod = "close")
    SaspayHttpStub saspayStub() throws Exception {
        return new SaspayHttpStub();
    }

    @Bean
    @Primary
    sn.groupia.ordering.application.service.CheckoutPolicy zeroCommissionPolicy() {
        return new sn.groupia.ordering.application.service.CheckoutPolicy(
                java.math.BigDecimal.ZERO, 30, 180);
    }

    static SaspayProperties properties() {
        return new SaspayProperties(
                true,
                null,
                "sk_test_synthetic",
                SECRET,
                UUID.fromString(MERCHANT),
                Set.of("pay.saspay.me"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2));
    }

    @Bean
    PaymentProvider saspayTestProvider(
            SaspayHttpStub http, PaymentQuoteStore store, ObjectMapper json, Clock clock) {
        var p = properties();
        var adapter =
                new SaspayPaymentProvider(
                        new SaspayClient(
                                SaspayClient.builder(p).baseUrl(http.url() + "/api/v1").build(),
                                json,
                                clock),
                        p,
                        store,
                        json,
                        clock);
        return new PaymentProvider() {
            public String name() {
                return adapter.name();
            }

            public String accountReference() {
                return adapter.accountReference();
            }

            public boolean requiresFeeQuote() {
                return true;
            }

            public ProviderFee quote(java.math.BigDecimal a, String c, String n) {
                outside();
                return adapter.quote(a, c, n);
            }

            public List<ProviderFee> quotes(java.math.BigDecimal a, String c) {
                outside();
                return adapter.quotes(a, c);
            }

            public ProviderObservation initiate(ProviderInstruction i) {
                outside();
                return adapter.initiate(i);
            }

            public ProviderObservation verify(ProviderInstruction i) {
                outside();
                return adapter.verify(i);
            }

            public boolean verifyAuthenticity(byte[] b, Map<String, String> h) {
                return adapter.verifyAuthenticity(b, h);
            }

            public NormalizedWebhook parse(byte[] b) {
                return adapter.parse(b);
            }

            void outside() {
                if (org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive())
                    throw new AssertionError("Provider network call inside transaction");
            }
        };
    }

    public static String rates() {
        return """
        [{"country_code":"SN","currency":"XOF","network_code":"wave_sn","network_name":"Wave",
        "payin":{"available":true,"otp_required":false,"tiers":[{"min_amount":"0.00","max_amount":"500000.00",
        "currency":"XOF","percent":"2.500","fixed":"0.00","floor_amount":null,"cap_amount":null,"fee_charge_mode":"ADD_ON"}]}}]
        """;
    }

    public static String signature(byte[] body, String timestamp) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(
                new javax.crypto.spec.SecretKeySpec(
                        SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return java.util.HexFormat.of().formatHex(mac.doFinal(body));
    }
}
