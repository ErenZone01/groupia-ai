package sn.groupia;

import java.math.BigDecimal;
import org.slf4j.LoggerFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.event.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.api.event.PaymentRequestSettled;
import sn.groupia.payment.application.service.*;
import sn.groupia.payment.domain.service.*;
import sn.groupia.ordering.application.service.OrderExpiration;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="groupia.ordering.expiration.batch-size=1") @AutoConfigureMockMvc @Import({TestcontainersConfiguration.class,PaymentTestConfiguration.class,sn.groupia.payment.infrastructure.provider.bictorys.BictorysTestServerConfiguration.class}) @RecordApplicationEvents
class BictorysIntegrationTests {
    @Autowired sn.groupia.payment.infrastructure.provider.bictorys.BictorysHttpStub http;
    private final Map<UUID,String> providerIds=new HashMap<>();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    @Autowired PaymentTestConfiguration.SimulatedProvider provider;
    @Autowired PaymentTestConfiguration.MutablePaymentClock clock;
    @Autowired PaymentService payments;
    @Autowired PaymentOperations operations;
    @Autowired PaymentWebhooks webhooks;
    @Autowired PaymentTransactions paymentTransactions;
    @Autowired OrderExpiration expiration;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ApplicationEvents applicationEvents;
    @Autowired FailedEventPublications failedPublications;
    private static String passwordHash;
    private static final AtomicInteger addressSequence=new AtomicInteger();
    private UUID org;
    private UUID actor;
    private String jwt;
    private String remote;
    private JsonNode campaign;
    private JsonNode checkout;
    private String checkoutKey;

    @BeforeEach void setup() throws Exception {
        await(()->jdbc.queryForObject("select count(*) from event_publication where status in ('PUBLISHED','PROCESSING','RESUBMITTED')",Long.class)==0);
        jdbc.execute("TRUNCATE TABLE order_fulfillment_events, shipping_calculations, shipping_measurements, shipping_assessments, customer_access_sessions, otp_challenges, order_access_links, customer_access_limits, shipment_facts, shipments, supplier_order_anomalies, supplier_orders, payment_webhook_events,payments,payment_requests,order_items,orders,customers,grouping_changes,grouping_offers,grouping_campaigns,product_variants,products,organization_members,organizations,user_accounts,event_publication CASCADE");
        provider.reset(); clock.reset(); http.reset(); providerIds.clear(); remote="192.0.2."+addressSequence.incrementAndGet();
        if(passwordHash==null){passwordHash=passwords.encode("Financial fixture 2026!");}
        actor=UUID.randomUUID();
        jdbc.update("insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at) values (?, '+221771234567', ?, 'ACTIVE', current_timestamp,current_timestamp)",actor,passwordHash);
        jwt=jwt(actor);
        org=UUID.fromString(read(auth(post("/api/organizations"),Map.of("name","Finance")).andExpect(status().isCreated()).andReturn()).get("id").asText());
        var product=read(auth(post("/api/organizations/"+org+"/products"),Map.of("name","Article")).andExpect(status().isCreated()).andReturn());
        var base="/api/organizations/"+org+"/grouping-campaigns";
        campaign=read(auth(post(base),Map.of("title","Campagne","shippingTerms",Map.of("transportMode","AIR","shippingPricingMethod","FIXED",
                "shippingCollectionMode","AFTER_FINAL_ASSESSMENT","shippingRate",1000))).andExpect(status().isCreated()).andReturn());
        var path=base+"/"+campaign.get("id").asText();
        campaign=read(auth(post(path+"/offers"),Map.of("campaignVersion",campaign.get("version").asLong(),
                "productVariantId",product.get("variants").get(0).get("id").asText(),"unitPrice",1000)).andExpect(status().isOk()).andReturn());
        campaign=read(auth(post(path+"/open"),Map.of("version",campaign.get("version").asLong())).andExpect(status().isOk()).andReturn());
        checkoutKey=UUID.randomUUID().toString();
        checkout=read(place(checkoutKey).andExpect(status().isCreated()).andReturn());
    }

    private void queueCharge() throws Exception {
        var charge=UUID.randomUUID().toString();
        http.reply(202,json.writeValueAsString(Map.of("type","CheckoutLinkObject","chargeId",charge,
                "link","https://pay.bictorys.com/v1/charges/"+charge+"?op_token=fixture")));
    }
    private String event(UUID payment,String status) throws Exception {
        return json.writeValueAsString(Map.of("id",providerIds.get(payment),"type","payment","merchantReference",merchant(payment),
                "paymentReference",checkout.get("orderNumber").asText(),"amount",2300,"currency","XOF","status",status,"timestamp",clock.instant().toString()));
    }
    private ResultActions deliver(String payload) throws Exception {
        return mvc.perform(post("/api/payment/webhooks/bictorys").contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("X-Secret-Key","fixture-webhook-secret"));
    }

