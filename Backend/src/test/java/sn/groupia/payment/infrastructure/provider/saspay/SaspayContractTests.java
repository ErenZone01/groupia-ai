package sn.groupia.payment.infrastructure.provider.saspay;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class SaspayContractTests {
    final ObjectMapper json = new ObjectMapper();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void exactIntegerAddOnAndNoGuessedRounding() {
        var rates = json.readTree(SaspayTestConfiguration.rates());
        assertThat(SaspayPricing.quote(rates, new BigDecimal("2000"), "XOF", "wave_sn").fee())
                .isEqualByComparingTo("50");
        assertThatThrownBy(
                        () -> SaspayPricing.quote(rates, new BigDecimal("2500"), "XOF", "wave_sn"))
                .hasMessage("SASPAY_XOF_ROUNDING_UNCONFIRMED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEDUCTED", "UNKNOWN"})
    void refusesOtherFeeModes(String mode) {
        var rates = json.readTree(SaspayTestConfiguration.rates().replace("ADD_ON", mode));
        assertThatThrownBy(
                        () -> SaspayPricing.quote(rates, new BigDecimal("2000"), "XOF", "wave_sn"))
                .hasMessage("SASPAY_ADD_ON_REQUIRED");
    }

    @Test
    void rejectsOtpAndAmbiguousTiers() {
        var source = SaspayTestConfiguration.rates();
        assertThatThrownBy(
                        () ->
                                SaspayPricing.quote(
                                        json.readTree(
                                                source.replace(
                                                        "\"otp_required\":false",
                                                        "\"otp_required\":true")),
                                        new BigDecimal("2000"),
                                        "XOF",
                                        "wave_sn"))
                .hasMessage("SASPAY_NETWORK_UNAVAILABLE");
        var duplicate = (tools.jackson.databind.node.ArrayNode) json.readTree(source).deepCopy();
        duplicate.add(duplicate.get(0));
        assertThatThrownBy(
                        () ->
                                SaspayPricing.quote(
                                        duplicate, new BigDecimal("2000"), "XOF", "wave_sn"))
                .hasMessage("SASPAY_AMBIGUOUS_RATE");
    }

    @Test
    void signatureCoversRawBodyAndTimeWithCaseInsensitiveHeaders() throws Exception {
        byte[] body =
                "{ \"event\": \"webhook.test\" }".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        var headers =
                Map.of(
                        "x-webhook-signature",
                        SaspayTestConfiguration.signature(body, timestamp),
                        "x-webhook-timestamp",
                        timestamp);
        assertThat(
                        SaspayWebhookAuthentication.verify(
                                body, headers, SaspayTestConfiguration.SECRET, clock))
                .isTrue();
        assertThat(
                        SaspayWebhookAuthentication.verify(
                                "{}".getBytes(), headers, SaspayTestConfiguration.SECRET, clock))
                .isFalse();
        assertThat(
                        SaspayWebhookAuthentication.verify(
                                body,
                                headers,
                                SaspayTestConfiguration.SECRET,
                                Clock.offset(clock, Duration.ofSeconds(301))))
                .isFalse();
        assertThat(
                        SaspayWebhookAuthentication.verify(
                                body,
                                headers,
                                SaspayTestConfiguration.SECRET,
                                Clock.offset(clock, Duration.ofSeconds(-301))))
                .isFalse();
        var duplicate = new HashMap<>(headers);
        duplicate.put("X-Webhook-Timestamp", timestamp);
        assertThat(
                        SaspayWebhookAuthentication.verify(
                                body, duplicate, SaspayTestConfiguration.SECRET, clock))
                .isFalse();
    }

    @Test
    void liveKeysAndArbitraryHostsAreRejectedAndSecretsRedacted() {
        var p = SaspayTestConfiguration.properties();
        assertThat(p.toString()).doesNotContain(p.apiKey(), p.webhookSecret());
        assertThatThrownBy(
                        () ->
                                new SaspayProperties(
                                        true,
                                        null,
                                        "sk_live_forbidden",
                                        p.webhookSecret(),
                                        p.merchantId(),
                                        Set.of(),
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SaspayProperties(
                                        true,
                                        java.net.URI.create("https://evil.example"),
                                        p.apiKey(),
                                        p.webhookSecret(),
                                        p.merchantId(),
                                        Set.of(),
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 409, 422, 429, 500})
    void httpErrorsAreSanitizedAndNeverAutomaticallyRetried(int status) throws Exception {
        try (var stub = new SaspayHttpStub()) {
            var p = SaspayTestConfiguration.properties();
            var client =
                    new SaspayClient(
                            SaspayClient.builder(p).baseUrl(stub.url() + "/api/v1").build(),
                            json,
                            clock);
            stub.reply(status, "sensitive payload must not escape");
            assertThatThrownBy(client::rates).hasMessage("SASPAY_HTTP_" + status).hasNoCause();
            assertThat(stub.requests).hasSize(1);
            assertThat(stub.requests.getFirst().path()).isEqualTo("/api/v1/pricing/my-rates/");
            if (status == 429) {
                assertThatThrownBy(client::rates).hasMessage("SASPAY_RATE_LIMITED");
                assertThat(stub.requests).hasSize(1);
            }
        }
    }
}
