package sn.groupia.payment.application.service;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import sn.groupia.payment.domain.service.WebhookParsingFailure;

class WebhookParsingDiagnosticsTests {
    @Test
    void reportsOnlyNamesAndTypesAtTheTwoSupportedLevels() {
        var shape =
                WebhookParsingDiagnostics.inspect(
                        ("""
                {"id":"transaction-secret","amount":2300,"currency":null,"status":{},
                 "paymentReference":[],"merchantReference":true,"reference":"display-secret",
                 "type":"secret-type","event":"secret-event","timestamp":"secret-time",
                 "customerObject":{"phone":"+221779999999","email":"private@example.test"},
                 "checkoutUrl":"https://example.test/secret",
                 "data":{"id":17,"amount":"2300","currency":"XOF","status":null,
                         "customerObject":{"address":"sensitive-address"},"customEnvelope":{}}}
                """)
                                .getBytes(StandardCharsets.UTF_8));
        assertThat(shape.rootType()).isEqualTo("object");
        assertThat(shape.dataType()).isEqualTo("object");
        assertThat(shape.rootFields())
                .contains("id", "amount", "data")
                .doesNotContain("customerObject", "checkoutUrl");
        assertThat(shape.dataFields())
                .containsExactly("id", "amount", "currency", "status", "<unknown-field>");
        assertThat(shape.rootFieldTypes())
                .containsEntry("id", "string")
                .containsEntry("amount", "number")
                .containsEntry("currency", "null")
                .containsEntry("status", "object")
                .containsEntry("paymentReference", "array")
                .containsEntry("merchantReference", "boolean")
                .hasSize(10);
        assertThat(shape.dataFieldTypes())
                .containsEntry("id", "number")
                .containsEntry("amount", "string")
                .containsEntry("timestamp", "missing")
                .hasSize(10);
        assertThat(shape.toString())
                .doesNotContain(
                        "transaction-secret",
                        "2300",
                        "XOF",
                        "display-secret",
                        "secret-type",
                        "secret-event",
                        "secret-time",
                        "customerObject",
                        "checkoutUrl",
                        "sensitive-address",
                        "+221779999999",
                        "private@example.test",
                        "https://");
    }

    @Test
    void hostileFieldNamesAreRedactedAndOutputIsBounded() {
        String payload =
                "{\"private@example.test\":1,\"+221779999999\":2,\"bad\\nWARN\":3,"
                        + java.util.stream.IntStream.range(0, 80)
                                .mapToObj(i -> "\"field" + i + "\":\"secret\"")
                                .collect(java.util.stream.Collectors.joining(","))
                        + "}";
        var shape = WebhookParsingDiagnostics.inspect(payload.getBytes(StandardCharsets.UTF_8));
        assertThat(shape.rootFields()).hasSize(41).contains("<unknown-field>", "<truncated>");
        assertThat(shape.toString())
                .doesNotContain("private@example.test", "+221779999999", "bad", "WARN", "secret");
    }

    @Test
    void scalarArrayAndMalformedRootsNeverExposeValues() {
        assertThat(inspect("\"private@example.test\"").rootType()).isEqualTo("string");
        assertThat(inspect("42").rootType()).isEqualTo("number");
        assertThat(inspect("null").rootType()).isEqualTo("null");
        assertThat(inspect("[\"secret\"]").rootType()).isEqualTo("array");
        assertThat(inspect("{\"id\":\"secret\"").rootType()).isEqualTo("invalid");
        assertThat(inspect("{\"id\":1,\"id\":2}").rootType()).isEqualTo("invalid");
        assertThat(inspect("{\"data\":\"private@example.test\"}").dataType()).isEqualTo("string");
        assertThat(inspect("[\"secret\"]").rootFields()).isEmpty();
    }

    private WebhookParsingDiagnostics.Shape inspect(String s) {
        return WebhookParsingDiagnostics.inspect(s.getBytes(StandardCharsets.UTF_8));
    }

    private enum Code {
        TEST_PARSE_FAILED;

        @Override
        public String toString() {
            return "sensitive-enum-text";
        }
    }

    private static class Classified extends RuntimeException implements WebhookParsingFailure {
        Classified() {
            super("secret message", new IllegalStateException("secret cause"));
        }

        public Enum<?> diagnosticCode() {
            return Code.TEST_PARSE_FAILED;
        }
    }

    @Test
    void onlyEnumNameCrossesDiagnosticBoundary() {
        assertThat(WebhookParsingFailure.safeCode(new Classified())).isEqualTo("TEST_PARSE_FAILED");
        assertThat(WebhookParsingFailure.safeCode(new RuntimeException("secret message")))
                .isEqualTo("WEBHOOK_PARSE_FAILED");
    }
}
