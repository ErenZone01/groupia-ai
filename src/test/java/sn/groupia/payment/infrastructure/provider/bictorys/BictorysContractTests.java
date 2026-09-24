package sn.groupia.payment.infrastructure.provider.bictorys;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.PaymentCheckoutContext;
import sn.groupia.payment.domain.service.*;
import static org.assertj.core.api.Assertions.*;

class BictorysContractTests {
    private BictorysHttpStub http;
    private BictorysPaymentProvider provider;
    private final BictorysWebhookParser parser=new BictorysWebhookParser();
    private final ProviderInstruction instruction=new ProviderInstruction(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
            PaymentPurpose.INITIAL_ORDER,"BICTORYS","sandbox","GP-contract","33e1c83b-7cb0-437b-bc50-a7a58e5660ad",
            UUID.randomUUID().toString(),new BigDecimal("5000"),"XOF",new PaymentCheckoutContext("GRP-000000000001","Fixture Customer","+221771234567",null));
    @BeforeEach void setup() throws Exception {
        http=new BictorysHttpStub();
        var properties=properties();
        provider=new BictorysPaymentProvider(new BictorysClient(BictorysClient.builder(properties).baseUrl(http.url()).build()),properties);
    }
    @AfterEach void close(){http.close();}
    private BictorysProperties properties(){return new BictorysProperties(true,null,"test_public-fixture","fixture-webhook-secret",Duration.ofSeconds(1),Duration.ofSeconds(2));}
    private byte[] fixture(String name)throws Exception{
        try(var in=getClass().getResourceAsStream("/bictorys/"+name+".json")){return Objects.requireNonNull(in).readAllBytes();}
    }
    private String body(String name)throws Exception{return new String(fixture(name),StandardCharsets.UTF_8);}