    @Test void hostedCheckoutUsesServerSnapshotsAndReplaysTheSamePrivateUrl() throws Exception {
        String key=key();queueCharge();
        var receipt=read(initiate(key).andExpect(status().isOk()).andReturn());
        var replay=read(initiate(key).andExpect(status().isOk()).andReturn());
        assertThat(replay.get("checkoutUrl")).isEqualTo(receipt.get("checkoutUrl"));
        assertThat(receipt.get("checkoutUrl").asText()).startsWith("https://pay.bictorys.com/");
        assertThat(http.requests).hasSize(1);
        var request=json.readTree(http.requests.getFirst().body());
        assertThat(request.get("paymentReference").asText()).isEqualTo(checkout.get("orderNumber").asText());
        assertThat(request.get("merchantReference").asText()).isEqualTo(jdbc.queryForObject("select merchant_reference from payments",String.class));
        assertThat(request.path("customerObject").path("name").asText()).isEqualTo("Awa Diop");
        assertThat(request.path("customerObject").path("phone").asText()).isEqualTo("+221771234567");
        assertThat(jdbc.queryForObject("select provider_transaction_id from payments",String.class)).isNull();
        assertThat(jdbc.queryForObject("select provider_charge_id from payments",String.class)).isNotBlank();
        assertThat(settlementCount()).isZero();assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT");
    }
    @ParameterizedTest @ValueSource(strings={"","wrong"})
    void missingOrWrongSecretCannotChangeMoney(String secret) throws Exception {
        var id=start();var payload=event(id,"succeeded");
        var request=post("/api/payment/webhooks/bictorys").contentType(MediaType.APPLICATION_JSON).content(payload);
        if(!secret.isEmpty()){request.header("X-Secret-Key",secret);}
        mvc.perform(request).andExpect(status().isUnauthorized());
        assertThat(paymentStatus(id)).isEqualTo("PENDING");assertThat(settlementCount()).isZero();
        assertThat(http.requests).hasSize(1);
    }
    @Test void authenticatedSuccessFromHostedChargeConfirmsAndDuplicateHasNoExtraEffect() throws Exception {
        var id=start();String body=event(id,"succeeded");
        deliver(body).andExpect(status().isAccepted());awaitConfirmed();
        deliver(body).andExpect(status().isAccepted());
        assertThat(settlementCount()).isEqualTo(1);assertThat(count("payment_webhook_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select provider_transaction_id from payments",String.class)).isEqualTo(providerIds.get(id));
        assertThat(http.requests).hasSize(1);
    }
    @ParameterizedTest @CsvSource({"amount,AMOUNT_MISMATCH","currency,CURRENCY_MISMATCH","paymentReference,PAYMENT_REFERENCE_MISMATCH"})
    void financialMismatchesAreDecidedByTheExistingCore(String field,String reason) throws Exception {
        var id=start();var node=(tools.jackson.databind.node.ObjectNode)json.readTree(event(id,"succeeded"));
        if(field.equals("amount")){node.put(field,2301);}else{node.put(field,field.equals("currency")?"EUR":"another-order");}
        deliver(json.writeValueAsString(node)).andExpect(status().isAccepted());
        assertThat(reason(id)).isEqualTo(reason);assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT");assertThat(settlementCount()).isZero();
    }
    @Test void unknownReferenceAndIncompleteHistoricalEnvelopeRequireReview() throws Exception {
        var id=start();deliver(event(id,"succeeded").replace(merchant(id),"GP-unknown")).andExpect(status().isAccepted());
        deliver("{\"event\":\"charge.successful\",\"data\":{\"id\":932,\"amount\":2300,\"currency\":\"XOF\",\"status\":\"réussi\",\"reference\":\"display-only\"}}").andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("select count(*) from payment_webhook_events where processing_status='REVIEW_REQUIRED'",Integer.class)).isEqualTo(2);
        assertThat(paymentStatus(id)).isEqualTo("PENDING");assertThat(settlementCount()).isZero();
    }
    @Test void reversedPreservesSuccessAndCannotBeErasedByDelayedFailureOrSuccess() throws Exception {
        var id=start();deliver(event(id,"succeeded")).andExpect(status().isAccepted());awaitConfirmed();
        clock.advance(Duration.ofSeconds(1));deliver(event(id,"reversed")).andExpect(status().isAccepted());
        assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED");assertThat(reason(id)).isEqualTo("PROVIDER_REVERSAL");
        deliver(event(id,"failed")).andExpect(status().isAccepted());
        clock.advance(Duration.ofSeconds(1));deliver(event(id,"succeeded")).andExpect(status().isAccepted());
        assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED");assertThat(reason(id)).isEqualTo("PROVIDER_REVERSAL");
        assertThat(settlementCount()).isEqualTo(1);assertThat(orderStatus()).isEqualTo("CONFIRMED");
    }
    @Test void authorizedDoesNotInventACapturedMobileMoneyPayment() throws Exception {
        var id=start();deliver(event(id,"authorized")).andExpect(status().isAccepted());
        assertThat(reason(id)).isEqualTo("AUTHORIZATION_NOT_CAPTURED");assertThat(settlementCount()).isZero();
        deliver(event(id,"succeeded")).andExpect(status().isAccepted());awaitConfirmed();
    }
    @Test void lateRealSuccessRemainsUnallocated() throws Exception {
        var id=start();clock.advance(Duration.ofMinutes(31));expiration.expireDue();
        deliver(event(id,"succeeded")).andExpect(status().isAccepted());
        assertThat(paymentStatus(id)).isEqualTo("PENDING");assertThat(reason(id)).isEqualTo("LATE_SUCCESS");
        assertThat(jdbc.queryForObject("select processing_status from payment_webhook_events",String.class)).isEqualTo("REVIEW_REQUIRED");
        assertThat(orderStatus()).isEqualTo("EXPIRED");assertThat(settlementCount()).isZero();
    }
    @Test void secondRealSuccessIsPreservedAsDuplicate() throws Exception {
        var first=start();deliver(event(first,"failed")).andExpect(status().isAccepted());
        var second=start();deliver(event(second,"succeeded")).andExpect(status().isAccepted());awaitConfirmed();
        deliver(event(first,"succeeded")).andExpect(status().isAccepted());
        assertThat(paymentStatus(first)).isEqualTo("FAILED");assertThat(reason(first)).isEqualTo("DUPLICATE_SUCCESS");
        assertThat(settlementCount()).isEqualTo(1);
    }
    @Test void providerTimeoutLeavesDurableClaimThatWebhookCanResolve() throws Exception {
        http.delayed(202,"{}",2600);
        var receipt=read(initiate(key()).andExpect(status().isOk()).andReturn());
        var id=UUID.fromString(receipt.get("id").asText());providerIds.put(id,UUID.randomUUID().toString());
        assertThat(paymentStatus(id)).isEqualTo("CREATED");assertThat(reason(id)).isEqualTo("PROVIDER_STATE_UNCERTAIN");
        deliver(event(id,"succeeded")).andExpect(status().isAccepted());awaitConfirmed();
    }
    @Test void unknownRemoteStatusNeverConfirms() throws Exception {
        var id=start();deliver(event(id,"mysterious")).andExpect(status().isAccepted());
        assertThat(reason(id)).isEqualTo("PROVIDER_STATUS_UNKNOWN");assertThat(settlementCount()).isZero();
    }
    @Test void pendingWebhookBeforeChargeAcknowledgementRetainsTransaction() throws Exception {
        var id=start();deliver(event(id,"pending")).andExpect(status().isAccepted());
        var instruction=paymentTransactions.reconciliationInstruction(id);
        var acknowledgement=new ProviderObservation("BICTORYS","sandbox",merchant(id),null,requestId(),orderId(),
                PaymentPurpose.INITIAL_ORDER,new BigDecimal("2300"),"XOF",PaymentStatus.PENDING,null,
                new ProviderMetadata(checkout.get("orderNumber").asText(),null,
                        jdbc.queryForObject("select provider_charge_id from payments",String.class),null));
        paymentTransactions.initiationResult(id,acknowledgement);
        assertThat(paymentTransactions.reconciliationInstruction(id).providerTransactionId()).isEqualTo(instruction.providerTransactionId());
        deliver(event(id,"succeeded")).andExpect(status().isAccepted());awaitConfirmed();
    }
    @Test void reversalBeforeSuccessRemainsVisibleAfterUncertainReconciliation() throws Exception {
        var id=start();deliver(event(id,"reversed")).andExpect(status().isAccepted());
        http.reply(500,"unavailable");payments.reconcile(id);
        assertThat(reason(id)).isEqualTo("PROVIDER_REVERSAL");
        deliver(event(id,"succeeded")).andExpect(status().isAccepted());
        assertThat(reason(id)).isEqualTo("PROVIDER_REVERSAL");assertThat(settlementCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"BICTORYS_INVALID_JSON","BICTORYS_UNKNOWN_ENVELOPE","BICTORYS_INCOMPLETE_EVIDENCE",
            "BICTORYS_UNSUPPORTED_EVENT","BICTORYS_INVALID_TIMESTAMP","BICTORYS_INVALID_FIELD","BICTORYS_INVALID_IDENTIFIER"})
    void authenticatedParsingFailureIsRetainedWithoutFinancialChanges(String code) throws Exception {
        var id=start();
        String beforePayment=jdbc.queryForObject("select row_to_json(p)::text from payments p where id=?",String.class,id);
        String beforeOrder=jdbc.queryForObject("select row_to_json(o)::text from orders o",String.class);
        var node=(tools.jackson.databind.node.ObjectNode)json.readTree(event(id,"succeeded"));
        node.putObject("customerObject").put("phone","+221779999999").put("email","private@example.test").put("secret","payload-sensitive-value");
        String payload;
        switch(code) {
            case "BICTORYS_INVALID_JSON" -> payload="{\"customerObject\":\"private@example.test\"";
            case "BICTORYS_UNKNOWN_ENVELOPE" -> payload=json.writeValueAsString(Map.of("event","undocumented","data",node));
            case "BICTORYS_INCOMPLETE_EVIDENCE" -> {node.put("amount","2300");payload=json.writeValueAsString(node);}
            case "BICTORYS_UNSUPPORTED_EVENT" -> {node.put("type","refund");payload=json.writeValueAsString(node);}
            case "BICTORYS_INVALID_TIMESTAMP" -> {node.put("status","failed");node.put("timestamp","private@example.test");payload=json.writeValueAsString(node);}
            case "BICTORYS_INVALID_FIELD" -> {node.putObject("currency").put("secret","payload-sensitive-value");payload=json.writeValueAsString(node);}
            case "BICTORYS_INVALID_IDENTIFIER" -> {node.put("id","+221779999999");payload=json.writeValueAsString(node);}
            default -> throw new AssertionError(code);
        }
        try(var logs=new ParsingLogs()) {
            deliver(payload).andExpect(status().isAccepted());
            var receipt=jdbc.queryForMap("select * from payment_webhook_events");
            assertThat(receipt.get("authenticity_verification_result")).isEqualTo("VALID");
            assertThat(receipt.get("processing_status")).isEqualTo("REVIEW_REQUIRED");
            assertThat(receipt.get("last_error_code")).isEqualTo(code);
            assertThat(receipt.get("observation")).isNull();assertThat(receipt.get("payment_id")).isNull();
            assertThat(receipt.get("next_attempt_at")).isNull();
            assertThat(logs.events()).hasSize(1);
            var logged=logs.events().getFirst();
            assertThat(logged.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(logged.getThrowableProxy()).isNull();
            assertThat(logged.getFormattedMessage()).contains("provider=BICTORYS","receiptId="+receipt.get("id"),"code="+code)
                    .doesNotContain("private@example.test","+221779999999","payload-sensitive-value","customerObject","fixture-webhook-secret",
                            merchant(id),checkout.get("orderNumber").asText(),providerIds.get(id));
            deliver(payload).andExpect(status().isAccepted());
            webhooks.retryPending();
            assertThat(count("payment_webhook_events")).isEqualTo(1);
            assertThat(jdbc.queryForObject("select last_error_code from payment_webhook_events",String.class)).isEqualTo(code);
            assertThat(jdbc.queryForObject("select attempt_count from payment_webhook_events",Integer.class)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select row_to_json(p)::text from payments p where id=?",String.class,id)).isEqualTo(beforePayment);
        assertThat(jdbc.queryForObject("select row_to_json(o)::text from orders o",String.class)).isEqualTo(beforeOrder);
        assertThat(settlementCount()).isZero();assertThat(http.requests).hasSize(1);
    }
    @Test void invalidSecretIsNotParsedOrLoggedAndValidPayloadStillConfirms() throws Exception {
        var id=start();
        try(var logs=new ParsingLogs()) {
            mvc.perform(post("/api/payment/webhooks/bictorys").contentType(MediaType.APPLICATION_JSON)
                    .content("{private@example.test").header("X-Secret-Key","wrong-secret"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("PAYMENT_AUTHENTICITY_FAILED"));
            assertThat(jdbc.queryForObject("select last_error_code from payment_webhook_events",String.class)).isEqualTo("PAYMENT_AUTHENTICITY_FAILED");
            assertThat(paymentStatus(id)).isEqualTo("PENDING");
            assertThat(logs.events()).isEmpty();
            deliver(event(id,"succeeded")).andExpect(status().isAccepted());awaitConfirmed();
            assertThat(logs.events()).isEmpty();
        }
    }
    @Test void redeliveryClarifiesLegacyNullObservationWithoutResolvingPayment() throws Exception {
        var id=start();
        String payload=event(id,"succeeded").replace("\"amount\":2300","\"amount\":\"2300\"");
        deliver(payload).andExpect(status().isAccepted());
        UUID receipt=jdbc.queryForObject("select id from payment_webhook_events",UUID.class);
        jdbc.update("update payment_webhook_events set last_error_code='UNKNOWN_REFERENCE' where id=?",receipt);
        deliver(payload).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("select id from payment_webhook_events",UUID.class)).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("select last_error_code from payment_webhook_events",String.class)).isEqualTo("BICTORYS_INCOMPLETE_EVIDENCE");
        assertThat(jdbc.queryForObject("select payment_id from payment_webhook_events",UUID.class)).isNull();
        assertThat(paymentStatus(id)).isEqualTo("PENDING");assertThat(settlementCount()).isZero();
    }
    @ParameterizedTest
    @ValueSource(strings={"httpFailure","pending","id","merchantReference","paymentReference","amount","currency"})
    void manualReconciliationCannotSettleWithoutConsistentServerSuccess(String scenario) throws Exception {
        var id=startWithKnownTransaction();
        http.reply(500,"unavailable");payments.reconcile(id);
        assertThat(payments.reconciliationQueue(100)).contains(id);
        var proof=(tools.jackson.databind.node.ObjectNode)json.readTree(event(id,"succeeded"));
        switch(scenario) {
            case "pending" -> proof.put("status","pending");
            case "id" -> proof.put("id","different-transaction");
            case "merchantReference" -> proof.put("merchantReference","GP-other");
            case "paymentReference" -> proof.put("paymentReference","GRP-other");
            case "amount" -> proof.put("amount",2301);
            case "currency" -> proof.put("currency","EUR");
            case "httpFailure" -> { }
            default -> throw new AssertionError(scenario);
        }
        http.reply(scenario.equals("httpFailure")?500:200,json.writeValueAsString(proof));
        payments.reconcile(id);
        assertThat(http.requests).hasSize(3);
        assertThat(http.requests.getLast().method()).isEqualTo("GET");
        assertThat(http.requests.getLast().path()).isEqualTo("/pay/v1/transactions/"+providerIds.get(id)+"/status");
        assertThat(paymentStatus(id)).isEqualTo("PENDING");
        String expectedReason=switch(scenario) {
            case "pending" -> null;
            case "merchantReference" -> "UNKNOWN_REFERENCE";
            case "paymentReference" -> "PAYMENT_REFERENCE_MISMATCH";
            case "amount" -> "AMOUNT_MISMATCH";
            case "currency" -> "CURRENCY_MISMATCH";
            default -> "PROVIDER_STATE_UNCERTAIN";
        };
        assertThat(reason(id)).isEqualTo(expectedReason);
        assertThat(jdbc.queryForObject("select acceptance_status from payment_requests",String.class)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select settled_payment_id from payment_requests",UUID.class)).isNull();
        assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT");assertThat(settlementCount()).isZero();
        assertThat(count("payment_webhook_events")).isZero();
    }
    @Test void authenticatedWebhookSettlesDespitePendingStatusAndDuplicateIsIdempotent() throws Exception {
        var id=start();String payload=successWithInvalidTimestamp(id);
        http.reply(200,"{\"id\":null,\"status\":\"pending\"}");
        deliver(payload).andExpect(status().isAccepted());awaitConfirmed();
        assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED");assertThat(reason(id)).isNull();
        assertThat(jdbc.queryForObject("select acceptance_status from payment_requests",String.class)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("select settled_payment_id from payment_requests",UUID.class)).isEqualTo(id);
        assertThat(jdbc.queryForObject("select observation->>'occurredAt' from payment_webhook_events",String.class)).isNull();
        assertThat(jdbc.queryForObject("select processing_status from payment_webhook_events",String.class)).isEqualTo("PROCESSED");
        assertThat(settlementCount()).isEqualTo(1);
        assertThat(applicationEvents.stream(PaymentRequestSettled.class)).hasSize(1);
        deliver(payload).andExpect(status().isAccepted());
        webhooks.retryPending();
        assertThat(count("payment_webhook_events")).isEqualTo(1);assertThat(settlementCount()).isEqualTo(1);
        assertThat(http.requests).hasSize(1);
        // A second success with a different fingerprint must also remain idempotent.
        var beforeOrder=jdbc.queryForObject("select row_to_json(o)::text from orders o",String.class);
        clock.advance(Duration.ofSeconds(1));
        deliver(event(id,"succeeded")).andExpect(status().isAccepted());
        assertThat(settlementCount()).isEqualTo(1);
        assertThat(applicationEvents.stream(PaymentRequestSettled.class)).hasSize(1);
        assertThat(jdbc.queryForObject("select row_to_json(o)::text from orders o",String.class)).isEqualTo(beforeOrder);
        assertThat(http.requests).hasSize(1);
        assertThat(orderStatus()).isEqualTo("CONFIRMED");
    }
    @Test void ignoredTimestampCannotBypassAuthentication() throws Exception {
        var id=start();
        mvc.perform(post("/api/payment/webhooks/bictorys").contentType(MediaType.APPLICATION_JSON)
                .content(successWithInvalidTimestamp(id)).header("X-Secret-Key","wrong-secret"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("PAYMENT_AUTHENTICITY_FAILED"));
        assertThat(jdbc.queryForObject("select last_error_code from payment_webhook_events",String.class)).isEqualTo("PAYMENT_AUTHENTICITY_FAILED");
        assertThat(jdbc.queryForObject("select observation from payment_webhook_events",String.class)).isNull();
        assertThat(paymentStatus(id)).isEqualTo("PENDING");assertThat(settlementCount()).isZero();
        assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT");assertThat(http.requests).hasSize(1);
    }
    @Test void ignoredTimestampCannotResolveUnknownMerchantReference() throws Exception {
        var id=start();
        deliver(successWithInvalidTimestamp(id).replace(merchant(id),"GP-unknown")).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("select last_error_code from payment_webhook_events",String.class)).isEqualTo("UNKNOWN_REFERENCE");
        assertThat(jdbc.queryForObject("select processing_status from payment_webhook_events",String.class)).isEqualTo("REVIEW_REQUIRED");
        assertThat(paymentStatus(id)).isEqualTo("PENDING");assertThat(settlementCount()).isZero();
        assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT");assertThat(http.requests).hasSize(1);
    }
    private String successWithInvalidTimestamp(UUID id) throws Exception {
        var node=(tools.jackson.databind.node.ObjectNode)json.readTree(event(id,"succeeded"));
        node.put("timestamp","private@example.test +221779999999 secret-value");
        return json.writeValueAsString(node);
    }
    @ParameterizedTest
    @CsvSource({"id,BICTORYS_INCOMPLETE_EVIDENCE","merchantReference,UNKNOWN_REFERENCE",
            "paymentReference,PAYMENT_REFERENCE_MISMATCH","amount,BICTORYS_INCOMPLETE_EVIDENCE",
            "currency,BICTORYS_INCOMPLETE_EVIDENCE","status,BICTORYS_INCOMPLETE_EVIDENCE"})
    void missingRequiredEvidenceNeverSettles(String field,String expected) throws Exception {
        var id=start();var node=(tools.jackson.databind.node.ObjectNode)json.readTree(event(id,"succeeded"));
        node.remove(field);
        if(field.equals("paymentReference")){node.put("reference",checkout.get("orderNumber").asText());}
        deliver(json.writeValueAsString(node)).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("select processing_status from payment_webhook_events",String.class)).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("select last_error_code from payment_webhook_events",String.class)).isEqualTo(expected);
        assertThat(paymentStatus(id)).isEqualTo("PENDING");assertThat(settlementCount()).isZero();
        assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT");assertThat(http.requests).hasSize(1);
    }
    @Test void transactionAlreadyOwnedByAnotherAttemptRequiresReview() throws Exception {
        var first=start();deliver(event(first,"failed")).andExpect(status().isAccepted());
        var second=start();providerIds.put(second,providerIds.get(first));
        deliver(event(second,"succeeded")).andExpect(status().isAccepted());
        assertThat(paymentStatus(first)).isEqualTo("FAILED");assertThat(paymentStatus(second)).isEqualTo("PENDING");
        assertThat(reason(second)).isEqualTo("TRANSACTION_MISMATCH");assertThat(settlementCount()).isZero();
        assertThat(jdbc.queryForObject("select processing_status from payment_webhook_events where last_error_code='TRANSACTION_MISMATCH'",String.class))
                .isEqualTo("REVIEW_REQUIRED");
    }
    @Test void concurrentDuplicateWebhooksSettleOnce() throws Exception {
        var id=start();String payload=event(id,"succeeded");
        var results=race(()->deliver(payload).andReturn(),()->deliver(payload).andReturn());
        assertThat(results).allSatisfy(r->assertThat(r.getResponse().getStatus()).isEqualTo(202));
        awaitConfirmed();assertThat(settlementCount()).isEqualTo(1);assertThat(count("payment_webhook_events")).isEqualTo(1);
        assertThat(http.requests).hasSize(1);
    }
    @Test void concurrentTransactionReuseAcrossOrdersHasOnlyOneWinner() throws Exception {
        var first=start();String firstPayload=event(first,"succeeded");
        checkout=read(place(key()).andExpect(status().isCreated()).andReturn());
        var second=start();providerIds.put(second,providerIds.get(first));String secondPayload=event(second,"succeeded");
        var results=race(()->deliver(firstPayload).andReturn(),()->deliver(secondPayload).andReturn());
        assertThat(results).allSatisfy(r->assertThat(r.getResponse().getStatus()).isEqualTo(202));
        await(()->jdbc.queryForObject("select count(*) from orders where status='CONFIRMED'",Integer.class)==1);
        assertThat(jdbc.queryForObject("select count(*) from payments where status='SUCCEEDED'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payments where reconciliation_reason='TRANSACTION_MISMATCH'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment_requests where acceptance_status='CLOSED'",Integer.class)).isEqualTo(1);
        assertThat(settlementCount()).isEqualTo(1);assertThat(http.requests).hasSize(2);
    }
    @Test void cancelledRequestCannotBeSettledByWebhook() throws Exception {
        var id=start();new TransactionTemplate(transactions).executeWithoutResult(s->operations.cancelInitialAcceptance(org,orderId()));
        deliver(event(id,"succeeded")).andExpect(status().isAccepted());
        assertThat(paymentStatus(id)).isEqualTo("PENDING");assertThat(reason(id)).isEqualTo("LATE_SUCCESS");
        assertThat(jdbc.queryForObject("select processing_status from payment_webhook_events",String.class)).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("select acceptance_status from payment_requests",String.class)).isEqualTo("CANCELLED");
        assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT");assertThat(settlementCount()).isZero();
    }
    @Test void uncertainPaymentCanStillBeReconciledWithoutWebhook() throws Exception {
        var id=startWithKnownTransaction();http.reply(500,"unavailable");payments.reconcile(id);
        assertThat(payments.reconciliationQueue(100)).contains(id);
        http.reply(200,event(id,"succeeded"));payments.reconcile(id);awaitConfirmed();
        assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED");assertThat(reason(id)).isNull();
        assertThat(jdbc.queryForObject("select settled_payment_id from payment_requests",UUID.class)).isEqualTo(id);
        assertThat(settlementCount()).isEqualTo(1);assertThat(count("payment_webhook_events")).isZero();
        assertThat(http.requests).hasSize(3);
    }
    @ParameterizedTest @ValueSource(strings={"receiptProvider","receiptAccount","observationProvider","observationAccount"})
    void financialCoreRejectsProviderOrAccountMismatch(String field) throws Exception {
        var id=start();
        var observation=new ProviderObservation(field.equals("observationProvider")?"OTHER":"BICTORYS",
                field.equals("observationAccount")?"other":"sandbox",merchant(id),providerIds.get(id),null,null,null,
                new BigDecimal("2300"),"XOF",PaymentStatus.SUCCEEDED,null,
                new ProviderMetadata(checkout.get("orderNumber").asText(),null,null,null));
        UUID receipt=paymentTransactions.receive(field.equals("receiptProvider")?"OTHER":"BICTORYS",
                field.equals("receiptAccount")?"other":"sandbox",null,"b".repeat(64),"a".repeat(64),true,observation,null);
        paymentTransactions.processWebhook(receipt,null,false);
        assertThat(jdbc.queryForObject("select last_error_code from payment_webhook_events",String.class)).isEqualTo("PROVIDER_MISMATCH");
        assertThat(jdbc.queryForObject("select processing_status from payment_webhook_events",String.class)).isEqualTo("REVIEW_REQUIRED");
        assertThat(paymentStatus(id)).isEqualTo("PENDING");assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(settlementCount()).isZero();
    }
    private UUID startWithKnownTransaction() throws Exception {
        String transaction=UUID.randomUUID().toString();
        http.reply(201,json.writeValueAsString(Map.of("transactionId",transaction,"link","https://pay.bictorys.com/fixture")));
        UUID id=UUID.fromString(read(initiate(key()).andExpect(status().isOk()).andReturn()).get("id").asText());
        providerIds.put(id,transaction);return id;
    }
    private static class ParsingLogs implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)LoggerFactory.getLogger(PaymentWebhooks.class);
        private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender=new ch.qos.logback.core.read.ListAppender<>();
        ParsingLogs(){appender.start();logger.addAppender(appender);}
        List<ch.qos.logback.classic.spi.ILoggingEvent> events(){return appender.list.stream().filter(e->e.getMessage().startsWith("Webhook parsing failed")).toList();}
        public void close(){logger.detachAppender(appender);appender.stop();}
    }


    private UUID start() throws Exception {
        queueCharge();
        UUID id=UUID.fromString(read(initiate(key()).andExpect(status().isOk()).andReturn()).get("id").asText());
        providerIds.put(id,UUID.randomUUID().toString());return id;
    }
    private UUID orderId(){return jdbc.queryForObject("select id from orders",UUID.class);}
    private UUID requestId(){return jdbc.queryForObject("select id from payment_requests",UUID.class);}
    private String merchant(UUID id){return jdbc.queryForObject("select merchant_reference from payments where id=?",String.class,id);}
    private String paymentStatus(UUID id){return jdbc.queryForObject("select status from payments where id=?",String.class,id);}
    private String reason(UUID id){return jdbc.queryForObject("select reconciliation_reason from payments where id=?",String.class,id);}
    private String orderStatus(){return jdbc.queryForObject("select status from orders",String.class);}
    private long settlementCount(){return jdbc.queryForObject("select count(*) from event_publication where event_type like '%PaymentRequestSettled' and listener_id like '%OrderSettlementListener%'",Long.class);}
    private long count(String table){return jdbc.queryForObject("select count(*) from "+table,Long.class);}
    private String key(){return UUID.randomUUID().toString();}
    private String secret(){return checkout.get("trackingToken").asText();}
    private String paymentPath(){return "/api/public/orders/"+checkout.get("orderNumber").asText()+"/payments";}
    private ResultActions initiate(String key) throws Exception {
        return mvc.perform(body(post(paymentPath()),Map.of("provider","bictorys")).header("X-Order-Tracking-Token",secret())
                .header("Idempotency-Key",key).with(r->{r.setRemoteAddr(remote);return r;}));
    }
    private ResultActions place(String key) throws Exception {
        return mvc.perform(body(post("/api/public/groupings/"+campaign.get("publicIdentifier").asText()+"/checkout"),
                Map.of("contact",Map.of("firstName","Awa","lastName","Diop","phone","771234567"),
                        "lines",List.of(Map.of("offerId",campaign.get("offers").get(0).get("id").asText(),
                                "quantity",2,"expectedUnitPrice",1000,"expectedPriceRevision",0)))).header("Idempotency-Key",key));
    }
    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request,Object value){return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(value));}
    private ResultActions auth(MockHttpServletRequestBuilder request)throws Exception{return mvc.perform(request.header("Authorization","Bearer "+jwt));}
    private ResultActions auth(MockHttpServletRequestBuilder request,Object value)throws Exception{return auth(body(request,value));}
    private JsonNode read(MvcResult result)throws Exception{return json.readTree(result.getResponse().getContentAsString());}
    private String jwt(UUID id){
        var claims=JwtClaimsSet.builder().issuer("https://groupia.test").audience(List.of("groupia-api-test")).subject(id.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(1800)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    private void awaitConfirmed()throws Exception{await(()->orderStatus().equals("CONFIRMED"));}
    private void await(BooleanSupplier condition)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline){Thread.sleep(20);}
        assertThat(condition.getAsBoolean()).isTrue();
    }
    private List<MvcResult> race(Callable<MvcResult> a,Callable<MvcResult> b)throws Exception{
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var first=executor.submit(()->{start.await();return a.call();});var second=executor.submit(()->{start.await();return b.call();});
            start.countDown();return List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        }
    }
}
