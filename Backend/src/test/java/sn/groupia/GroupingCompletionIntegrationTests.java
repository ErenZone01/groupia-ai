package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.application.service.PaymentTransactions;
import sn.groupia.payment.domain.model.*;
import sn.groupia.payment.domain.repository.*;
import sn.groupia.payment.domain.service.*;
import tools.jackson.databind.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PaymentTestConfiguration.class})
class GroupingCompletionIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired PlatformTransactionManager manager;
    @Autowired PaymentTransactions financial;
    @Autowired FinancialStore store;
    @Autowired PaymentReviewStore reviews;
    @Autowired RefundStore refunds;
    @Autowired PaymentOperations operations;
    @Autowired PaymentTestConfiguration.MutablePaymentClock clock;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean(
            name = "groupingPersistence")
    sn.groupia.grouping.domain.repository.GroupingChanges changes;

    MerchantSettlementFixtures.Fixture f;
    UUID initial;
    String token;

    @BeforeEach
    void setup() {
        f = MerchantSettlementFixtures.seed(jdbc);
        initial = MerchantSettlementFixtures.settled(jdbc, f, "INITIAL_ORDER");
        jdbc.update(
                "update grouping_campaigns set status='DISTRIBUTING',closed_at=now() where id=?",
                f.campaign());
        clock.reset();
        clock.advance(Duration.ofMinutes(1));
        token = token(f.actor());
    }

    void completed() {
        MerchantSettlementFixtures.refund(jdbc, f, "MANUAL_EXTERNAL");
        jdbc.update(
                "update orders set status='COMPLETED',completed_at=now() where id=?", f.order());
    }

    String base() {
        return "/api/organizations/" + f.org() + "/grouping-campaigns/" + f.campaign();
    }

    ResultActions postComplete(Object body) throws Exception {
        return mvc.perform(
                post(base() + "/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)));
    }

    ResultActions complete() throws Exception {
        return postComplete(Map.of("version", 0, "confirmed", true));
    }

    JsonNode diagnostic() throws Exception {
        return json.readTree(
                mvc.perform(get(base() + "/completion").header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    void blocked(String reason) throws Exception {
        assertThat(diagnostic().path("eligible").asBoolean()).isFalse();
        assertThat(diagnostic().toString()).contains(reason);
        complete().andExpect(status().isConflict());
    }

    @Test
    void completesWithoutAnyFinancialMutationAndIsTerminal() throws Exception {
        completed();
        var before = snapshot();
        assertThat(diagnostic().path("eligible").asBoolean()).isTrue();
        complete()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
        assertThat(snapshot()).isEqualTo(before);
        assertThat(history()).isEqualTo(1);
        mvc.perform(get(base() + "/merchant-settlement").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        complete().andExpect(status().isConflict());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update grouping_campaigns set status='DISTRIBUTING' where id=?",
                                        f.campaign()))
                .hasStackTraceContaining("terminal");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update grouping_campaigns set completed_at=completed_at+interval '1 second' where id=?",
                                        f.campaign()))
                .hasStackTraceContaining("terminal");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "delete from grouping_changes where campaign_id=?",
                                        f.campaign()))
                .hasStackTraceContaining("immutable");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_READY", "READY_FOR_PICKUP"})
    void remainingPickupBlocks(String state) throws Exception {
        jdbc.update(
                "update orders set fulfillment_status=?,ready_for_pickup_at=case when ?='READY_FOR_PICKUP' then now() end where id=?",
                state,
                state,
                f.order());
        blocked("ORDER_NOT_COMPLETED");
    }

    @Test
    void missingFinalBlocks() throws Exception {
        blocked("SHIPPING_NOT_FINALIZED");
    }

    @Test
    void creditWithoutRefundBlocks() throws Exception {
        GroupingCompletionFixtures.shippingCredit(jdbc, f);
        blocked("SHIPPING_CREDIT_DUE");
    }

    @Test
    void balanceDueBlocks() throws Exception {
        GroupingCompletionFixtures.shippingCredit(jdbc, f, 6500);
        blocked("SHIPPING_BALANCE_DUE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "PENDING", "REVIEW_REQUIRED", "FAILED", "CANCELLED"})
    void unresolvedRefundBlocks(String state) throws Exception {
        var calculation = GroupingCompletionFixtures.shippingCredit(jdbc, f);
        var refund =
                new TransactionTemplate(manager)
                        .execute(
                                s ->
                                        refunds.create(
                                                new ReserveRefund(
                                                        f.org(),
                                                        f.order(),
                                                        calculation,
                                                        new BigDecimal("500"),
                                                        "XOF",
                                                        "+221771234567",
                                                        "Fixture",
                                                        f.actor(),
                                                        "BICTORYS_PAYOUT",
                                                        "WAVE",
                                                        null,
                                                        null),
                                                clock.instant()));
        jdbc.update(
                "update payment_refunds set status=?,reconciliation_required=?,reconciliation_reason=? where id=?",
                state,
                state.equals("REVIEW_REQUIRED"),
                state.equals("REVIEW_REQUIRED") ? "PROVIDER_STATE_UNCERTAIN" : null,
                refund.id());
        blocked("REFUND_UNRESOLVED");
    }

    @Test
    void successfulRefundWaitsForConsumerWithoutRepair() throws Exception {
        MerchantSettlementFixtures.refund(jdbc, f, "MANUAL_EXTERNAL");
        blocked("ORDER_NOT_COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "select status from orders where id=?", String.class, f.order()))
                .isEqualTo("CONFIRMED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "PENDING"})
    void dangerousAttemptBlocks(String state) throws Exception {
        completed();
        UUID p = extraPayment(initial, state);
        blocked("PAYMENT_IN_PROGRESS");
        assertThat(p).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"PROVIDER_REVERSAL", "PROVIDER_STATE_UNCERTAIN", "TRANSACTION_MISMATCH"})
    void anomaliesRemainBlocking(String reason) throws Exception {
        completed();
        jdbc.update(
                "update payments set reconciliation_required=true,reconciliation_reason=? where payment_request_id=?",
                reason,
                initial);
        blocked("PAYMENT_RECONCILIATION_REQUIRED");
    }

    @Test
    void openHistoricalShippingObligationBlocks() throws Exception {
        completed();
        shippingRequest();
        blocked("PAYMENT_OBLIGATION_OPEN");
    }

    @Test
    void unresolvedInitialIsNotHiddenByDistributionProjection() throws Exception {
        completed();
        var other = MerchantSettlementFixtures.seed(jdbc, f);
        jdbc.update("update orders set campaign_id=? where id=?", f.campaign(), other.order());
        blocked("ORDER_NOT_COMPLETED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPIRED", "CANCELLED"})
    void unpaidTerminalNotReadyIsCompatible(String state) throws Exception {
        completed();
        var other = MerchantSettlementFixtures.seed(jdbc, f);
        jdbc.update(
                "update orders set campaign_id=?,status=? where id=?",
                f.campaign(),
                state,
                other.order());
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        s ->
                                operations.createRequest(
                                        new CreatePaymentRequest(
                                                f.org(),
                                                other.order(),
                                                PaymentPurpose.INITIAL_ORDER,
                                                "INITIAL:" + other.order(),
                                                new BigDecimal("25300"),
                                                "XOF",
                                                new BigDecimal("20000"),
                                                new BigDecimal("300"),
                                                new BigDecimal("5000"),
                                                clock.instant().plusSeconds(600))));
        financial.cancel(f.org(), other.order());
        complete().andExpect(status().isOk());
    }

    @Test
    void cancelledPaidOrderBlocks() throws Exception {
        completed();
        jdbc.update("update orders set status='CANCELLED' where id=?", f.order());
        blocked("TERMINAL_ORDER_PAID");
    }

    @Test
    void emptyDistributionBlocks() throws Exception {
        var other = MerchantSettlementFixtures.seed(jdbc, f);
        jdbc.update("update orders set campaign_id=? where id=?", other.campaign(), f.order());
        blocked("GROUPING_COMPLETION_INCONSISTENT_STATE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "MANAGER", "VIEWER", "OPERATOR"})
    void permissions(String role) throws Exception {
        completed();
        jdbc.update(
                "update organization_members set role=? where organization_id=?", role, f.org());
        mvc.perform(get(base() + "/completion").header("Authorization", "Bearer " + token))
                .andExpect(status().is(role.equals("OPERATOR") ? 403 : 200));
        complete().andExpect(status().is(Set.of("OWNER", "MANAGER").contains(role) ? 200 : 403));
    }

    @Test
    void scopeAndInactiveOrganizationRejected() throws Exception {
        completed();
        var other = MerchantSettlementFixtures.seed(jdbc);
        token = token(other.actor());
        complete().andExpect(status().isForbidden());
        token = token(f.actor());
        jdbc.update("update organizations set status='ARCHIVED' where id=?", f.org());
        complete().andExpect(status().isNotFound());
    }

    @Test
    void confirmationAndVersionRequired() throws Exception {
        completed();
        postComplete(Map.of("version", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GROUPING_COMPLETION_CONFIRMATION_REQUIRED"));
        postComplete(Map.of("version", 0, "confirmed", false)).andExpect(status().isBadRequest());
        postComplete(Map.of("version", 7, "confirmed", true)).andExpect(status().isConflict());
    }

    @Test
    void transportRemainsReadableButWritesAreForbidden() throws Exception {
        completed();
        complete().andExpect(status().isOk());
        mvc.perform(
                        get(base() + "/shipping-assessments")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void duplicateClosuresAreConflictSafe() throws Exception {
        completed();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> task =
                    () -> {
                        start.await();
                        return complete().andReturn().getResponse().getStatus();
                    };
            var a = pool.submit(task);
            var b = pool.submit(task);
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(history()).isEqualTo(1);
    }

    @Test
    void journalFailureRollsBackCampaign() throws Exception {
        completed();
        jdbc.execute(
                "create function reject_completion_fixture() returns trigger language plpgsql as $$ begin if NEW.kind='COMPLETION_TRANSITION' then raise exception 'fixture journal failure'; end if; return NEW; end; $$");
        jdbc.execute(
                "create trigger reject_completion_fixture before insert on grouping_changes for each row execute function reject_completion_fixture()");
        try {
            complete().andExpect(status().is5xxServerError());
            assertThat(
                            jdbc.queryForObject(
                                    "select status from grouping_campaigns where id=?",
                                    String.class,
                                    f.campaign()))
                    .isEqualTo("DISTRIBUTING");
            assertThat(
                            jdbc.queryForObject(
                                    "select completed_at from grouping_campaigns where id=?",
                                    Object.class,
                                    f.campaign()))
                    .isNull();
        } finally {
            jdbc.execute("drop trigger reject_completion_fixture on grouping_changes");
            jdbc.execute("drop function reject_completion_fixture()");
        }
    }

    @Test
    void admittedWebhookIsSeenAfterWaitingForParentLock() throws Exception {
        completed();
        var admitted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var admission =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .execute(
                                                    s -> {
                                                        var id = receive();
                                                        admitted.countDown();
                                                        waitFor(release);
                                                        return id;
                                                    }));
            assertThat(admitted.await(10, TimeUnit.SECONDS)).isTrue();
            var closing = pool.submit(() -> complete().andReturn().getResponse().getStatus());
            try {
                assertThatThrownBy(() -> closing.get(250, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            admission.get(10, TimeUnit.SECONDS);
            assertThat(closing.get(10, TimeUnit.SECONDS)).isEqualTo(409);
            blocked("PAYMENT_RECEIPT_PENDING");
        }
    }

    @Test
    void lateVerifiedFinancialEventStillCreatesReviewWithoutReopening() throws Exception {
        completed();
        complete().andExpect(status().isOk());
        var observation = observation("TX-LATE-" + UUID.randomUUID());
        var receipt =
                financial.receive(
                        "SIMULATED",
                        "fixture",
                        null,
                        UUID.randomUUID().toString().replace("-", "").repeat(2),
                        "a".repeat(64),
                        true,
                        observation,
                        null);
        financial.processWebhook(receipt, observation, false);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment_reviews where payment_request_id=? and status='OPEN'",
                                Long.class,
                                initial))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select status from grouping_campaigns where id=?",
                                String.class,
                                f.campaign()))
                .isEqualTo("COMPLETED");
        assertThat(history()).isEqualTo(1);
    }

    @Test
    void resolvedDuplicateIsClassifiedPrecisely() throws Exception {
        completed();
        UUID secondary = extraPayment(initial, "SUCCEEDED");
        UUID review = duplicate(secondary);
        blocked("PAYMENT_REVIEW_OPEN");
        resolve(review);
        assertThat(diagnostic().path("eligible").asBoolean()).isTrue();
        jdbc.update(
                "update payments set reconciliation_required=true,reconciliation_reason='PROVIDER_REVERSAL' where id=?",
                secondary);
        blocked("PAYMENT_RECONCILIATION_REQUIRED");
    }

    @Test
    void resolvedInitialDuplicateUnblocksTransportAndDistribution() throws Exception {
        GroupingCompletionFixtures.shippingCredit(jdbc, f, 5000);
        jdbc.update(
                "update orders set fulfillment_status='NOT_READY',picked_up_at=null,ready_for_pickup_at=null where id=?",
                f.order());
        UUID secondary = extraPayment(initial, "SUCCEEDED");
        var review = duplicate(secondary);
        var before =
                json.readTree(
                        mvc.perform(
                                        get(base() + "/fulfillment")
                                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(before.path("orders").get(0).path("canMarkReady").asBoolean()).isFalse();
        assertThat(shippingInProgress()).isTrue();
        resolve(review);
        assertThat(shippingInProgress()).isFalse();
        fulfillment("ready-for-pickup").andExpect(status().isOk());
        fulfillment("picked-up").andExpect(status().isOk());
        complete().andExpect(status().isOk());
    }

    @Test
    void resolvingOnlyOneOfTwoReviewsDoesNotUnblock() throws Exception {
        completed();
        var b = extraPayment(initial, "SUCCEEDED");
        var c = extraPayment(initial, "SUCCEEDED");
        var rb = duplicate(b);
        var rc = duplicate(c);
        resolve(rb);
        blocked("PAYMENT_REVIEW_OPEN");
        assertThat(shippingInProgress()).isTrue();
        resolve(rc);
        assertThat(shippingInProgress()).isFalse();
        complete().andExpect(status().isOk());
    }

    @Test
    void twoAdditionalTransactionsOnPrincipalNeedBothResolutions() throws Exception {
        completed();
        for (int n = 0; n < 2; n++) {
            var observation = observation("TX-EXTRA-" + UUID.randomUUID());
            var receipt =
                    financial.receive(
                            "SIMULATED",
                            "fixture",
                            null,
                            UUID.randomUUID().toString().replace("-", "").repeat(2),
                            "a".repeat(64),
                            true,
                            observation,
                            null);
            financial.processWebhook(receipt, observation, false);
        }
        var ids =
                jdbc.query(
                        "select id from payment_reviews where payment_request_id=? order by id",
                        (rs, row) -> rs.getObject(1, UUID.class),
                        initial);
        assertThat(ids).hasSize(2);
        resolve(ids.get(0));
        blocked("PAYMENT_REVIEW_OPEN");
        resolve(ids.get(1));
        assertThat(shippingInProgress()).isFalse();
        complete().andExpect(status().isOk());
    }

    @Test
    void payoutPendingAndExpiredSubscriptionDoNotBlockOrChange() throws Exception {
        completed();
        UUID dest = UUID.randomUUID(), payout = UUID.randomUUID();
        jdbc.update(
                "insert into merchant_payout_destinations(id,organization_id,method,phone_e164,holder_name,status,created_at,updated_at,created_by,updated_by) values (?,?,'WAVE','+221771234567','Fixture','ACTIVE',now(),now(),?,?)",
                dest,
                f.org(),
                f.actor(),
                f.actor());
        jdbc.update(
                """
            insert into merchant_payouts(id,organization_id,campaign_id,amount,currency,status,destination_id,method,phone_e164,holder_name,provider,provider_account_reference,business_reference,provider_idempotency_key,requested_by,requested_at,reconciliation_required)
            values (?,?,?,1000,'XOF','PENDING',?,'WAVE','+221771234567','Fixture','BICTORYS','sandbox',?,?,?,now(),false)
            """,
                payout,
                f.org(),
                f.campaign(),
                dest,
                "PAYOUT:" + payout,
                UUID.randomUUID(),
                f.actor());
        jdbc.update(
                """
            insert into organization_subscriptions(id,organization_id,status,trial_started_at,trial_ends_at,trial_provenance,created_at,updated_at,billing_timezone)
            values (?,?,'SUSPENDED',now()-interval '60 days',now()-interval '30 days','ORGANIZATION_CREATED',now()-interval '60 days',now(),'Africa/Dakar')
            """,
                UUID.randomUUID(),
                f.org());
        var before = snapshot();
        complete().andExpect(status().isOk());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void lastPickupRacingCompletionNeverClosesBeforePickup() throws Exception {
        GroupingCompletionFixtures.shippingCredit(jdbc, f, 5000);
        jdbc.update(
                "update orders set fulfillment_status='READY_FOR_PICKUP',picked_up_at=null where id=?",
                f.order());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var pickup =
                    pool.submit(
                            () -> {
                                gate.await();
                                return fulfillment("picked-up")
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            var closing =
                    pool.submit(
                            () -> {
                                gate.await();
                                return complete().andReturn().getResponse().getStatus();
                            });
            gate.countDown();
            assertThat(pickup.get(15, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(closing.get(15, TimeUnit.SECONDS)).isIn(200, 409);
            assertThat(
                            jdbc.queryForObject(
                                    "select status from orders where id=?",
                                    String.class,
                                    f.order()))
                    .isEqualTo("COMPLETED");
            if (history() == 0)
                postComplete(
                                Map.of(
                                        "version",
                                        jdbc.queryForObject(
                                                "select version from grouping_campaigns where id=?",
                                                Long.class,
                                                f.campaign()),
                                        "confirmed",
                                        true))
                        .andExpect(status().isOk());
        }
        assertThat(history()).isEqualTo(1);
    }

    @Test
    void reviewResolutionRacingCompletionSeesCommittedResolution() throws Exception {
        completed();
        var review = duplicate(extraPayment(initial, "SUCCEEDED"));
        var changed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var resolution =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .execute(
                                                    s -> {
                                                        try {
                                                            resolve(review);
                                                        } catch (Exception e) {
                                                            throw new IllegalStateException(e);
                                                        }
                                                        changed.countDown();
                                                        waitFor(release);
                                                        return true;
                                                    }));
            assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
            var closing = pool.submit(() -> complete().andReturn().getResponse().getStatus());
            try {
                assertThatThrownBy(() -> closing.get(250, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            resolution.get(10, TimeUnit.SECONDS);
            assertThat(closing.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
    }

    @Test
    void refundSuccessRacingCompletionStillWaitsForOrderConsumer() throws Exception {
        var calc = GroupingCompletionFixtures.shippingCredit(jdbc, f);
        var refund =
                new TransactionTemplate(manager)
                        .execute(
                                s ->
                                        refunds.create(
                                                new ReserveRefund(
                                                        f.org(),
                                                        f.order(),
                                                        calc,
                                                        new BigDecimal("500"),
                                                        "XOF",
                                                        "+221771234567",
                                                        "Fixture",
                                                        f.actor(),
                                                        "BICTORYS_PAYOUT",
                                                        "WAVE",
                                                        null,
                                                        null),
                                                clock.instant()));
        var changed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var success =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .execute(
                                                    s -> {
                                                        refunds.lock(refund.id());
                                                        refunds.state(
                                                                refund.id(),
                                                                "SUCCEEDED",
                                                                false,
                                                                null,
                                                                "REFUND-" + refund.id(),
                                                                clock.instant(),
                                                                clock.instant());
                                                        changed.countDown();
                                                        waitFor(release);
                                                        return true;
                                                    }));
            assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
            var closing = pool.submit(() -> complete().andReturn().getResponse().getStatus());
            try {
                assertThatThrownBy(() -> closing.get(250, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            success.get(10, TimeUnit.SECONDS);
            assertThat(closing.get(10, TimeUnit.SECONDS)).isEqualTo(409);
            blocked("ORDER_NOT_COMPLETED");
        }
    }

    @Test
    void historicalReceiptAndUnrelatedReceiptDoNotBlock() throws Exception {
        completed();
        UUID received = receive();
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        s ->
                                store.lockWebhook(received)
                                        .finish("REVIEW_REQUIRED", "HISTORICAL", clock.instant()));
        financial.receive(
                "SIMULATED",
                "other-account",
                null,
                UUID.randomUUID().toString().replace("-", "").repeat(2),
                "a".repeat(64),
                true,
                observation(null),
                null);
        complete().andExpect(status().isOk());
    }

    @Test
    void terminalTransportRejectsFinalizationAndMeasurement() throws Exception {
        completed();
        complete().andExpect(status().isOk());
        mvc.perform(
                        post(base() + "/orders/" + f.order() + "/shipping-measurements")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"version\":1,\"information\":{\"actualWeightKg\":1,\"source\":\"MANAGER\"}}"))
                .andExpect(status().isConflict());
        mvc.perform(
                        post(base() + "/orders/" + f.order() + "/shipping-finalizations")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"version\":1,\"origin\":\"PROVIDER_QUOTED\",\"quotedAmount\":4500,\"currency\":\"XOF\",\"source\":\"MANAGER\",\"correctionReason\":\"fixture\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void webhookAdmittedAfterCompletionDecisionWaitsThenRemainsProcessable() throws Exception {
        completed();
        var decision = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            invocation.callRealMethod();
                            decision.countDown();
                            waitFor(release);
                            return null;
                        })
                .when(changes)
                .append(org.mockito.ArgumentMatchers.any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var closing = pool.submit(() -> complete().andReturn().getResponse().getStatus());
            assertThat(decision.await(10, TimeUnit.SECONDS)).isTrue();
            var admission = pool.submit(this::receive);
            try {
                assertThatThrownBy(() -> admission.get(250, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(closing.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            var id = admission.get(10, TimeUnit.SECONDS);
            financial.processWebhook(id, observation(null), false);
            assertThat(
                            jdbc.queryForObject(
                                    "select processing_status from payment_webhook_events where id=?",
                                    String.class,
                                    id))
                    .isEqualTo("PROCESSED");
            assertThat(
                            jdbc.queryForObject(
                                    "select status from grouping_campaigns where id=?",
                                    String.class,
                                    f.campaign()))
                    .isEqualTo("COMPLETED");
        }
    }

    @Test
    void shippingObligationCreatedUnderOrderLockCannotEscapeCompletion() throws Exception {
        completed();
        var created = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var creating =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .execute(
                                                    s -> {
                                                        jdbc.queryForList(
                                                                "select id from orders where id=? for update",
                                                                f.order());
                                                        var id = shippingRequest();
                                                        created.countDown();
                                                        waitFor(release);
                                                        return id;
                                                    }));
            assertThat(created.await(10, TimeUnit.SECONDS)).isTrue();
            var closing = pool.submit(() -> complete().andReturn().getResponse().getStatus());
            try {
                assertThatThrownBy(() -> closing.get(250, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            creating.get(10, TimeUnit.SECONDS);
            assertThat(closing.get(10, TimeUnit.SECONDS)).isEqualTo(409);
            blocked("PAYMENT_OBLIGATION_OPEN");
        }
    }

    boolean shippingInProgress() throws Exception {
        return json.readTree(
                        mvc.perform(
                                        get(base() + "/shipping-assessments")
                                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .get(0)
                .path("shippingSettlement")
                .path("paymentInProgress")
                .asBoolean();
    }

    ResultActions fulfillment(String action) throws Exception {
        return mvc.perform(
                post(base() + "/orders/" + f.order() + "/" + action)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                json.writeValueAsString(
                                        Map.of(
                                                "version",
                                                jdbc.queryForObject(
                                                        "select version from orders where id=?",
                                                        Long.class,
                                                        f.order()),
                                                "confirmed",
                                                true))));
    }

    UUID duplicate(UUID payment) {
        return new TransactionTemplate(manager)
                .execute(
                        s -> {
                            var r = store.lockRequest(initial);
                            var p = store.lockPayment(payment);
                            var review =
                                    PaymentReview.duplicate(
                                            r,
                                            p,
                                            new ProviderObservation(
                                                    p.getProvider(),
                                                    p.getProviderAccountReference(),
                                                    p.getMerchantReference(),
                                                    p.getProviderTransactionId(),
                                                    r.getId(),
                                                    r.getOrderId(),
                                                    r.getPurpose(),
                                                    r.getExpectedAmount(),
                                                    r.getCurrency(),
                                                    PaymentStatus.SUCCEEDED,
                                                    clock.instant()),
                                            clock.instant());
                            reviews.add(review);
                            reviews.flush();
                            return review.getId();
                        });
    }

    void resolve(UUID review) throws Exception {
        mvc.perform(
                        post("/api/organizations/"
                                        + f.org()
                                        + "/payment-reviews/"
                                        + review
                                        + "/resolve-refunded")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"externalRefundReference\":\"fixture-resolution\"}"))
                .andExpect(status().isOk());
    }

    UUID extraPayment(UUID request, String status) {
        UUID p = UUID.randomUUID();
        jdbc.update(
                """
        insert into payments(id,organization_id,payment_request_id,provider,provider_account_reference,merchant_reference,provider_transaction_id,provider_idempotency_key,initiation_key,expected_amount,currency,confirmed_amount,confirmed_currency,status,reconciliation_required,initiated_at,confirmed_at)
        select ?,organization_id,id,'SIMULATED','fixture',?,?,?, ?,expected_amount,currency,case when ?='SUCCEEDED' then expected_amount end,case when ?='SUCCEEDED' then currency end,?,false,now(),case when ?='SUCCEEDED' then now() end from payment_requests where id=?
        """,
                p,
                "GP-" + p,
                status.equals("SUCCEEDED") ? "TX-" + p : null,
                p.toString(),
                p.toString(),
                status,
                status,
                status,
                status,
                request);
        return p;
    }

    UUID shippingRequest() {
        return new TransactionTemplate(manager)
                .execute(
                        s ->
                                operations
                                        .createRequest(
                                                new CreatePaymentRequest(
                                                        f.org(),
                                                        f.order(),
                                                        PaymentPurpose.SHIPPING_BALANCE,
                                                        "SHIP:" + UUID.randomUUID(),
                                                        new BigDecimal("2000"),
                                                        "XOF",
                                                        BigDecimal.ZERO,
                                                        BigDecimal.ZERO,
                                                        new BigDecimal("2000"),
                                                        null))
                                        .id());
    }

    UUID receive() {
        return financial.receive(
                "SIMULATED",
                "fixture",
                null,
                UUID.randomUUID().toString().replace("-", "").repeat(2),
                "a".repeat(64),
                true,
                observation(null),
                null);
    }

    ProviderObservation observation(String tx) {
        var p =
                jdbc.queryForMap(
                        "select p.* from payments p join payment_requests r on r.settled_payment_id=p.id where r.id=?",
                        initial);
        return new ProviderObservation(
                "SIMULATED",
                "fixture",
                p.get("merchant_reference").toString(),
                tx == null ? p.get("provider_transaction_id").toString() : tx,
                initial,
                f.order(),
                PaymentPurpose.INITIAL_ORDER,
                new BigDecimal("25300"),
                "XOF",
                PaymentStatus.SUCCEEDED,
                clock.instant());
    }

    long history() {
        return jdbc.queryForObject(
                "select count(*) from grouping_changes where campaign_id=? and kind='COMPLETION_TRANSITION'",
                Long.class,
                f.campaign());
    }

    Map<String, Object> snapshot() {
        var result = new TreeMap<String, Object>();
        for (var table :
                List.of(
                        "payments",
                        "payment_requests",
                        "payment_refunds",
                        "payment_reviews",
                        "merchant_payouts",
                        "merchant_ledger_entries"))
            result.put(
                    table,
                    jdbc.queryForList(
                            "select * from " + table + " where organization_id=? order by id",
                            f.org()));
        return result;
    }

    static void waitFor(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    String token(UUID actor) {
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(),
                                JwtClaimsSet.builder()
                                        .issuer("https://groupia.test")
                                        .audience(List.of("groupia-api-test"))
                                        .subject(actor.toString())
                                        .issuedAt(Instant.now())
                                        .expiresAt(Instant.now().plusSeconds(1800))
                                        .build()))
                .getTokenValue();
    }
}
