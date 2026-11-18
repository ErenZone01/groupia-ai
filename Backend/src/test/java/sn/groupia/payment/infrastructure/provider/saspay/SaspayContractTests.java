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
    void waveSoftpayRedirectUsesConfiguredHostAndKeepsRealTransactionReference() throws Exception {
        var original = SaspayTestConfiguration.properties();
        var approved =
                new SaspayProperties(
                        original.enabled(),
                        original.apiUrl(),
                        original.apiKey(),
                        original.webhookSecret(),
                        original.merchantId(),
                        Set.of("pay.saspay.me", "pay.wave.com"),
                        original.connectTimeout(),
                        original.readTimeout(),
                        false,
                        null,
                        null);
        var denied = original;
        var orderId = UUID.randomUUID();
        var requestId = UUID.randomUUID();
        var q =
                new sn.groupia.payment.domain.model.PaymentFeeQuote(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        requestId,
                        "SASPAY",
                        original.merchantId().toString(),
                        "SN",
                        "wave_sn",
                        "Wave",
                        new BigDecimal("1000"),
                        new BigDecimal("25"),
                        "XOF",
                        clock.instant().plusSeconds(120));
        sn.groupia.payment.domain.repository.PaymentQuoteStore quoteStore =
                new sn.groupia.payment.domain.repository.PaymentQuoteStore() {
                    public void save(sn.groupia.payment.domain.model.PaymentFeeQuote value) {}

                    public Optional<sn.groupia.payment.domain.model.PaymentFeeQuote> find(UUID id) {
                        return q.id().equals(id) ? Optional.of(q) : Optional.empty();
                    }
                };
        var instruction =
                new sn.groupia.payment.domain.service.ProviderInstruction(
                        UUID.randomUUID(),
                        requestId,
                        null,
                        sn.groupia.payment.api.PaymentPurpose.DIRECT_ORDER_INITIAL,
                        "SASPAY",
                        original.merchantId().toString(),
                        "GRP-DIRECT-REFERENCE",
                        null,
                        UUID.randomUUID().toString(),
                        new BigDecimal("1000"),
                        "XOF",
                        new sn.groupia.payment.api.dto.PaymentCheckoutContext(
                                        "DIR-000000000123",
                                        "Awa Ndiaye",
                                        "+221771234567",
                                        "awa@example.test")
                                .withFeeQuote(q.id()),
                        sn.groupia.payment.api.PaymentTarget.directOrder(orderId));
        var transactionId = "3ef59f24-4bf9-43a6-b983-c9c632005ab3";
        var response =
                "{\"id\":\""
                        + transactionId
                        + "\",\"status\":\"PENDING\",\"checkout_url\":\"https://pay.wave.com/checkout/opaque\"}";

        for (var properties : List.of(denied, approved)) {
            try (var stub = new SaspayHttpStub()) {
                var provider =
                        new SaspayPaymentProvider(
                                new SaspayClient(
                                        SaspayClient.builder(properties)
                                                .baseUrl(stub.url() + "/api/v1")
                                                .build(),
                                        json,
                                        clock),
                                properties,
                                quoteStore,
                                json,
                                clock,
                                true,
                                java.net.URI.create("http://localhost:4200"),
                                true);
                stub.reply(200, SaspayTestConfiguration.rates());
                stub.reply(201, response);
                var observation = provider.initiate(instruction);
                assertThat(observation.providerTransactionId()).isEqualTo(transactionId);
                assertThat(observation.status())
                        .isEqualTo(sn.groupia.payment.api.PaymentStatus.PENDING);
                assertThat(stub.requests).hasSize(2);
                assertThat(stub.requests.get(0).method()).isEqualTo("GET");
                assertThat(stub.requests.get(1).method()).isEqualTo("POST");
                if (properties == denied) {
                    assertThat(observation.metadata().checkoutUrl()).isNull();
                    assertThat(observation.metadata().reviewReason())
                            .isEqualTo(
                                    sn.groupia.payment.domain.service.ProviderMetadata.ReviewReason
                                            .PROVIDER_STATUS_UNKNOWN);
                } else {
                    assertThat(observation.metadata().checkoutUrl())
                            .isEqualTo("https://pay.wave.com/checkout/opaque");
                    assertThat(observation.metadata().reviewReason()).isNull();
                }
            }
        }
    }

    @Test
    void normalizesCompactAndDashedSaspayIdsWithoutAcceptingMalformedReferences() {
        var id = "cb8f14cd-5dad-4793-b305-e65e1365908d";
        assertThat(SaspayClient.uuid(id)).isEqualTo(id);
        assertThat(SaspayClient.uuid(id.replace("-", ""))).isEqualTo(id);
        assertThat(SaspayClient.uuid(id.toUpperCase(java.util.Locale.ROOT))).isEqualTo(id);
        assertThatThrownBy(() -> SaspayClient.uuid("cb8f14cd5dad4793b305e65e1365908"))
                .hasMessage("SASPAY_INVALID_IDENTIFIER");
        assertThatThrownBy(() -> SaspayClient.uuid("cb8f14cd5dad4793b305e65e1365908G"))
                .hasMessage("SASPAY_INVALID_IDENTIFIER");
        assertThatThrownBy(() -> SaspayClient.uuid(null)).hasMessage("SASPAY_INVALID_IDENTIFIER");
    }

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
    void optedInFractionalXofQuotesUseDocumentedExactDecimalsWithoutRounding() {
        var rates = json.readTree(SaspayTestConfiguration.rates());
        // A 2.5% SasPay tariff produces 62.50 XOF at 2500 XOF.
        assertThat(SaspayPricing.quote(rates, new BigDecimal("2500"), "XOF", "wave_sn", true).fee())
                .isEqualByComparingTo("62.50");
        // This flag must not silently round a tariff such as 62.525.
        assertThatThrownBy(
                        () ->
                                SaspayPricing.quote(
                                        rates, new BigDecimal("2501"), "XOF", "wave_sn", true))
                .hasMessage("SASPAY_XOF_PRECISION_UNCONFIRMED");
        // Existing integer-only behavior stays the default.
        assertThatThrownBy(
                        () -> SaspayPricing.quote(rates, new BigDecimal("2500"), "XOF", "wave_sn"))
                .hasMessage("SASPAY_XOF_ROUNDING_UNCONFIRMED");
    }

    @Test
    void optedInProviderPublishesFractionalQuoteWithoutInitiatingAnyPayment() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            var p = SaspayTestConfiguration.properties();
            var provider =
                    new SaspayPaymentProvider(
                            new SaspayClient(
                                    SaspayClient.builder(p).baseUrl(stub.url() + "/api/v1").build(),
                                    json,
                                    clock),
                            p,
                            null,
                            json,
                            clock,
                            true);
            stub.reply(200, SaspayTestConfiguration.rates());
            var fees = provider.quotes(new BigDecimal("2500"), "XOF");
            assertThat(fees).hasSize(1);
            assertThat(fees.getFirst().network()).isEqualTo("wave_sn");
            assertThat(fees.getFirst().fee()).isEqualByComparingTo("62.50");
            assertThat(stub.requests).hasSize(1);
            assertThat(stub.requests.getFirst().method()).isEqualTo("GET");
        }
    }

    @Test
    void absentRateIsDistinctFromOverlappingRateWithoutAcceptingEither() {
        var rates = json.readTree(SaspayTestConfiguration.rates());
        var tier =
                (tools.jackson.databind.node.ObjectNode)
                        rates.get(0).path("payin").path("tiers").get(0);
        tier.put("min_amount", "3000");
        assertThatThrownBy(
                        () ->
                                SaspayPricing.quote(
                                        rates, new BigDecimal("1000"), "XOF", "wave_sn", true))
                .hasMessage("SASPAY_RATE_NOT_APPLICABLE");
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

        assertThat(
                        SaspayPricing.quote(numericRates, new BigDecimal("1000"), "XOF", "wave_sn")
                                .fee())
                .isEqualByComparingTo("25");
        assertThatThrownBy(
                        () ->
                                SaspayPricing.quote(
                                        numericRates, new BigDecimal("1100"), "XOF", "wave_sn"))
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

        var tier =
                (tools.jackson.databind.node.ObjectNode)
                        rates.get(0).path("payin").path("tiers").get(0);
        tier.put("percent", "2.5E+0");
        assertThatThrownBy(
                        () -> SaspayPricing.quote(rates, new BigDecimal("1000"), "XOF", "wave_sn"))
                .hasMessage("SASPAY_INVALID_MONEY");
    }

    @Test
    void exactNumericJsonMoneyRejectsFractionalExcessivePrecisionAndOversizedAmounts() {
        assertThatThrownBy(
                        () ->
                                SaspayPricing.decimal(
                                        json.readTree("{\"amount\":0.0000001}"), "amount"))
                .hasMessage("SASPAY_INVALID_MONEY");
        assertThatThrownBy(
                        () ->
                                SaspayPricing.decimal(
                                        json.readTree("{\"amount\":100000000000000}"), "amount"))
                .hasMessage("SASPAY_INVALID_MONEY");
        assertThatThrownBy(
                        () -> SaspayPricing.decimal(json.readTree("{\"amount\":-1E+3}"), "amount"))
                .hasMessage("SASPAY_INVALID_MONEY");
    }

    @Test
    void rejectsInvalidNumericShapesAndExcessivePrecision() {
        var rates = json.readTree(SaspayTestConfiguration.rates());
        var tier =
                (tools.jackson.databind.node.ObjectNode)
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
    void verifiedWebhookRejectsConflictingAndDuplicateEventHeaders() throws Exception {
        byte[] body =
                "{\"event\":\"transaction.success\",\"data\":{}}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String stamp = Long.toString(clock.instant().getEpochSecond());
        var headers = new HashMap<String, String>();
        headers.put("X-Webhook-Signature", SaspayTestConfiguration.signature(body, stamp));
        headers.put("X-Webhook-Timestamp", stamp);
        headers.put("X-Webhook-Event", "transaction.success");
        assertThat(
                        SaspayWebhookAuthentication.verify(
                                body, headers, SaspayTestConfiguration.SECRET, clock))
                .isTrue();
        headers.put("X-Webhook-Event", "transaction.failed");
        assertThat(
                        SaspayWebhookAuthentication.verify(
                                body, headers, SaspayTestConfiguration.SECRET, clock))
                .isFalse();
        headers.put("X-Webhook-Event", "transaction.success");
        headers.put("x-webhook-event", "transaction.success");
        assertThat(
                        SaspayWebhookAuthentication.verify(
                                body, headers, SaspayTestConfiguration.SECRET, clock))
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
    void credentialsMustNeverCrossSaspaySandboxAndProductionEnvironments() {
        var sandbox = SaspayTestConfiguration.properties();
        assertThat(sandbox.apiUrl())
                .isEqualTo(java.net.URI.create("https://sandbox-api.saspay.me/api/v1"));
        var live =
                new SaspayProperties(
                        true,
                        null,
                        "sk_live_environment_fixture",
                        sandbox.webhookSecret(),
                        sandbox.merchantId(),
                        sandbox.checkoutHosts(),
                        null,
                        null,
                        true,
                        UUID.randomUUID(),
                        new BigDecimal("2000"));
        assertThat(live.apiUrl()).isEqualTo(java.net.URI.create("https://api.saspay.me/api/v1"));
        assertThatThrownBy(
                        () ->
                                new SaspayProperties(
                                        true,
                                        java.net.URI.create("https://api.saspay.me/api/v1"),
                                        sandbox.apiKey(),
                                        sandbox.webhookSecret(),
                                        sandbox.merchantId(),
                                        sandbox.checkoutHosts(),
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
                                        java.net.URI.create("https://sandbox-api.saspay.me/api/v1"),
                                        live.apiKey(),
                                        live.webhookSecret(),
                                        live.merchantId(),
                                        live.checkoutHosts(),
                                        null,
                                        null,
                                        true,
                                        live.livePilotOrganizationId(),
                                        live.livePilotMaxChargedXof()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void springBootBindsTheCanonicalProductionRecordConstructor() {
        var config =
                new org.springframework.boot.context.properties.source
                        .MapConfigurationPropertySource(
                        Map.of(
                                "groupia.payment.saspay.enabled",
                                "true",
                                "groupia.payment.saspay.api-key",
                                "sk_live_multishop_synthetic",
                                "groupia.payment.saspay.webhook-secret",
                                SaspayTestConfiguration.SECRET,
                                "groupia.payment.saspay.merchant-id",
                                SaspayTestConfiguration.MERCHANT,
                                "groupia.payment.saspay.checkout-hosts",
                                "checkout.saspay.me",
                                "groupia.payment.saspay.production-enabled",
                                "true",
                                "groupia.payment.saspay.production-max-charged-xof",
                                "100000"));
        var binder = new org.springframework.boot.context.properties.bind.Binder(config);
        var properties =
                binder.bind(
                                "groupia.payment.saspay",
                                org.springframework.boot.context.properties.bind.Bindable.of(
                                        SaspayProperties.class))
                        .get();
        assertThat(properties.productionEnabled()).isTrue();
        assertThat(properties.livePilotEnabled()).isFalse();
        assertThat(properties.productionMaxChargedXof())
                .isEqualByComparingTo("100000");
        assertThat(properties.checkoutHosts()).contains("checkout.saspay.me");
    }

    @Test
    void productionCheckoutSupportsMultipleOrganizationsButKeepsIndependentServerLimits()
            throws Exception {
        var fixture = SaspayTestConfiguration.properties();
        var live =
                new SaspayProperties(
                        true,
                        null,
                        "sk_live_multi_organization_fixture",
                        fixture.webhookSecret(),
                        fixture.merchantId(),
                        fixture.checkoutHosts(),
                        null,
                        null,
                        false,
                        null,
                        null,
                        true,
                        new BigDecimal("10000"));
        assertThat(live.productionEnabled()).isTrue();
        assertThat(live.livePilotEnabled()).isFalse();
        assertThat(live.apiUrl()).isEqualTo(java.net.URI.create("https://api.saspay.me/api/v1"));
        try (var stub = new SaspayHttpStub()) {
            var adapter =
                    new SaspayPaymentProvider(
                            new SaspayClient(
                                    SaspayClient.builder(live)
                                            .baseUrl(stub.url() + "/api/v1")
                                            .build(),
                                    json,
                                    clock),
                            live,
                            null,
                            json,
                            clock);
            assertThat(adapter.canQuoteForOrganization(UUID.randomUUID())).isTrue();
            assertThat(adapter.canQuoteForOrganization(UUID.randomUUID())).isTrue();
            stub.reply(200, SaspayTestConfiguration.rates());
            assertThat(adapter.quote(new BigDecimal("1000"), "XOF", "wave_sn").fee()).isPositive();
            stub.reply(200, SaspayTestConfiguration.rates());
            assertThatThrownBy(() -> adapter.quote(new BigDecimal("10000"), "XOF", "wave_sn"))
                    .hasMessage("SASPAY_PRODUCTION_AMOUNT_LIMIT");
        }
        assertThatThrownBy(
                        () ->
                                new SaspayProperties(
                                        true,
                                        null,
                                        live.apiKey(),
                                        live.webhookSecret(),
                                        live.merchantId(),
                                        live.checkoutHosts(),
                                        null,
                                        null,
                                        false,
                                        null,
                                        null,
                                        true,
                                        new BigDecimal("10000001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SaspayProperties(
                                        true,
                                        null,
                                        live.apiKey(),
                                        live.webhookSecret(),
                                        live.merchantId(),
                                        live.checkoutHosts(),
                                        null,
                                        null,
                                        true,
                                        UUID.randomUUID(),
                                        new BigDecimal("2000"),
                                        true,
                                        new BigDecimal("10000")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void livePilotRequiresExplicitOrganizationAndCapsAndProdProfile() {
        var p = SaspayTestConfiguration.properties();
        var live =
                new SaspayProperties(
                        true,
                        null,
                        "sk_live_pilot_synthetic",
                        p.webhookSecret(),
                        p.merchantId(),
                        p.checkoutHosts(),
                        null,
                        null,
                        true,
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        new BigDecimal("2000"));
        assertThat(live.isLive()).isTrue();
        var development = new org.springframework.mock.env.MockEnvironment();
        assertThatThrownBy(() -> SaspayConfiguration.validateProfile(live, development))
                .isInstanceOf(IllegalArgumentException.class);
        development.setActiveProfiles("prod");
        assertThatCode(() -> SaspayConfiguration.validateProfile(live, development))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                new SaspayProperties(
                                        true,
                                        null,
                                        "sk_live_pilot_synthetic",
                                        p.webhookSecret(),
                                        p.merchantId(),
                                        p.checkoutHosts(),
                                        null,
                                        null,
                                        true,
                                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                        new BigDecimal("10000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SaspayProperties(
                                        true,
                                        null,
                                        "sk_live_pilot_synthetic",
                                        p.webhookSecret(),
                                        p.merchantId(),
                                        p.checkoutHosts(),
                                        null,
                                        null,
                                        true,
                                        null,
                                        new BigDecimal("2000")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void livePilotQuoteIsCappedIncludingProviderFeesAndLimitedToOneOrganization() throws Exception {
        var p = SaspayTestConfiguration.properties();
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var live =
                new SaspayProperties(
                        true,
                        null,
                        "sk_live_pilot_synthetic",
                        p.webhookSecret(),
                        p.merchantId(),
                        p.checkoutHosts(),
                        null,
                        null,
                        true,
                        organizationId,
                        new BigDecimal("2000"));
        try (var stub = new SaspayHttpStub()) {
            var adapter =
                    new SaspayPaymentProvider(
                            new SaspayClient(
                                    SaspayClient.builder(live)
                                            .baseUrl(stub.url() + "/api/v1")
                                            .build(),
                                    json,
                                    clock),
                            live,
                            null,
                            json,
                            clock);
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
    @ValueSource(ints = {400, 401, 403, 404, 409, 410, 422, 429, 500, 503})
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

    @Test
    void cancelledPayinWebhookIsRecognizedAndNeverMistakenForSuccess() throws Exception {
        var properties = SaspayTestConfiguration.properties();
        var adapter =
                new SaspayPaymentProvider(
                        new SaspayClient(SaspayClient.builder(properties).build(), json, clock),
                        properties,
                        null,
                        json,
                        clock);
        String event =
                "{\"event\":\"transaction.cancelled\",\"data\":{\"id\":"
                        + "\"a4b5c6d7-e8f9-4a0b-8c1d-2e3f4a5b6c7d\","
                        + "\"type\":\"PAIEMENT\",\"status\":\"CANCELLED\","
                        + "\"fee_charge_mode\":\"ADD_ON\","
                        + "\"amount\":\"1000.00\",\"fee\":\"0.00\","
                        + "\"charged\":\"1000.00\",\"net_amount\":\"1000.00\","
                        + "\"currency\":\"XOF\"}}";
        var parsed = adapter.parse(event.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(parsed.observation().status())
                .isEqualTo(sn.groupia.payment.api.PaymentStatus.CANCELLED);
        assertThatThrownBy(
                        () ->
                                adapter.parse(
                                        event.replace(
                                                        "transaction.cancelled",
                                                        "transaction.success")
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .hasMessage("SASPAY_INCONSISTENT_EVENT");
        assertThatThrownBy(
                        () ->
                                adapter.parse(
                                        event.replace("PAIEMENT", "TRANSFERT")
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .hasMessage("SASPAY_UNSUPPORTED_FLOW");
    }

    @Test
    void providerBusinessCodesAreSanitizedWithoutPersistingUntrustedResponse() throws Exception {
        var properties = SaspayTestConfiguration.properties();
        try (var stub = new SaspayHttpStub()) {
            var client =
                    new SaspayClient(
                            SaspayClient.builder(properties)
                                    .baseUrl(stub.url() + "/api/v1")
                                    .build(),
                            json,
                            clock);
            stub.reply(
                    422,
                    "{\"success\":false,\"error\":"
                            + "{\"code\":\"insufficient_balance\",\"message\":\"private\"}}");
            var known = catchThrowableOfType(client::rates, SaspayException.class);
            assertThat(known).hasMessage("SASPAY_HTTP_422").hasNoCause();
            assertThat(known.httpStatus()).isEqualTo(422);
            assertThat(known.providerCode()).isEqualTo("insufficient_balance");

            stub.reply(403, "{\"code\":\"attacker_controlled_sensitive_value\"}");
            var unknown = catchThrowableOfType(client::rates, SaspayException.class);
            assertThat(unknown).hasMessage("SASPAY_HTTP_403").hasNoCause();
            assertThat(unknown.httpStatus()).isEqualTo(403);
            assertThat(unknown.providerCode()).isEqualTo("UNRECOGNIZED");

            stub.reply(200, "{\"success\":false,\"error\":{\"code\":\"merchant_suspended\"}}");
            var logicalFailure = catchThrowableOfType(client::rates, SaspayException.class);
            assertThat(logicalFailure).hasMessage("SASPAY_PROVIDER_REPORTED_FAILURE");
            assertThat(logicalFailure.providerCode()).isEqualTo("merchant_suspended");
            assertThat(logicalFailure.httpStatus()).isEqualTo(200);
            assertThat(stub.requests).hasSize(3);
        }
    }

    @Test
    void malformedSuccessfulResponsesCannotMasqueradeAsTransportUncertainty() throws Exception {
        var properties = SaspayTestConfiguration.properties();
        try (var stub = new SaspayHttpStub()) {
            var client =
                    new SaspayClient(
                            SaspayClient.builder(properties)
                                    .baseUrl(stub.url() + "/api/v1")
                                    .build(),
                            json,
                            clock);
            stub.reply(200, "not-json");
            assertThatThrownBy(client::rates).hasMessage("SASPAY_INVALID_JSON").hasNoCause();
            stub.reply(200, "null");
            assertThatThrownBy(client::rates).hasMessage("SASPAY_INVALID_RESPONSE").hasNoCause();
            stub.reply(200, "{\"success\":true,\"data\":null}");
            assertThatThrownBy(client::rates).hasMessage("SASPAY_INVALID_RESPONSE").hasNoCause();
            assertThat(stub.requests).hasSize(3);
        }
    }
}
