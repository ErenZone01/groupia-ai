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

    @Test
    void acceptsNumericJsonRateFieldsAsExactDecimalsWithoutGuessingXofRounding() {
        var numericRates =
                json.readTree(
                        SaspayTestConfiguration.rates()
                                .replace("\"min_amount\":\"0.00\"", "\"min_amount\":0.00")
                                .replace("\"max_amount\":\"500000.00\"", "\"max_amount\":500000.00")
                                .replace("\"percent\":\"2.500\"", "\"percent\":2.500")
                                .replace("\"fixed\":\"0.00\"", "\"fixed\":0.00"));

        assertThat(SaspayPricing.quote(numericRates, new BigDecimal("1000"), "XOF", "wave_sn").fee())
                .isEqualByComparingTo("25");
        assertThatThrownBy(
                        () -> SaspayPricing.quote(numericRates, new BigDecimal("1100"), "XOF", "wave_sn"))
                .hasMessage("SASPAY_XOF_ROUNDING_UNCONFIRMED");
    }

    @Test
    void acceptsExponentNotationForJsonNumericRatesWithoutAcceptingScientificStrings() {
        var rates =
                json.readTree(
                        SaspayTestConfiguration.rates()
                                .replace("\"min_amount\":\"0.00\"", "\"min_amount\":0E-12")
                                .replace("\"max_amount\":\"500000.00\"", "\"max_amount\":5E+5")
                                .replace("\"percent\":\"2.500\"", "\"percent\":2.5E+0")
                                .replace("\"fixed\":\"0.00\"", "\"fixed\":0E-12"));
        assertThat(SaspayPricing.quote(rates, new BigDecimal("1000"), "XOF", "wave_sn").fee())
                .isEqualByComparingTo("25");
        assertThatThrownBy(
                        () -> SaspayPricing.quote(rates, new BigDecimal("1100"), "XOF", "wave_sn"))
                .hasMessage("SASPAY_XOF_ROUNDING_UNCONFIRMED");

        var tier = (tools.jackson.databind.node.ObjectNode)
                rates.get(0).path("payin").path("tiers").get(0);
        tier.put("percent", "2.5E+0");
        assertThatThrownBy(
                        () -> SaspayPricing.quote(rates, new BigDecimal("1000"), "XOF", "wave_sn"))
                .hasMessage("SASPAY_INVALID_MONEY");
    }

    @Test
    void exactNumericJsonMoneyRejectsFractionalExcessivePrecisionAndOversizedAmounts() {
        assertThatThrownBy(
                        () -> SaspayPricing.decimal(
                                json.readTree("{\"amount\":0.0000001}"), "amount"))
                .hasMessage("SASPAY_INVALID_MONEY");
        assertThatThrownBy(
                        () -> SaspayPricing.decimal(
                                json.readTree("{\"amount\":100000000000000}"), "amount"))
                .hasMessage("SASPAY_INVALID_MONEY");
        assertThatThrownBy(
                        () -> SaspayPricing.decimal(
                                json.readTree("{\"amount\":-1E+3}"), "amount"))
                .hasMessage("SASPAY_INVALID_MONEY");
    }

    @Test
    void rejectsInvalidNumericShapesAndExcessivePrecision() {
        var rates = json.readTree(SaspayTestConfiguration.rates());
        var tier = (tools.jackson.databind.node.ObjectNode)
                rates.get(0).path("payin").path("tiers").get(0);

        tier.put("min_amount", -1);
        assertThatThrownBy(
                        () -> SaspayPricing.quote(rates, new BigDecimal("1000"), "XOF", "wave_sn"))
                .hasMessage("SASPAY_INVALID_MONEY");

        tier.put("min_amount", true);
        assertThatThrownBy(
                        () -> SaspayPricing.quote(rates, new BigDecimal("1000"), "XOF", "wave_sn"))
                .hasMessage("SASPAY_INVALID_MONEY");

        tier.put("min_amount", "0.0000001");
        assertThatThrownBy(
                        () -> SaspayPricing.quote(rates, new BigDecimal("1000"), "XOF", "wave_sn"))
                .hasMessage("SASPAY_INVALID_MONEY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"100000000000000", "99999999999999"})
    void rejectsAmountsOrTotalsOutsideTheVerificationRange(String amount) {
        var rates = json.readTree(SaspayTestConfiguration.rates());
        var tier =
                (tools.jackson.databind.node.ObjectNode)
                        rates.get(0).path("payin").path("tiers").get(0);
        tier.putNull("max_amount");
        tier.put("percent", "0.00");
        tier.put("fixed", "1.00");
        assertThatThrownBy(
                        () -> SaspayPricing.quote(rates, new BigDecimal(amount), "XOF", "wave_sn"))
                .hasMessage("SASPAY_UNSUPPORTED_QUOTE");
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
                                        null,
                                        false,
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
                                        null,
                                        false,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void livePilotRequiresExplicitOrganizationAndCapsAndProdProfile() {
        var p = SaspayTestConfiguration.properties();
        var live = new SaspayProperties(
                true, null, "sk_live_pilot_synthetic", p.webhookSecret(), p.merchantId(),
                p.checkoutHosts(), null, null, true,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new BigDecimal("2000"));
        assertThat(live.isLive()).isTrue();
        var development = new org.springframework.mock.env.MockEnvironment();
        assertThatThrownBy(() -> SaspayConfiguration.validateProfile(live, development))
                .isInstanceOf(IllegalArgumentException.class);
        development.setActiveProfiles("prod");
        assertThatCode(() -> SaspayConfiguration.validateProfile(live, development))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new SaspayProperties(
                        true, null, "sk_live_pilot_synthetic", p.webhookSecret(), p.merchantId(),
                        p.checkoutHosts(), null, null, true,
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        new BigDecimal("10000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SaspayProperties(
                        true, null, "sk_live_pilot_synthetic", p.webhookSecret(), p.merchantId(),
                        p.checkoutHosts(), null, null, true, null, new BigDecimal("2000")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void livePilotQuoteIsCappedIncludingProviderFeesAndLimitedToOneOrganization() throws Exception {
        var p = SaspayTestConfiguration.properties();
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var live = new SaspayProperties(
                true, null, "sk_live_pilot_synthetic", p.webhookSecret(), p.merchantId(),
                p.checkoutHosts(), null, null, true, organizationId, new BigDecimal("2000"));
        try (var stub = new SaspayHttpStub()) {
            var adapter = new SaspayPaymentProvider(
                    new SaspayClient(
                            SaspayClient.builder(live).baseUrl(stub.url() + "/api/v1").build(),
                            json, clock),
                    live, null, json, clock);
            assertThat(adapter.canQuoteForOrganization(organizationId)).isTrue();
            assertThat(adapter.canQuoteForOrganization(UUID.randomUUID())).isFalse();
            stub.reply(200, SaspayTestConfiguration.rates());
            assertThat(adapter.quote(new BigDecimal("1000"), "XOF", "wave_sn").fee())
                    .isEqualByComparingTo("25");
            stub.reply(200, SaspayTestConfiguration.rates());
            assertThatThrownBy(() -> adapter.quote(new BigDecimal("2000"), "XOF", "wave_sn"))
                    .hasMessage("SASPAY_LIVE_PILOT_LIMIT");
        }
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
