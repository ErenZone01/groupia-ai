package sn.groupia.payment.infrastructure.provider.saspay;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sn.groupia.payment.domain.service.WebhookParsingFailure;

class SaspayWebhookDiagnosticsTests {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"event\":\"transaction.success\",\"event\":\"transaction.failed\",\"data\":{}}",
                "{\"data\":{\"id\":\"first\",\"id\":\"second\"}}",
                "{\"data\":{}} {\"secret\":\"sensitive\"}",
                "not-json"
            })
    void ambiguousJsonCannotBeFinancialEvidence(String payload) {
        assertThatThrownBy(() -> SaspayWebhookJson.parse(payload.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(
                        SaspayException.class,
                        ex ->
                                assertThat(WebhookParsingFailure.safeCode(ex))
                                        .isEqualTo("SASPAY_INVALID_JSON"))
                .hasMessage("SASPAY_INVALID_JSON")
                .hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}", "{\"data\":null}", "{\"data\":[]}"})
    void invalidShapeIsClassifiedWithoutRetainingPayload(String payload) {
        assertThatThrownBy(() -> SaspayWebhookJson.parse(payload.getBytes(StandardCharsets.UTF_8)))
                .hasMessage("SASPAY_INVALID_RESPONSE")
                .hasNoCause();
    }
}