    @Test void disabledConfigurationStartsWithoutSecretsOrAdapter() {
        new ApplicationContextRunner().withUserConfiguration(BictorysConfiguration.class).run(c->{
            assertThat(c).hasNotFailed().doesNotHaveBean(PaymentProvider.class);
        });
    }
    @Test void activeConfigurationRegistersTheAdapter() {
        new ApplicationContextRunner().withUserConfiguration(BictorysConfiguration.class)
                .withPropertyValues("groupia.payment.bictorys.enabled=true","groupia.payment.bictorys.api-key=test_public-fixture",
                        "groupia.payment.bictorys.webhook-secret=fixture-webhook-secret")
                .run(c->assertThat(c).hasNotFailed().hasSingleBean(PaymentProvider.class));
    }
    @Test void enabledConfigurationRejectsMissingSecrets() {
        new ApplicationContextRunner().withUserConfiguration(BictorysConfiguration.class)
                .withPropertyValues("groupia.payment.bictorys.enabled=true").run(c->assertThat(c).hasFailed());
    }
    @ParameterizedTest @ValueSource(strings={"https://api.bictorys.com","http://api.test.bictorys.com","https://api.test.bictorys.com.evil.test","https://api.test.bictorys.com/path"})
    void onlySandboxIsAccepted(String url) {
        assertThatThrownBy(()->new BictorysProperties(true,java.net.URI.create(url),"test_public-fixture","secret",null,null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("test_public-fixture");
    }
    @Test void productionKeyIsRejected(){assertThatThrownBy(()->new BictorysProperties(true,null,"public-live","secret",null,null)).isInstanceOf(IllegalArgumentException.class);}
    @ParameterizedTest @ValueSource(ints={201,202})
    void hostedChargeUsesDocumentedFieldsAndParsesItsAcknowledgement(int code)throws Exception {
        http.reply(code,body("charge-"+code));
        var result=provider.initiate(instruction);
        var request=http.requests.getFirst();
        assertThat(request.method()).isEqualTo("POST");assertThat(request.path()).isEqualTo("/pay/v1/charges");
        assertThat(request.apiKey()).isEqualTo("test_public-fixture");
        var sent=parser.root(request.body().getBytes(StandardCharsets.UTF_8));
        assertThat(sent.path("amount").decimalValue()).isEqualByComparingTo("5000");
        assertThat(sent.path("currency").asText()).isEqualTo("XOF");
        assertThat(sent.path("country").asText()).isEqualTo("SN");
        assertThat(sent.path("merchantReference").asText()).isEqualTo("GP-contract");
        assertThat(sent.path("paymentReference").asText()).isEqualTo("GRP-000000000001");
        assertThat(sent.path("customerObject").path("phone").asText()).isEqualTo("+221771234567");
        assertThat(sent.has("payment_type")).isFalse();assertThat(sent.has("authorization")).isFalse();
        assertThat(sent.has("successRedirectUrl")).isFalse();
        assertThat(result.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.metadata().checkoutUrl()).startsWith("https://pay.bictorys.com/");
        if(code==202){assertThat(result.providerTransactionId()).isNull();assertThat(result.metadata().chargeId()).isNotNull();}
        else {assertThat(result.providerTransactionId()).isEqualTo(instruction.providerTransactionId());}
    }
    @Test void returnedMerchantReferenceIsNotReplacedByTheExpectedOne()throws Exception {
        http.reply(201,body("charge-201").replace("GP-contract","GP-other"));
        assertThat(provider.initiate(instruction).merchantReference()).isEqualTo("GP-other");
    }
    @Test void redirectUrlAloneIsNeverExposedAsCheckout()throws Exception {
        http.reply(201,"{\"transactionId\":\"tx-1\",\"redirectUrl\":\"https://merchant.example/success\"}");
        assertThat(provider.initiate(instruction).metadata().checkoutUrl()).isNull();
    }
    @Test void untrustedCheckoutUrlIsRejected()throws Exception {
        http.reply(202,body("charge-202").replace("pay.bictorys.com","evil.example"));
        assertThatThrownBy(()->provider.initiate(instruction)).hasMessage("BICTORYS_INVALID_CHECKOUT_URL");
    }
    @ParameterizedTest @CsvSource({"succeeded,SUCCEEDED","success,SUCCEEDED","pending,PENDING","processing,PENDING","failed,FAILED","cancelled,CANCELLED","authorized,PENDING","reversed,CREATED","unexpected,CREATED"})
    void statusesAreNormalizedWithoutInventingFinancialSuccess(String external,PaymentStatus internal)throws Exception {
        var observation=provider.parse(body("webhook-root").replace("succeeded",external).getBytes(StandardCharsets.UTF_8)).observation();
        assertThat(observation.status()).isEqualTo(internal);
        if(Set.of("authorized","reversed","unexpected").contains(external)){assertThat(observation.metadata().reviewReason()).isNotNull();}
    }
    @Test void historicalDataEnvelopeNeverGuessesMerchantReference()throws Exception {
        var event=provider.parse(fixture("webhook-data"));
        assertThat(event.providerEventId()).isNull();assertThat(event.observation().providerTransactionId()).isEqualTo("932");
        assertThat(event.observation().merchantReference()).isNull();
        assertThat(event.observation().metadata().paymentReference()).isNull();
    }
    @Test void rootTransactionIdIsNotAProviderEventId()throws Exception {
        var event=provider.parse(fixture("webhook-root"));
        assertThat(event.providerEventId()).isNull();assertThat(event.observation().metadata().paymentReference()).isEqualTo("GRP-000000000001");
    }
    @Test void succeededWebhookKeepsValidIsoTimestamp()throws Exception {
        assertThat(provider.parse(fixture("webhook-root")).observation().occurredAt())
                .isEqualTo(java.time.Instant.parse("2026-03-01T12:05:00Z"));
    }
    @Test void succeededWebhookIgnoresUnparseableTimestampWithoutLoggingItsValue()throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(BictorysWebhookParser.class);
        var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();logger.addAppender(logs);
        try {
            var expected=provider.parse(fixture("webhook-root")).observation();
            var actual=provider.parse(invalidTimestamp(body("webhook-root"))).observation();
            assertThat(actual.occurredAt()).isNull();
            assertThat(actual).usingRecursiveComparison().ignoringFields("occurredAt").isEqualTo(expected);
            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.getFirst().getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(logs.list.getFirst().getThrowableProxy()).isNull();
            assertThat(logs.list.getFirst().getFormattedMessage()).isEqualTo(
                    "provider=BICTORYS code=BICTORYS_INVALID_TIMESTAMP action=IGNORED_FOR_AUTHENTICATED_SUCCESS");
            assertThat(logs.list.getFirst().getArgumentArray()).isNullOrEmpty();
        } finally {logger.detachAppender(logs);logs.stop();}
    }
    @ParameterizedTest @ValueSource(strings={"failed","cancelled","expired","pending","authorized","reversed","unexpected"})
    void otherStatusesStillRejectUnparseableTimestamp(String status)throws Exception {
        assertThatThrownBy(()->provider.parse(invalidTimestamp(body("webhook-root").replace("succeeded",status))))
                .hasMessage("BICTORYS_INVALID_TIMESTAMP");
    }
    @ParameterizedTest @ValueSource(strings={"id","amount","currency","status","paymentReference","merchantReference"})
    void ignoredTimestampDoesNotRelaxFinancialFieldTypes(String field)throws Exception {
        var node=(tools.jackson.databind.node.ObjectNode)parser.root(invalidTimestamp(body("webhook-root")));
        node.putObject(field).put("sensitive","never-coerce");
        assertThatThrownBy(()->provider.parse(node.toString().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BictorysException.class);
    }
    @Test void serverVerificationStillRejectsUnparseableTimestamp()throws Exception {
        http.reply(200,new String(invalidTimestamp(body("webhook-root")),StandardCharsets.UTF_8));
        assertThatThrownBy(()->provider.verify(instruction)).hasMessage("BICTORYS_INVALID_TIMESTAMP");
    }
    private byte[] invalidTimestamp(String body) {
        return body.replace("2026-03-01T12:05:00Z","private@example.test +221779999999 secret-value")
                .getBytes(StandardCharsets.UTF_8);
    }
    @ParameterizedTest @ValueSource(strings={"{}","[]","not-json","{\"data\":{}}","{\"id\":\"a\",\"id\":\"b\"}"})
    void undocumentedOrMalformedShapesAreRejected(String body) {
        assertThatThrownBy(()->provider.parse(body.getBytes(StandardCharsets.UTF_8))).isInstanceOf(BictorysException.class);
    }
    @ParameterizedTest @ValueSource(strings={"","wrong","fixture-webhook-secret"})
    void webhookSecretMustMatchExactly(String secret) {
        assertThat(provider.verifyAuthenticity(new byte[0],Map.of("x-secret-key",secret))).isEqualTo(secret.equals("fixture-webhook-secret"));
    }
    @Test void absentSecretIsRejected(){assertThat(provider.verifyAuthenticity(new byte[0],Map.of())).isFalse();}
    @Test void duplicateCaseInsensitiveSecretHeadersAreRejected(){assertThat(provider.verifyAuthenticity(new byte[0],Map.of("X-Secret-Key","fixture-webhook-secret","x-secret-key","wrong"))).isFalse();}
    @ParameterizedTest @ValueSource(ints={400,401,403,500})
    void httpFailuresAreSanitizedAndNeverRetried(int status) {
        http.reply(status,"<html>test_public-fixture personal details</html>");
        assertThatThrownBy(()->provider.initiate(instruction)).hasMessage("BICTORYS_HTTP_"+status).hasNoCause();
        assertThat(http.requests).hasSize(1);
    }
    @Test void nonJsonSuccessIsUncertain(){http.reply(201,"<html>gateway</html>");assertThatThrownBy(()->provider.initiate(instruction)).hasMessage("BICTORYS_INVALID_JSON");}
    @Test void timeoutIsUncertain(){
        var shortTimeout=new BictorysProperties(true,null,"test_public-fixture","fixture-webhook-secret",Duration.ofSeconds(1),Duration.ofMillis(150));
        var slowProvider=new BictorysPaymentProvider(new BictorysClient(BictorysClient.builder(shortTimeout).baseUrl(http.url()).build()),shortTimeout);
        http.delayed(201,"{}",600);assertThatThrownBy(()->slowProvider.initiate(instruction)).hasMessage("BICTORYS_TRANSPORT_UNCERTAIN");
    }
    @Test void interruptedConnectionIsUncertain(){http.disconnect();assertThatThrownBy(()->provider.initiate(instruction)).hasMessage("BICTORYS_TRANSPORT_UNCERTAIN");}
    @Test void completeStatusUsesExactlyTheTransactionsStatusEndpoint()throws Exception {
        http.reply(200,body("webhook-root"));
        assertThat(provider.verify(instruction).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(http.requests.getFirst().path()).isEqualTo("/pay/v1/transactions/"+instruction.providerTransactionId()+"/status");
        assertThat(http.requests.getFirst().apiKey()).isEqualTo("test_public-fixture");
    }
    @Test void statusOnlyIsSupplementedWithServerFinancialEvidence()throws Exception {
        http.reply(200,body("status-only"));http.reply(200,body("webhook-root"));
        assertThat(provider.verify(instruction).amount()).isEqualByComparingTo("5000");
        assertThat(http.requests.getLast().path()).isEqualTo("/pay/v1/transactions/"+instruction.providerTransactionId());
    }
    @Test void numericScaleDifferenceIsNotAContradiction()throws Exception {
        http.reply(200,"{\"status\":\"succeeded\",\"amount\":5000.00}");
        http.reply(200,body("webhook-root"));
        assertThat(provider.verify(instruction).amount()).isEqualByComparingTo("5000");
    }
    @Test void incompleteVerificationNeverCopiesExpectedAmounts()throws Exception {
        http.reply(200,body("status-only"));http.reply(200,body("status-only"));
        assertThatThrownBy(()->provider.verify(instruction)).hasMessage("BICTORYS_INCOMPLETE_EVIDENCE");
    }
    @Test void verificationMismatchIsPreservedForCoreValidation()throws Exception {
        http.reply(200,body("webhook-root").replace("5000","5001").replace("XOF","EUR"));
        var result=provider.verify(instruction);
        assertThat(result.amount()).isEqualByComparingTo("5001");assertThat(result.currency()).isEqualTo("EUR");
    }
    @Test void missingTransactionIdDoesNotGuessFromACharge() {
        assertThatThrownBy(()->provider.verify(instruction.withTransactionId(null))).hasMessage("BICTORYS_TRANSACTION_ID_REQUIRED");
        assertThat(http.requests).isEmpty();
    }
    @Test void sensitiveObjectsHaveRedactedStringRepresentations()throws Exception {
        assertThat(properties().toString()).doesNotContain("test_public-fixture","fixture-webhook-secret");
        assertThat(instruction.toString()).doesNotContain("+221","GP-contract");
        assertThat(provider.parse(fixture("webhook-root")).observation().toString()).doesNotContain("GP-contract");
    }
}
