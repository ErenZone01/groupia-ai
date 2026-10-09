package sn.groupia;

import java.math.BigDecimal;
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

@SpringBootTest(properties="groupia.ordering.expiration.batch-size=1") @AutoConfigureMockMvc @Import({TestcontainersConfiguration.class,PaymentTestConfiguration.class}) @RecordApplicationEvents
class PaymentIntegrationTests {
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
        provider.reset(); clock.reset(); remote="192.0.2."+addressSequence.incrementAndGet();
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
        await(()->jdbc.queryForObject("select count(*) from event_publication where status in ('PUBLISHED','PROCESSING','RESUBMITTED')",Long.class)==0);
    }

    @Test void checkoutCreatesOneInitialObligationWithIdenticalDeadlineAndBreakdown() throws Exception {
        assertThat(count("payment_requests")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select expected_amount from payment_requests",BigDecimal.class)).isEqualByComparingTo("2300");
        assertThat(jdbc.queryForObject("select goods_amount from payment_requests",BigDecimal.class)).isEqualByComparingTo("2000");
        assertThat(jdbc.queryForObject("select fee_amount from payment_requests",BigDecimal.class)).isEqualByComparingTo("300");
        assertThat(jdbc.queryForObject("select shipping_amount from payment_requests",BigDecimal.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from orders o join payment_requests r on r.order_id=o.id where r.expires_at=o.payment_deadline and r.expected_amount=o.initial_amount_due",Long.class)).isEqualTo(1);
        place(checkoutKey).andExpect(status().isOk());
        assertThat(count("payment_requests")).isEqualTo(1); assertThat(count("payments")).isZero();
    }

    @Test void obligationFailureRollsBackEntireCheckout() throws Exception {
        jdbc.execute("alter table payment_requests add constraint test_request_failure check(false) not valid");
        try {
            place(UUID.randomUUID().toString()).andExpect(status().isInternalServerError());
            assertThat(count("orders")).isEqualTo(1); assertThat(count("customers")).isEqualTo(1);
            assertThat(count("order_items")).isEqualTo(1); assertThat(count("payment_requests")).isEqualTo(1);
        } finally { jdbc.execute("alter table payment_requests drop constraint test_request_failure"); }
    }

    @Test void creationBusinessReferenceIsIdempotentAndRejectsChangedTerms() {
        var request=operations.initialRequest(org,orderId());
        var input=new CreatePaymentRequest(org,orderId(),PaymentPurpose.INITIAL_ORDER,request.businessReference(),
                request.expectedAmount(),"XOF",request.goodsAmount(),request.feeAmount(),request.shippingAmount(),request.expiresAt());
        var tx=new TransactionTemplate(transactions);
        assertThat(tx.execute(s->operations.createRequest(input)).id()).isEqualTo(request.id());
        var changed=new CreatePaymentRequest(org,orderId(),PaymentPurpose.INITIAL_ORDER,request.businessReference(),
                new BigDecimal("2400"),"XOF",new BigDecimal("2100"),request.feeAmount(),request.shippingAmount(),request.expiresAt());
        assertThatThrownBy(()->tx.execute(s->operations.createRequest(changed))).hasMessageContaining("Référence");
        assertThat(count("payment_requests")).isEqualTo(1);
    }

    @Test void initiationRequiresPrivateTrackingAndOrderNumberMustMatch() throws Exception {
        mvc.perform(body(post(paymentPath()),Map.of("provider","SIMULATED")).header("Idempotency-Key",key()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_TRACKING_INVALID"));
        mvc.perform(body(post("/api/public/orders/GRP-000000000000/payments"),Map.of("provider","SIMULATED"))
                .header("X-Order-Tracking-Token",secret()).header("Idempotency-Key",key()))
                .andExpect(status().isNotFound());
        assertThat(count("payments")).isZero(); assertThat(provider.initiationCalls.get()).isZero();
    }

    @Test void pendingInitiationAndReplayMakeOnlyOneProviderCall() throws Exception {
        String key=key();
        var first=read(initiate(key).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING")).andReturn());
        var replay=read(initiate(key).andExpect(status().isOk()).andReturn());
        assertThat(first.get("id").asText()).isEqualTo(replay.get("id").asText());
        assertThat(provider.initiationCalls.get()).isEqualTo(1);
        assertThat(count("payments")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select merchant_reference from payments",String.class)).startsWith("GP-");
        assertThat(jdbc.queryForObject("select provider_idempotency_key from payments",String.class)).isNotEqualTo(key);
        initiate(key()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_INITIATION_CONFLICT"));
    }

    @ParameterizedTest @EnumSource(value=PaymentStatus.class,names={"FAILED","CANCELLED","EXPIRED"})
    void newKeyAllowsRetryOnlyAfterCertainTerminalFailure(PaymentStatus status) throws Exception {
        provider.initialStatus=status;
        initiate(key()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value(status.name()));
        provider.initialStatus=PaymentStatus.PENDING;
        initiate(key()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(count("payments")).isEqualTo(2); assertThat(provider.initiationCalls.get()).isEqualTo(2);
    }

    @Test void unavailableProviderAndMissingInitiationKeyDoNotCreateAttempts() throws Exception {
        mvc.perform(body(post(paymentPath()),Map.of("provider","UNCONFIGURED")).header("X-Order-Tracking-Token",secret())
                .header("Idempotency-Key",key())).andExpect(status().isServiceUnavailable());
        mvc.perform(body(post(paymentPath()),Map.of("provider","SIMULATED")).header("X-Order-Tracking-Token",secret()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(count("payments")).isZero();
    }

    @Test void timeoutRemainsUncertainAndCanBeReconciledWithoutAnotherDebit() throws Exception {
        provider.initiationTimeout=true;
        var receipt=read(initiate(key()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.reconciliationRequired").value(true)).andReturn());
        var id=UUID.fromString(receipt.get("id").asText());
        initiate(key()).andExpect(status().isConflict());
        assertThat(payments.reconciliationQueue(100)).contains(id);
        provider.status(merchant(id),PaymentStatus.SUCCEEDED);
        payments.reconcile(id);
        awaitConfirmed();
        assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED");
        assertThat(provider.initiationCalls.get()).isEqualTo(1);
        assertThat(provider.verificationCalls.get()).isEqualTo(1);
    }

    @Test void immediateProviderSuccessAlsoConfirmsThroughDurableEvent() throws Exception {
        provider.initialStatus=PaymentStatus.SUCCEEDED;
        initiate(key()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        awaitConfirmed(); assertThat(settlementCount()).isEqualTo(1);
    }

    @Test void invalidAuthenticityStoresOnlyFingerprintAndCannotPoisonValidEventId() throws Exception {
        var id=start(); var observation=provider.status(merchant(id),PaymentStatus.SUCCEEDED);
        var payload=json.writeValueAsBytes(new NormalizedWebhook("shared-id",observation));
        mvc.perform(post("/api/payment/webhooks/SIMULATED").contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("X-Test-Signature","invalid")).andExpect(status().isUnauthorized());
        assertThat(paymentStatus(id)).isEqualTo("PENDING");
        assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("select observation from payment_webhook_events",String.class)).isNull();
        assertThat(jdbc.queryForObject("select provider_event_id from payment_webhook_events",String.class)).isNull();
        webhook("shared-id",observation).andExpect(status().isAccepted());
        awaitConfirmed(); assertThat(count("payment_webhook_events")).isEqualTo(2);
    }

    @Test void validDuplicateWebhookSettlesExactlyOnce() throws Exception {
        var id=start(); var observation=provider.status(merchant(id),PaymentStatus.SUCCEEDED);
        webhook("event-1",observation).andExpect(status().isAccepted());
        webhook("event-1",observation).andExpect(status().isAccepted());
        awaitConfirmed();
        assertThat(count("payment_webhook_events")).isEqualTo(1);
        assertThat(settlementCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select settled_payment_id from payment_requests",UUID.class)).isEqualTo(id);
        assertThat(jdbc.queryForObject("select acceptance_status from payment_requests",String.class)).isEqualTo("CLOSED");
    }

    @Test void duplicateWithoutProviderEventIdStillHasNoAdditionalFinancialEffect() throws Exception {
        var id=start(); var observation=provider.status(merchant(id),PaymentStatus.SUCCEEDED);
        webhook(null,observation).andExpect(status().isAccepted()); webhook(null,observation).andExpect(status().isAccepted());
        webhook(null,copy(observation,null,null,null,null,null,clock.instant().plusSeconds(1))).andExpect(status().isAccepted());
        awaitConfirmed(); assertThat(count("payment_webhook_events")).isEqualTo(2); assertThat(settlementCount()).isEqualTo(1);
    }

    @Test void unknownReferenceIsDurableReviewWithoutChangingAnyPayment() throws Exception {
        var id=start(); var original=provider.status(merchant(id),PaymentStatus.SUCCEEDED);
        var unknown=copy(original,"GP-unknown",null,null,null,null,null);
        webhook("unknown",unknown).andExpect(status().isAccepted());
        assertThat(paymentStatus(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select processing_status from payment_webhook_events",String.class)).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("select payment_id from payment_webhook_events",UUID.class)).isNull();
        assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT");
    }

    @ParameterizedTest @CsvSource({"amount,AMOUNT_MISMATCH","currency,CURRENCY_MISMATCH","transaction,TRANSACTION_MISMATCH","target,TARGET_MISMATCH","provider,PROVIDER_MISMATCH"})
    void financialInconsistencyNeverConfirms(String kind,String reason) throws Exception {
        var id=start(); var o=provider.status(merchant(id),PaymentStatus.SUCCEEDED);
        var changed=switch(kind) {
            case "amount" -> copy(o,null,new BigDecimal("2301"),null,null,null,null);
            case "currency" -> copy(o,null,null,"EUR",null,null,null);
            case "transaction" -> copy(o,null,null,null,"TX-incoherent",null,null);
            case "target" -> new ProviderObservation(o.provider(),o.providerAccountReference(),o.merchantReference(),o.providerTransactionId(),
                    o.paymentRequestId(),UUID.randomUUID(),o.purpose(),o.amount(),o.currency(),o.status(),o.occurredAt());
            default -> new ProviderObservation("OTHER",o.providerAccountReference(),o.merchantReference(),o.providerTransactionId(),
                    o.paymentRequestId(),o.orderId(),o.purpose(),o.amount(),o.currency(),o.status(),o.occurredAt());
        };
        webhook("bad",changed).andExpect(status().isAccepted());
        assertThat(paymentStatus(id)).isEqualTo("PENDING");
        assertThat(reason(id)).isEqualTo(reason);
        assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT"); assertThat(settlementCount()).isZero();
        if(kind.equals("amount") || kind.equals("currency")) {
            payments.reconcile(id); awaitConfirmed(); assertThat(reason(id)).isNull();
        }
    }

    @ParameterizedTest @EnumSource(value=PaymentStatus.class,names={"PENDING","FAILED","CANCELLED","EXPIRED"})
    void delayedStatusNeverRegressesASuccess(PaymentStatus delayed) throws Exception {
        var id=start(); var success=provider.status(merchant(id),PaymentStatus.SUCCEEDED);
        webhook("success",success).andExpect(status().isAccepted()); awaitConfirmed();
        var stale=copy(success,null,null,null,null,delayed,clock.instant().minusSeconds(5));
        webhook("old-"+delayed,stale).andExpect(status().isAccepted());
        payments.reconcile(id);
        assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED"); assertThat(reason(id)).isNull();
        assertThat(settlementCount()).isEqualTo(1);
    }

    @Test void staleFailureDoesNotOverrideNewerPendingObservation() throws Exception {
        var id=start(); var o=provider.observations.get(merchant(id));
        webhook("stale",copy(o,null,null,null,null,PaymentStatus.FAILED,clock.instant().minusSeconds(1))).andExpect(status().isAccepted());
        assertThat(paymentStatus(id)).isEqualTo("PENDING");
    }

    @Test void successWebhookRequiresProviderVerificationAndCanRetryAfterTimeout() throws Exception {
        var id=start(); var success=provider.status(merchant(id),PaymentStatus.SUCCEEDED);
        provider.verificationTimeout=true;
        webhook("proof-delayed",success).andExpect(status().isAccepted());
        assertThat(paymentStatus(id)).isEqualTo("PENDING"); assertThat(reason(id)).isEqualTo("PROVIDER_STATE_UNCERTAIN");
        assertThat(jdbc.queryForObject("select processing_status from payment_webhook_events",String.class)).isEqualTo("RETRYABLE_FAILURE");
        provider.verificationTimeout=false; clock.advance(Duration.ofMinutes(2)); webhooks.retryPending();
        awaitConfirmed(); assertThat(jdbc.queryForObject("select processing_status from payment_webhook_events",String.class)).isEqualTo("PROCESSED");
    }

    @Test void repeatedProviderVerificationFailuresStopAtFiveAttemptsForReview() throws Exception {
        var id=start(); provider.verificationTimeout=true;
        webhook("poison",provider.status(merchant(id),PaymentStatus.SUCCEEDED)).andExpect(status().isAccepted());
        for(int i=0;i<6;i++){clock.advance(Duration.ofMinutes(6)); webhooks.retryPending();}
        assertThat(jdbc.queryForObject("select attempt_count from payment_webhook_events",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select processing_status from payment_webhook_events",String.class)).isEqualTo("REVIEW_REQUIRED");
        assertThat(provider.verificationCalls.get()).isEqualTo(5);
        assertThat(paymentStatus(id)).isEqualTo("PENDING");
    }

    @Test void orderExpirationClosesAcceptanceAndIsIdempotent() throws Exception {
        clock.advance(Duration.ofMinutes(30));
        assertThat(expiration.expireDue()).isEqualTo(1); assertThat(expiration.expireDue()).isZero();
        assertThat(orderStatus()).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select acceptance_status from payment_requests",String.class)).isEqualTo("CANCELLED");
        initiate(key()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_PAYMENT_EXPIRED"));
        assertThat(count("payments")).isZero();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void lateSuccessIsPreservedButNeverSettlesOrRevives(boolean schedulerRan) throws Exception {
        var id=start(); clock.advance(Duration.ofMinutes(31));
        if(schedulerRan){expiration.expireDue();}
        webhook("late",provider.status(merchant(id),PaymentStatus.SUCCEEDED)).andExpect(status().isAccepted());
        assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED"); assertThat(reason(id)).isEqualTo("LATE_SUCCESS");
        assertThat(jdbc.queryForObject("select settled_payment_id from payment_requests",UUID.class)).isNull();
        assertThat(settlementCount()).isZero();
        expiration.expireDue(); assertThat(orderStatus()).isEqualTo("EXPIRED");
        payments.reconcile(id); assertThat(reason(id)).isEqualTo("LATE_SUCCESS");
    }

    @Test void secondRealSuccessRemainsRecordedWithoutReplacingFirstSettlement() throws Exception {
        provider.initialStatus=PaymentStatus.FAILED; var a=start();
        provider.initialStatus=PaymentStatus.PENDING; var b=start();
        webhook("B",provider.status(merchant(b),PaymentStatus.SUCCEEDED)).andExpect(status().isAccepted()); awaitConfirmed();
        webhook("A",provider.status(merchant(a),PaymentStatus.SUCCEEDED)).andExpect(status().isAccepted());
        assertThat(paymentStatus(a)).isEqualTo("SUCCEEDED"); assertThat(paymentStatus(b)).isEqualTo("SUCCEEDED");
        assertThat(reason(a)).isEqualTo("DUPLICATE_SUCCESS");
        assertThat(jdbc.queryForObject("select settled_payment_id from payment_requests",UUID.class)).isEqualTo(b);
        assertThat(settlementCount()).isEqualTo(1);
    }

    @Test void duplicateSettlementDeliveryDoesNotConfirmTwice() throws Exception {
        var id=start(); webhook("settled",provider.status(merchant(id),PaymentStatus.SUCCEEDED)).andExpect(status().isAccepted()); awaitConfirmed();
        var event=applicationEvents.stream(PaymentRequestSettled.class).findFirst().orElseThrow();
        long version=jdbc.queryForObject("select version from orders",Long.class);
        String confirmed=jdbc.queryForObject("select confirmed_at::text from orders",String.class);
        new TransactionTemplate(transactions).executeWithoutResult(s->publisher.publishEvent(event));
        await(()->jdbc.queryForObject("select count(*) from event_publication where event_type like '%PaymentRequestSettled' and listener_id like '%OrderSettlementListener%' and completion_date is not null",Long.class)==2);
        assertThat(jdbc.queryForObject("select version from orders",Long.class)).isEqualTo(version);
        assertThat(jdbc.queryForObject("select confirmed_at::text from orders",String.class)).isEqualTo(confirmed);
        assertThat(jdbc.queryForObject("select count(*) from event_publication where event_type like '%OrderCommerciallyAccepted' and listener_id like '%LateAcceptanceListener%'",Long.class)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"CANCELLED","EXPIRED","COMPLETED"})
    void consumerNeverResurrectsIneligibleOrder(String status) throws Exception {
        var id=start();
        jdbc.update("update orders set status=?",status);
        webhook("ineligible",provider.status(merchant(id),PaymentStatus.SUCCEEDED)).andExpect(status().isAccepted());
        await(()->jdbc.queryForObject("select count(*) from event_publication where event_type like '%PaymentRequestSettled' and listener_id like '%OrderSettlementListener%' and completion_date is not null",Long.class)==1);
        assertThat(orderStatus()).isEqualTo(status);
        assertThat(jdbc.queryForObject("select confirmed_at from orders",String.class)).isNull();
    }

    @Test void failedConsumerDoesNotRollbackMoneyAndPublicationCanBeResubmitted() throws Exception {
        var id=start();
        jdbc.execute("alter table orders add constraint test_consumer_failure check(status<>'CONFIRMED')");
        try {
            webhook("durable",provider.status(merchant(id),PaymentStatus.SUCCEEDED)).andExpect(status().isAccepted());
            await(()->jdbc.queryForObject("select count(*) from event_publication where status='FAILED'",Long.class)==1);
            assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("select acceptance_status from payment_requests",String.class)).isEqualTo("CLOSED");
            assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT");
            clock.advance(Duration.ofHours(1)); assertThat(expiration.expireDue()).isZero();
        } finally { jdbc.execute("alter table orders drop constraint test_consumer_failure"); }
        failedPublications.resubmit(ResubmissionOptions.defaults().withMinAge(Duration.ZERO).withFilter(p->p.getCompletionAttempts()<5));
        awaitConfirmed();
        assertThat(jdbc.queryForObject("select completion_attempts from event_publication where event_type like '%PaymentRequestSettled' and listener_id like '%OrderSettlementListener%'",Integer.class)).isEqualTo(2);
    }

    @Test void publicationWriteFailureIsRecoverableFromDurableWebhook() throws Exception {
        var id=start();
        jdbc.execute("alter table event_publication add constraint test_publication_failure check(false) not valid");
        try {
            webhook("registry-failed",provider.status(merchant(id),PaymentStatus.SUCCEEDED)).andExpect(status().isInternalServerError());
            assertThat(paymentStatus(id)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select processing_status from payment_webhook_events",String.class)).isEqualTo("RETRYABLE_FAILURE");
        } finally { jdbc.execute("alter table event_publication drop constraint test_publication_failure"); }
        clock.advance(Duration.ofMinutes(2)); webhooks.retryPending(); awaitConfirmed();
    }

    @ParameterizedTest @ValueSource(strings={"OWNER","MANAGER","VIEWER","OPERATOR"})
    void financialReadPermissionIsCentralizedAndScoped(String role) throws Exception {
        var id=start(); jdbc.update("update organization_members set role=?",role);
        var action=auth(get("/api/organizations/"+org+"/payment-requests?size=1"));
        if(role.equals("OPERATOR")) { action.andExpect(status().isForbidden()); return; }
        action.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        auth(get("/api/organizations/"+org+"/payment-requests/"+requestId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts[0].id").value(id.toString()))
                .andExpect(jsonPath("$.attempts[0].providerIdempotencyKey").doesNotExist());
        auth(get("/api/organizations/"+UUID.randomUUID()+"/payment-requests/"+requestId())).andExpect(status().isForbidden());
        mvc.perform(get("/api/organizations/"+org+"/payment-requests")).andExpect(status().isUnauthorized());
    }

    @Test void memberOfTwoOrganizationsCannotReadFinanceUnderWrongOrganization() throws Exception {
        var other=read(auth(post("/api/organizations"),Map.of("name","Autre")).andExpect(status().isCreated()).andReturn()).get("id").asText();
        auth(get("/api/organizations/"+other+"/payment-requests/"+requestId())).andExpect(status().isNotFound());
        auth(get("/api/organizations/"+other+"/payment-requests")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test void concurrentSameKeyProducesOnlyOneExternalInitiation() throws Exception {
        String key=key();
        var results=race(()->initiate(key).andReturn(),()->initiate(key).andReturn());
        assertThat(results.stream().map(r->r.getResponse().getStatus())).containsOnly(200);
        assertThat(count("payments")).isEqualTo(1); assertThat(provider.initiationCalls.get()).isEqualTo(1);
    }

    @Test void concurrentDistinctKeysCannotAccidentallyCreateTwoPendingAttempts() throws Exception {
        var results=race(()->initiate(key()).andReturn(),()->initiate(key()).andReturn());
        assertThat(results.stream().map(r->r.getResponse().getStatus())).containsExactlyInAnyOrder(200,409);
        assertThat(count("payments")).isEqualTo(1);
    }

    @Test void twoConcurrentRealSuccessesAllocateExactlyOneSettlement() throws Exception {
        provider.initialStatus=PaymentStatus.FAILED; var a=start();
        provider.initialStatus=PaymentStatus.PENDING; var b=start();
        var oa=provider.status(merchant(a),PaymentStatus.SUCCEEDED); var ob=provider.status(merchant(b),PaymentStatus.SUCCEEDED);
        var results=race(()->webhook("parallel-a",oa).andReturn(),()->webhook("parallel-b",ob).andReturn());
        assertThat(results.stream().map(r->r.getResponse().getStatus())).containsOnly(202);
        awaitConfirmed();
        assertThat(jdbc.queryForObject("select count(*) from payments where status='SUCCEEDED'",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from payments where reconciliation_reason='DUPLICATE_SUCCESS'",Long.class)).isEqualTo(1);
        assertThat(settlementCount()).isEqualTo(1);
    }

    @Test void expirationAndReadCanCommitWhileProviderCallIsBlocked() throws Exception {
        provider.initialStatus=PaymentStatus.SUCCEEDED;
        provider.entered=new CountDownLatch(1); provider.release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=executor.submit(()->initiate(key()).andReturn());
            try {
                assertThat(provider.entered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(count("payments")).isEqualTo(1);
                mvc.perform(get("/api/public/orders/track").header("X-Order-Tracking-Token",secret())).andExpect(status().isOk());
                clock.advance(Duration.ofMinutes(31)); assertThat(expiration.expireDue()).isEqualTo(1);
            } finally { provider.release.countDown(); }
            assertThat(future.get(10,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(orderStatus()).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select status from payments",String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select reconciliation_reason from payments",String.class)).isEqualTo("LATE_SUCCESS");
    }

    @Test void postgresEnforcesFinancialIdentityAmountsAndSettlementOwnership() throws Exception {
        var id=start();
        for(String sql:List.of("update payment_requests set expected_amount=-1","update payment_requests set expected_amount=1",
                "update payment_requests set currency='EUR'","update payments set expected_amount=0.5",
                "update payments set status='SUCCEEDED'","update payments set organization_id=gen_random_uuid()",
                "update payment_requests set settled_payment_id=gen_random_uuid(),acceptance_status='CLOSED',closed_at=current_timestamp",
                "delete from orders","delete from payment_requests")) {
            assertThatThrownBy(()->jdbc.update(sql)).as(sql).isInstanceOf(DataIntegrityViolationException.class);
        }
        provider.initialStatus=PaymentStatus.FAILED;
        webhook("failure",provider.status(merchant(id),PaymentStatus.FAILED)).andExpect(status().isAccepted());
        var another=start();
        assertThatThrownBy(()->jdbc.update("update payments set merchant_reference=?",merchant(id))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update payments set provider_transaction_id=? where id=?","TX-"+id,another)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void duplicateWebhookDuringBackoffDoesNotSpendAnotherAttempt() throws Exception {
        var id=start(); var success=provider.status(merchant(id),PaymentStatus.SUCCEEDED);
        provider.verificationTimeout=true;
        webhook("backoff",success).andExpect(status().isAccepted());
        webhook("backoff",success).andExpect(status().isAccepted());
        assertThat(provider.verificationCalls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select attempt_count from payment_webhook_events",Integer.class)).isEqualTo(1);
    }

    @Test void immediateSuccessWithoutVerificationRemainsRecoverable() throws Exception {
        provider.initialStatus=PaymentStatus.SUCCEEDED; provider.verificationTimeout=true;
        var id=start();
        assertThat(paymentStatus(id)).isEqualTo("CREATED");
        assertThat(reason(id)).isEqualTo("PROVIDER_STATE_UNCERTAIN");
        assertThat(settlementCount()).isZero();
        provider.verificationTimeout=false; payments.reconcile(id); awaitConfirmed();
        assertThat(provider.initiationCalls.get()).isEqualTo(1);
    }

    @Test void concurrentIdenticalWebhooksHaveOneReceiptAndOneSettlement() throws Exception {
        var id=start(); var success=provider.status(merchant(id),PaymentStatus.SUCCEEDED);
        var results=race(()->webhook("same-event",success).andReturn(),()->webhook("same-event",success).andReturn());
        assertThat(results.stream().map(r->r.getResponse().getStatus())).containsOnly(202);
        awaitConfirmed(); assertThat(count("payment_webhook_events")).isEqualTo(1);
        assertThat(settlementCount()).isEqualTo(1);
    }

    @Test void parallelExpirationWorkersOnlyExpireOnce() throws Exception {
        clock.advance(Duration.ofMinutes(30));
        try(var executor=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=executor.submit(()->{start.await();return expiration.expireDue();});
            var b=executor.submit(()->{start.await();return expiration.expireDue();});
            start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS)+b.get(10,TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(orderStatus()).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select acceptance_status from payment_requests",String.class)).isEqualTo("CANCELLED");
    }

    @Test void settledOrderAwaitingConfirmationDoesNotStarveLaterExpirationPages() throws Exception {
        place(key()).andExpect(status().isCreated());
        var ids=jdbc.queryForList("select id from orders order by id",UUID.class);
        var paid=ids.getFirst(); var unpaid=ids.getLast();
        var attempt=payments.initiateInitial(org,paid,"SIMULATED",key()).id();
        jdbc.execute("alter table orders add constraint test_consumer_failure check(status<>'CONFIRMED')");
        try {
            webhook("blocked-first-page",provider.status(merchant(attempt),PaymentStatus.SUCCEEDED)).andExpect(status().isAccepted());
            await(()->jdbc.queryForObject("select count(*) from event_publication where status='FAILED'",Long.class)==1);
            clock.advance(Duration.ofMinutes(31));
            assertThat(expiration.expireDue()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,paid)).isEqualTo("PENDING_PAYMENT");
            assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,unpaid)).isEqualTo("EXPIRED");
        } finally { jdbc.execute("alter table orders drop constraint test_consumer_failure"); }
        failedPublications.resubmit(ResubmissionOptions.defaults().withMinAge(Duration.ZERO));
        await(()->jdbc.queryForObject("select status from orders where id=?",String.class,paid).equals("CONFIRMED"));
    }
    @Test void repeatedTransactionFailuresAlsoStopForInvestigation() throws Exception {
        var id=start(); var success=provider.status(merchant(id),PaymentStatus.SUCCEEDED);
        jdbc.execute("alter table event_publication add constraint test_publication_failure check(false) not valid");
        try {
            webhook("transaction-poison",success).andExpect(status().isInternalServerError());
            for(int i=0;i<4;i++){clock.advance(Duration.ofMinutes(5)); webhooks.retryPending();}
            assertThat(jdbc.queryForObject("select attempt_count from payment_webhook_events",Integer.class)).isEqualTo(5);
            assertThat(jdbc.queryForObject("select processing_status from payment_webhook_events",String.class)).isEqualTo("REVIEW_REQUIRED");
            assertThat(jdbc.queryForObject("select last_error_code from payment_webhook_events",String.class)).isEqualTo("PROCESSING_FAILURE");
            assertThat(settlementCount()).isZero();
            webhooks.retryPending(); assertThat(provider.verificationCalls.get()).isEqualTo(5);
        } finally { jdbc.execute("alter table event_publication drop constraint test_publication_failure"); }
        payments.reconcile(id); awaitConfirmed();
    }
    private ProviderObservation copy(ProviderObservation o,String merchant,BigDecimal amount,String currency,String tx,PaymentStatus status,Instant at) {
        return new ProviderObservation(o.provider(),o.providerAccountReference(),merchant==null?o.merchantReference():merchant,
                tx==null?o.providerTransactionId():tx,o.paymentRequestId(),o.orderId(),o.purpose(),amount==null?o.amount():amount,
                currency==null?o.currency():currency,status==null?o.status():status,at==null?o.occurredAt():at);
    }
    private UUID start() throws Exception { return UUID.fromString(read(initiate(key()).andExpect(status().isOk()).andReturn()).get("id").asText()); }
    private UUID orderId(){return jdbc.queryForObject("select id from orders",UUID.class);}
    private UUID requestId(){return jdbc.queryForObject("select id from payment_requests",UUID.class);}
    private String merchant(UUID id){return jdbc.queryForObject("select merchant_reference from payments where id=?",String.class,id);}
    private String paymentStatus(UUID id){return jdbc.queryForObject("select status from payments where id=?",String.class,id);}
    private String reason(UUID id){return jdbc.queryForObject("select reconciliation_reason from payments where id=?",String.class,id);}
    private String orderStatus(){return jdbc.queryForObject("select status from orders",String.class);}
    private long settlementCount(){return jdbc.queryForObject("select count(*) from event_publication where event_type like '%PaymentRequestSettled' and listener_id like '%OrderSettlementListener%'",Long.class);}
    private long count(String table){return jdbc.queryForObject("select count(*) from "+table,Long.class);}

    @Test void authenticUnclassifiedParseFailureHasSafeFallback() throws Exception {
        var id=start();
        byte[] payload="{\"private@example.test\":\"secret-value\"".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        webhooks.receive("SIMULATED",payload,Map.of("X-Test-Signature",provider.signature(payload)));
        assertThat(jdbc.queryForObject("select last_error_code from payment_webhook_events",String.class)).isEqualTo("WEBHOOK_PARSE_FAILED");
        assertThat(jdbc.queryForObject("select processing_status from payment_webhook_events",String.class)).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("select observation from payment_webhook_events",String.class)).isNull();
        assertThat(paymentStatus(id)).isEqualTo("PENDING");assertThat(settlementCount()).isZero();
    }


    private String key(){return UUID.randomUUID().toString();}
    private String secret(){return checkout.get("trackingToken").asText();}
    private String paymentPath(){return "/api/public/orders/"+checkout.get("orderNumber").asText()+"/payments";}
    private ResultActions initiate(String key) throws Exception {
        return mvc.perform(body(post(paymentPath()),Map.of("provider","SIMULATED")).header("X-Order-Tracking-Token",secret())
                .header("Idempotency-Key",key).with(r->{r.setRemoteAddr(remote);return r;}));
    }
    private ResultActions webhook(String eventId,ProviderObservation observation) throws Exception {
        byte[] payload=json.writeValueAsBytes(new NormalizedWebhook(eventId,observation));
        return mvc.perform(post("/api/payment/webhooks/SIMULATED").contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("X-Test-Signature",provider.signature(payload)));
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
