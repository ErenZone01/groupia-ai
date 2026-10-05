package sn.groupia.payment.infrastructure.provider.bictorys;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
class BictorysPayoutContractTests {
    private BictorysRefundProvider provider(){
        return new BictorysRefundProvider(new BictorysPayoutProperties(false,null,null,null),"synthetic-webhook-secret",RestClient.create());
    }
    private String evidence(){return """
        {"transactionType":"refund","id":"refund-tx","merchantReference":"GPR-fixture","amount":-500,"currency":"XOF",
        "customerPhone":"221771234567","status":"succeeded","timestamp":"2026-10-05T12:00:00Z"}
        """;}
    @Test void disabledPayoutsRequireNoPrivateCredentials(){
        var p=new BictorysPayoutProperties(false,null,null,null);
        assertThat(p.enabled()).isFalse();assertThat(provider().enabled()).isFalse();
        assertThat(provider().authentic(Map.of("x-secret-key","synthetic-webhook-secret"))).isTrue();
        assertThat(provider().authentic(Map.of("X-Secret-Key","wrong"))).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"missing-key","public-key","missing-code","invalid-timeout"})
    void enabledPayoutsValidateDedicatedCredentials(String defect){
        assertThatThrownBy(()->new BictorysPayoutProperties(true,
            defect.equals("missing-key")?null:defect.equals("public-key")?"test_public-fixture":"test_secret-fixture",
            defect.equals("missing-code")?null:"synthetic-merchant-code",defect.equals("invalid-timeout")?Duration.ZERO:Duration.ofSeconds(30)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("synthetic-merchant-code");
    }
    @Test void secretsAndRecipientsAreRedacted(){
        var p=new BictorysPayoutProperties(true,"test_secret-fixture","synthetic-merchant-code",null);
        assertThat(p.toString()).doesNotContain("test_secret-fixture","synthetic-merchant-code");
        var request=new BictorysPayoutRequest(java.math.BigDecimal.TEN,"XOF","SN",
            new BictorysPayoutRequest.Customer("Private name","+221771234567","SN","fr-FR"),"refund","reason","reference",
            new BictorysPayoutRequest.Merchant("synthetic-merchant-code"));
        assertThat(request.toString()).doesNotContain("Private name","771234567","synthetic-merchant-code");
        assertThat(request.customerObject().toString()).contains("redacted");
        assertThat(request.merchant().toString()).contains("redacted");
        assertThat(provider().parse(evidence().getBytes()).toString()).doesNotContain("771234567");
    }
    @Test void outgoingSignedAmountAndRecipientAreNormalized(){
        var proof=provider().parse(evidence().getBytes());
        assertThat(proof.amount()).isEqualByComparingTo("500");assertThat(proof.recipientPhone()).isEqualTo("+221771234567");
        assertThat(proof.provider()).isEqualTo("BICTORYS");assertThat(proof.account()).isEqualTo("sandbox");
        assertThat(proof.status()).isEqualTo("SUCCEEDED");
    }
    @ParameterizedTest @ValueSource(strings={"direction","recipient","fraction","unknown-status","duplicate-json","timestamp"})
    void incompleteOrAmbiguousProofIsRejected(String defect){
        String body=switch(defect){
            case "direction"->evidence().replace("\"refund\"","\"payment\"");
            case "recipient"->evidence().replace("221771234567","221");
            case "fraction"->evidence().replace("-500","-500.5");
            case "unknown-status"->evidence().replace("succeeded","unknown");
            case "duplicate-json"->evidence().replace("\"amount\":-500","\"amount\":-500,\"amount\":-500");
            default->evidence().replace("2026-10-05T12:00:00Z","invalid");
        };
        assertThatThrownBy(()->provider().parse(body.getBytes())).isInstanceOf(BictorysException.class);
    }
    @Test void routingNeverTurnsAPayoutIntoACharge(){
        assertThat(provider().recognizes(evidence().getBytes())).isTrue();
        assertThat(provider().recognizes("{\"merchantReference\":\"GPR-incomplete\"}".getBytes())).isTrue();
        assertThat(provider().recognizes("{\"merchantReference\":\"GP-charge\",\"type\":\"payment\"}".getBytes())).isFalse();
        assertThatThrownBy(()->new BictorysWebhookParser().parse(evidence().getBytes())).isInstanceOf(BictorysException.class);
    }
}

