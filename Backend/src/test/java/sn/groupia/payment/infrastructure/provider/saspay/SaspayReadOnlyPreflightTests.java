package sn.groupia.payment.infrastructure.provider.saspay;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SaspayReadOnlyPreflightTests {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);

    private SaspayReadOnlyPreflight service(SaspayHttpStub stub) {
        var properties = SaspayTestConfiguration.properties();
        var client =
                new SaspayClient(
                        SaspayClient.builder(properties).baseUrl(stub.url() + "/api/v1").build(),
                        new ObjectMapper(),
                        CLOCK);
        return new SaspayReadOnlyPreflight(properties, CLOCK, new ObjectMapper(), client);
    }

    private static String balance(String merchant, String amount, String currency) {
        return """
                {"id":"a2b3c4d5-e6f7-4012-8a9b-0c1d2e3f4a5b","merchant":"%s",
                "country":"SN","currency":"%s","available_amount":"%s",
                "pending_amount":"25.00","is_frozen":false}
                """
                .formatted(merchant, currency, amount);
    }

    private static String history() {
        return """
                {"results":[{"id":"9c3f2a10-4b7e-4f1a-9d2e-9b6a7c1e4a02",
                "merchant":"%s","currency":"XOF",
                "flow_direction":"INBOUND","status":"SUCCESS"}],"next":null}
                """
                .formatted(SaspayTestConfiguration.MERCHANT);
    }

    @Test
    void verifiedWalletAndHistoryReadsAreStrictlyGetOnlyAndBounded() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            stub.reply(
                    200,
                    "{\"count\":1,\"results\":["
                            + balance(SaspayTestConfiguration.MERCHANT, "2500.00", "XOF")
                            + "],\"next\":null}");
            stub.reply(200, history());
            var snapshot = service(stub).check();
            assertThat(snapshot.status()).isEqualTo("READ_ONLY_ACCESS_OK");
            assertThat(snapshot.senegalAvailableXof()).isEqualByComparingTo("2500");
            assertThat(snapshot.senegalPendingXof()).isEqualByComparingTo("25");
            assertThat(snapshot.senegalXofWallets()).isEqualTo(1);
            assertThat(snapshot.sampledTransactions()).isEqualTo(1);
            assertThat(snapshot.noteCode()).isEqualTo("DIAGNOSTIC_NOT_ACCOUNTING_PROOF");
            assertThat(stub.requests).hasSize(2);
            assertThat(stub.requests)
                    .allMatch(
                            x ->
                                    x.method().equals("GET")
                                            && x.body().isEmpty()
                                            && x.idempotencyKey() == null);
            assertThat(stub.requests.getFirst().path())
                    .contains("/merchant-balances/")
                    .contains("page_size=100");
            assertThat(stub.requests.getLast().path())
                    .contains("/transactions/")
                    .contains("merchant=")
                    .contains("created_at__gte=")
                    .contains("page_size=100");
        }
    }

    @Test
    void aSecondManualPreflightCannotHammerTheProviderImmediately() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            stub.reply(
                    200,
                    "{\"count\":1,\"results\":["
                            + balance(SaspayTestConfiguration.MERCHANT, "2500", "XOF")
                            + "],\"next\":null}");
            stub.reply(200, history());
            var preflight = service(stub);
            preflight.check();
            assertThatThrownBy(preflight::check)
                    .isInstanceOf(sn.groupia.shared.api.ApiException.class);
            assertThat(stub.requests).hasSize(2);
        }
    }

    @Test
    void truncatedWalletPagesCannotBeDescribedAsComplete() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            stub.reply(
                    200,
                    "{\"count\":2,\"results\":["
                            + balance(SaspayTestConfiguration.MERCHANT, "100", "XOF")
                            + "],\"next\":\"https://sandbox-api.saspay.me/api/v1/merchant-balances/?page=2\"}");
            stub.reply(200, history());
            var snapshot = service(stub).check();
            assertThat(snapshot.status()).isEqualTo("INCOMPLETE_SAMPLE");
            assertThat(snapshot.balancesComplete()).isFalse();
            assertThat(stub.requests).hasSize(2);
        }
    }

    @Test
    void wrongMerchantFailsBeforeHistoricalReadWithoutAnyTransfer() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            stub.reply(
                    200,
                    "{\"count\":1,\"results\":["
                            + balance(UUID.randomUUID().toString(), "1000", "XOF")
                            + "],\"next\":null}");
            assertThatThrownBy(() -> service(stub).check())
                    .isInstanceOf(SaspayException.class)
                    .hasMessage("SASPAY_PREFLIGHT_MERCHANT_MISMATCH");
            assertThat(stub.requests).hasSize(1);
            assertThat(stub.requests.getFirst().method()).isEqualTo("GET");
        }
    }

    @Test
    void invalidMoneyOrUntrustedHistoryNeverBecomesAReadyStatus() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            stub.reply(
                    200,
                    "{\"count\":1,\"results\":["
                            + balance(SaspayTestConfiguration.MERCHANT, "-1.00", "XOF")
                            + "],\"next\":null}");
            assertThatThrownBy(() -> service(stub).check())
                    .isInstanceOf(SaspayException.class)
                    .hasMessage("SASPAY_PREFLIGHT_BALANCES_INVALID");
            assertThat(stub.requests).hasSize(1);
        }
        try (var stub = new SaspayHttpStub()) {
            stub.reply(
                    200,
                    "{\"count\":1,\"results\":["
                            + balance(SaspayTestConfiguration.MERCHANT, "1000", "XOF")
                            + "],\"next\":null}");
            stub.reply(
                    200,
                    "{\"results\":[{\"merchant\":\""
                            + UUID.randomUUID()
                            + "\",\"currency\":\"XOF\","
                            + "\"flow_direction\":\"INBOUND\",\"status\":\"SUCCESS\"}],\"next\":null}");
            assertThatThrownBy(() -> service(stub).check())
                    .isInstanceOf(SaspayException.class)
                    .hasMessage("SASPAY_PREFLIGHT_HISTORY_INVALID");
            assertThat(stub.requests).hasSize(2);
        }
    }

    @Test
    void disabledSaspayCannotTriggerAnyProviderNetworkRead() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            var disabled =
                    new SaspayProperties(
                            false,
                            null,
                            null,
                            null,
                            UUID.fromString(SaspayTestConfiguration.MERCHANT),
                            Set.of(),
                            null,
                            null,
                            false,
                            null,
                            null);
            var service = new SaspayReadOnlyPreflight(disabled, CLOCK, new ObjectMapper(), null);
            assertThatThrownBy(service::check)
                    .isInstanceOf(sn.groupia.shared.api.ApiException.class);
            assertThat(stub.requests).isEmpty();
        }
    }
}
