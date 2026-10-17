package sn.groupia.payment.infrastructure.provider.bictorys;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import sn.groupia.payment.api.OutgoingPayoutOperations.*;

class BictorysOutgoingSecurityTests {
    static final String SECRET = "synthetic-webhook-secret";
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC);
    static final byte[] BODY = "{\"fixture\":\"é\"}".getBytes(StandardCharsets.UTF_8);

    private String signature(String timestamp, byte[] body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void hmacAuthenticatesTheExactUtf8Bytes() throws Exception {
        String ts = Long.toString(CLOCK.millis());
        assertThat(
                        BictorysWebhookAuthentication.verify(
                                BODY,
                                Map.of(
                                        "X-Webhook-Signature",
                                        signature(ts, BODY),
                                        "X-Webhook-Timestamp",
                                        ts),
                                SECRET,
                                CLOCK))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "old",
                "future",
                "invalid-time",
                "body",
                "timestamp",
                "signature",
                "missing-signature",
                "missing-time",
                "duplicate-case"
            })
    void invalidHmacNeverDowngradesToCorrectStaticSecret(String defect) throws Exception {
        String ts = Long.toString(CLOCK.millis());
        byte[] body = BODY;
        if (defect.equals("old")) ts = Long.toString(CLOCK.millis() - 300001);
        if (defect.equals("future")) ts = Long.toString(CLOCK.millis() + 300001);
        var headers =
                new HashMap<>(
                        Map.of(
                                "X-Secret-Key",
                                SECRET,
                                "X-Webhook-Timestamp",
                                ts,
                                "X-Webhook-Signature",
                                signature(ts, BODY)));
        switch (defect) {
            case "invalid-time" -> headers.put("X-Webhook-Timestamp", "invalid");
            case "body" -> body = "{}".getBytes(StandardCharsets.UTF_8);
            case "timestamp" ->
                    headers.put("X-Webhook-Timestamp", Long.toString(CLOCK.millis() + 1));
            case "signature" -> headers.put("X-Webhook-Signature", "0".repeat(64));
            case "missing-signature" -> headers.remove("X-Webhook-Signature");
            case "missing-time" -> headers.remove("X-Webhook-Timestamp");
            case "duplicate-case" -> headers.put("x-webhook-signature", "0".repeat(64));
            default -> {}
        }
        assertThat(BictorysWebhookAuthentication.verify(body, headers, SECRET, CLOCK)).isFalse();
    }

    @Test
    void staticOnlyCompatibilityAndAmbiguousHeaders() {
        assertThat(
                        BictorysWebhookAuthentication.verify(
                                BODY, Map.of("X-Secret-Key", SECRET), SECRET, CLOCK))
                .isTrue();
        assertThat(
                        BictorysWebhookAuthentication.verify(
                                BODY, Map.of("X-Secret-Key", "wrong"), SECRET, CLOCK))
                .isFalse();
        assertThat(BictorysWebhookAuthentication.verify(BODY, Map.of(), SECRET, CLOCK)).isFalse();
        assertThat(
                        BictorysWebhookAuthentication.verify(
                                BODY,
                                Map.of("X-Secret-Key", SECRET, "x-secret-key", "wrong"),
                                SECRET,
                                CLOCK))
                .isFalse();
    }

    private final Registration operation =
            new Registration(
                    Kind.MERCHANT_PAYOUT,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "BICTORYS",
                    "sandbox",
                    "MERCHANT_PAYOUT:fixture",
                    UUID.randomUUID(),
                    new BigDecimal("10000"),
                    "XOF",
                    "WAVE",
                    CLOCK.instant());
    private final BictorysRefundProvider provider =
            new BictorysRefundProvider(
                    new BictorysPayoutProperties(false, null, null, null),
                    SECRET,
                    RestClient.create());

    private String payload() {
        return """
        {"id":"synthetic-payout-tx","merchantReference":"MERCHANT_PAYOUT:fixture","amount":-10000,
         "currency":"XOF","pspName":"wave_money","customerPhone":"221771234567","status":"succeeded",
         "merchantFee":150,"customerFee":0,"timestamp":"2026-10-17T00:00:00Z"}
        """;
    }

    @Test
    void principalAndFeesRemainDistinct() {
        var e = provider.parse(operation, payload().getBytes(StandardCharsets.UTF_8));
        assertThat(e.amount()).isEqualByComparingTo("10000");
        assertThat(e.providerFee()).isEqualByComparingTo("150");
        assertThat(e.recipientPhone()).isEqualTo("+221771234567");
        assertThat(e.errorCode()).isNull();
        assertThat(e.toString()).doesNotContain("771234567", SECRET);
    }

    @Test
    void incompatibleNetAmountCannotBeRepairedWithFee() {
        var e =
                provider.parse(
                        operation,
                        payload().replace("-10000", "-9850").getBytes(StandardCharsets.UTF_8));
        assertThat(e.errorCode()).isEqualTo("PAYOUT_AMOUNT_SEMANTICS_UNCONFIRMED");
        assertThat(e.amount()).isEqualByComparingTo("9850");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "pending",
                "processing",
                "authorized",
                "failed",
                "cancelled",
                "canceled",
                "reversed"
            })
    void nonSuccessIsNeverInventedAsSuccess(String status) {
        var e =
                provider.parse(
                        operation,
                        payload().replace("succeeded", status).getBytes(StandardCharsets.UTF_8));
        assertThat(e.status()).isNotEqualTo("SUCCEEDED");
        if (status.equals("reversed")) assertThat(e.errorCode()).isEqualTo("PAYOUT_REVERSED");
    }

    @Test
    void unknownIdentityCannotAuthorizeSignNormalization() {
        assertThatThrownBy(
                        () ->
                                provider.parse(
                                        operation,
                                        payload()
                                                .replace("MERCHANT_PAYOUT:fixture", "GP-charge")
                                                .getBytes(StandardCharsets.UTF_8)))
                .hasMessage("PAYOUT_REFERENCE_MISMATCH");
    }
}
