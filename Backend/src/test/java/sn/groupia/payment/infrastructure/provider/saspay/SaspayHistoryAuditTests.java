package sn.groupia.payment.infrastructure.provider.saspay;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SaspayHistoryAuditTests {
    private static final String FIRST = "9c3f2a10-4b7e-4f1a-9d2e-9b6a7c1e4a02";
    private static final String SECOND = "a4b5c6d7-e8f9-4a0b-8c1d-2e3f4a5b6c7d";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-18T20:00:00Z"), ZoneOffset.UTC);
    private static final Instant FROM = Instant.parse("2026-11-17T20:00:00Z");
    private static final Instant UNTIL = CLOCK.instant();

    private SaspayHistoryAudit audit(SaspayHttpStub stub, SaspayHistoryAudit.LedgerLookup lookup) {
        var props = SaspayTestConfiguration.properties();
        var client =
                new SaspayClient(
                        SaspayClient.builder(props).baseUrl(stub.url() + "/api/v1").build(),
                        new ObjectMapper(),
                        CLOCK);
        return new SaspayHistoryAudit(client, lookup, props, CLOCK);
    }

    private static SaspayHistoryAudit.Evidence inbound(String status, String amount) {
        return new SaspayHistoryAudit.Evidence(
                "INBOUND",
                status,
                new BigDecimal(amount),
                "XOF",
                new BigDecimal("37"),
                new BigDecimal("1037"),
                false);
    }

    private static String transaction(String id, String status, String amount) {
        return """
                {"id":"%s","merchant":"%s","transaction_type":"PAIEMENT",
                "flow_direction":"INBOUND","status":"%s","currency":"XOF",
                "requested_amount":"%s","net_amount":"%s","fee_charge_mode":"ADD_ON",
                "client_fee":"37.00","debited_amount":"1037.00"}
                """
                .formatted(id, SaspayTestConfiguration.MERCHANT, status, amount, amount);
    }

    private static String payout(String id) {
        return """
                {"id":"%s","merchant":"%s","transaction_type":"TRANSFERT",
                "flow_direction":"OUTBOUND","status":"SUCCESS","currency":"XOF",
                "requested_amount":"1000.00","net_amount":"963.00",
                "fee_charge_mode":"DEDUCTED","client_fee":"37.00","debited_amount":"1000.00"}
                """
                .formatted(id, SaspayTestConfiguration.MERCHANT);
    }

    @Test
    void comparesSuccessfulPayinWithoutAnyProviderPostOrLedgerMutation() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            stub.reply(
                    200,
                    "{\"results\":[" + transaction(FIRST, "SUCCESS", "1000") + "],\"next\":null}");
            var audit =
                    audit(
                            stub,
                            (account, id) -> {
                                assertThat(account).isEqualTo(SaspayTestConfiguration.MERCHANT);
                                assertThat(id).isEqualTo(FIRST);
                                return List.of(inbound("SUCCEEDED", "1000"));
                            });

            assertThat(audit.compare(FROM, UNTIL))
                    .isEqualTo(new SaspayHistoryAudit.Result(1, 1, 1, 0, 0));
            assertThat(stub.requests).hasSize(1);
            assertThat(stub.requests.getFirst().method()).isEqualTo("GET");
            assertThat(stub.requests.getFirst().body()).isEmpty();
            assertThat(stub.requests.getFirst().path()).contains("/transactions/");
            assertThat(stub.requests.getFirst().path()).contains("created_at__gte=");
            assertThat(stub.requests.getFirst().path()).contains("merchant=");
        }
    }

    @Test
    void followsBoundedCursorAndAcceptsHistoricalTransfertPayouts() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            stub.reply(
                    200,
                    "{\"results\":["
                            + transaction(FIRST, "SUCCESS", "1000")
                            + "],\"next\":\"https://sandbox-api.saspay.me/api/v1/transactions/?cursor=abc123\"}");
            stub.reply(200, "{\"results\":[" + payout(SECOND) + "],\"next\":null}");
            var audit =
                    audit(
                            stub,
                            (account, id) -> {
                                if (id.equals(FIRST)) return List.of(inbound("SUCCEEDED", "1000"));
                                return List.of(
                                        new SaspayHistoryAudit.Evidence(
                                                "OUTBOUND",
                                                "SUCCEEDED",
                                                new BigDecimal("1000"),
                                                "XOF",
                                                null,
                                                null,
                                                false));
                            });

            assertThat(audit.compare(FROM, UNTIL))
                    .isEqualTo(new SaspayHistoryAudit.Result(2, 2, 2, 0, 0));
            assertThat(stub.requests).hasSize(2);
            assertThat(stub.requests).allMatch(request -> request.method().equals("GET"));
            assertThat(stub.requests.get(1).path()).contains("cursor=abc123");
        }
    }

    @Test
    void flagsMismatchAndUnmatchedProviderTransactionsWithoutChangingBalances() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            stub.reply(
                    200,
                    "{\"results\":["
                            + transaction(FIRST, "SUCCESS", "2000")
                            + ","
                            + transaction(SECOND, "SUCCESS", "1000")
                            + "],\"next\":null}");
            var audit =
                    audit(
                            stub,
                            (account, id) ->
                                    id.equals(FIRST)
                                            ? List.of(inbound("SUCCEEDED", "1000"))
                                            : List.of());

            assertThat(audit.compare(FROM, UNTIL))
                    .isEqualTo(new SaspayHistoryAudit.Result(1, 2, 1, 1, 1));
            assertThat(stub.requests).hasSize(1);
        }
    }

    @Test
    void rejectsUntrustedPaginationHostWithoutFetchingIt() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            stub.reply(
                    200,
                    "{\"results\":[],\"next\":\"https://attacker.example/api/v1/transactions/?cursor=bad\"}");
            var audit = audit(stub, (account, id) -> List.of());

            assertThatThrownBy(() -> audit.compare(FROM, UNTIL))
                    .isInstanceOf(SaspayException.class)
                    .hasMessage("SASPAY_HISTORY_NEXT_INVALID");
            assertThat(stub.requests).hasSize(1);
            assertThat(stub.requests.getFirst().method()).isEqualTo("GET");
        }
    }

    @Test
    void incompleteOrMalformedProviderHistoryFailsClosed() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            stub.reply(200, "{\"results\":{},\"next\":null}");
            var audit = audit(stub, (account, id) -> List.of());

            assertThatThrownBy(() -> audit.compare(FROM, UNTIL))
                    .isInstanceOf(SaspayException.class)
                    .hasMessage("SASPAY_HISTORY_PAGE_INVALID");
        }
    }

    @Test
    void reportsProviderSuccessThatGroupiaHasNotYetConfirmed() throws Exception {
        try (var stub = new SaspayHttpStub()) {
            stub.reply(
                    200,
                    "{\"results\":[" + transaction(FIRST, "SUCCESS", "1000") + "],\"next\":null}");
            var audit = audit(stub, (account, id) -> List.of(inbound("PENDING", "1000")));
            assertThat(audit.compare(FROM, UNTIL).differences()).isEqualTo(1);
        }
    }
}
