package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.api.event.*;
import sn.groupia.settlement.application.service.*;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;
import tools.jackson.databind.*;

@ActiveProfiles("test")
@SpringBootTest(
        properties = {
            // These historical 300 XOF financial fixtures must never inherit the local
            // production profile (or its live payment settings) from Backend/.env.
            "groupia.ordering.platform-fee-xof=0",
            "groupia.payment.saspay.enabled=false",
            "groupia.settlement.payouts.enabled=true",
            // Test fixtures use an isolated fake provider regardless of Backend/.env.
            // Never initialize a LIVE SasPay payout adapter in these regression tests.
            "groupia.settlement.payout-provider=BICTORYS"
        })
@AutoConfigureMockMvc
@Import({
    TestcontainersConfiguration.class,
    PaymentTestConfiguration.class,
    MerchantPayoutTestConfiguration.class
})
class MerchantSettlementIntegrationTests {
    @Autowired JdbcTemplate j;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired PaymentOperations payments;
    @Autowired sn.groupia.payment.application.service.PaymentService incoming;
    @Autowired PaymentTestConfiguration.SimulatedProvider paymentProvider;
    @Autowired PaymentTestConfiguration.MutablePaymentClock clock;
    @Autowired MerchantPayoutTestConfiguration.FakeMerchantProvider provider;
    @Autowired MerchantPayoutTransactions payouts;
    @Autowired ApplicationEventPublisher events;
    @Autowired FailedEventPublications failed;
    @Autowired PlatformTransactionManager tx;
    MerchantSettlementFixtures.Fixture f;
    String jwt;
    UUID requestId;

    @BeforeEach
    void setup() {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                j.queryForObject(
                                                        "select count(*) from event_publication where status in ('PUBLISHED','PROCESSING','RESUBMITTED')",
                                                        Integer.class))
                                        .isZero());
        j.execute(
                "TRUNCATE merchant_payout_pilot_bindings,merchant_payout_pilot_grants,merchant_payout_administrations,merchant_payout_resolution_incidents,merchant_payout_verifications,payout_status_notifications,merchant_payout_fee_quote_bindings,merchant_payout_fee_quotes,saspay_company_fee_events,merchant_payout_provider_fees,commercial_refund_events,commercial_refund_sources,commercial_refund_cases,marketplace_simulation_events,marketplace_simulated_provider_receipts,marketplace_simulations,provider_collections,direct_order_fulfillment_events,payment_fee_quotes,direct_order_verified_settlements,direct_shipping_fee_settings,direct_delivery_policies,direct_order_items,direct_orders,direct_offers,direct_storefronts,product_media,product_media_galleries,auth_refresh_tokens,auth_sessions,outgoing_provider_operations,payment_reviews,subscription_invoices,subscription_periods,subscription_billing_contacts,organization_subscriptions,merchant_ledger_entries,merchant_payouts,merchant_payout_destinations,refund_webhook_receipts,payment_refunds,order_fulfillment_events,shipping_calculations,shipping_measurements,shipping_assessments,customer_access_sessions,otp_challenges,order_access_links,customer_access_limits,shipment_facts,shipments,supplier_order_anomalies,supplier_orders,payment_webhook_events,payments,payment_requests,order_items,orders,customers,grouping_changes,grouping_offers,grouping_campaigns,product_variants,products,organization_members,organizations,user_accounts,event_publication");
        provider.reset();
        paymentProvider.reset();
        clock.reset();
        f = MerchantSettlementFixtures.seed(j);
        jwt = token(f.actor());
        requestId =
                new TransactionTemplate(tx)
                        .execute(
                                s ->
                                        payments.createRequest(
                                                        new CreatePaymentRequest(
                                                                f.org(),
                                                                f.order(),
                                                                PaymentPurpose.INITIAL_ORDER,
                                                                "INITIAL_ORDER:" + f.order(),
                                                                new BigDecimal("25300"),
                                                                "XOF",
                                                                new BigDecimal("20000"),
                                                                new BigDecimal("300"),
                                                                new BigDecimal("5000"),
                                                                clock.instant().plusSeconds(1800)))
                                                .id());
    }

    private String base() {
        return "/api/organizations/" + f.org();
    }

    private String payoutPath() {
        return base() + "/grouping-campaigns/" + f.campaign() + "/merchant-payouts";
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,false", "300,false", "0,true", "300,true"})
    void externalCommercialRefundPreservesSellerNetAndHoldsHistoricalFeeCompensation(
            int fee, boolean delayedLedger) throws Exception {
        // Adjust the synthetic, not-yet-paid fixture before any historical settlement exists.
        if (fee == 0) {
            j.update(
                    "update orders set platform_fee_amount=0,initial_amount_due=25000,platform_fee_rule_snapshot='{\"type\":\"FIXED\",\"currency\":\"XOF\",\"amount\":0,\"version\":1}'::jsonb where id=?",
                    f.order());
            j.update(
                    "update payment_requests set fee_amount=0,expected_amount=25000 where id=?",
                    requestId);
        }
        if (delayedLedger) {
            j.execute(
                    "alter table merchant_ledger_entries add constraint fixture_ledger_failure check(false)");
            try {
                var payment =
                        payments.initiateInitial(
                                f.org(), f.order(), "SIMULATED", UUID.randomUUID().toString());
                var reference =
                        j.queryForObject(
                                "select merchant_reference from payments where id=?",
                                String.class,
                                payment.id());
                paymentProvider.status(reference, PaymentStatus.SUCCEEDED);
                incoming.reconcile(payment.id());
                await().atMost(Duration.ofSeconds(15))
                        .untilAsserted(
                                () ->
                                        assertThat(
                                                        j.queryForObject(
                                                                "select status from orders where id=?",
                                                                String.class,
                                                                f.order()))
                                                .isEqualTo("CONFIRMED"));
                TestEventDrain.awaitIdle(j);
                assertThat(
                                j.queryForObject(
                                        "select count(*) from merchant_ledger_entries",
                                        Integer.class))
                        .isZero();
                assertThat(
                                j.queryForObject(
                                        "select count(*) from event_publication where status='FAILED' and listener_id like '%MerchantLedgerListeners%'",
                                        Integer.class))
                        .isPositive();
            } finally {
                j.execute(
                        "alter table merchant_ledger_entries drop constraint fixture_ledger_failure");
            }
        } else {
            paid();
        }
        TestEventDrain.awaitIdle(j);
        long version =
                j.queryForObject("select version from orders where id=?", Long.class, f.order());
        var result =
                read(
                        mvc.perform(
                                        post(base() + "/orders/" + f.order() + "/cancellation")
                                                .header("Authorization", "Bearer " + jwt)
                                                .header("Idempotency-Key", UUID.randomUUID())
                                                .contentType(
                                                        org.springframework.http.MediaType
                                                                .APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "version",
                                                                        version,
                                                                        "reason",
                                                                        "Restitution fictive",
                                                                        "confirmed",
                                                                        true))))
                                .andExpect(status().isOk())
                                .andReturn());
        var id = result.path("caseId").asText();
        mvc.perform(
                        post(base() + "/commercial-refunds/" + id + "/external-declaration")
                                .header("Authorization", "Bearer " + jwt)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        0,
                                                        "externalMethod",
                                                        "CASH",
                                                        "note",
                                                        "Fixture seulement",
                                                        "confirmed",
                                                        true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("refund.amountDue").value(25000 + fee))
                .andExpect(jsonPath("historicalFeeCompensationDue").value(fee));
        TestEventDrain.awaitIdle(j);
        var replay =
                new CommercialRefundRecorded(
                        UUID.randomUUID(),
                        clock.instant(),
                        f.org(),
                        new PaymentTarget(PaymentTargetType.ORDER, f.order()),
                        UUID.fromString(id),
                        new BigDecimal(25000 + fee),
                        "XOF");
        new TransactionTemplate(tx).executeWithoutResult(s -> events.publishEvent(replay));
        failed.resubmit(ResubmissionOptions.defaults().withMinAge(Duration.ZERO));
        TestEventDrain.awaitIdle(j);
        assertThat(j.queryForObject("select count(*) from merchant_ledger_entries", Integer.class))
                .isEqualTo(1);
        assertThat(
                        j.queryForObject(
                                "select count(*) from event_publication where completion_date is null",
                                Integer.class))
                .isZero();
        var balance =
                read(
                        auth(get(base() + "/merchant-balance"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(balance.path("grossMerchantCollections").decimalValue())
                .isEqualByComparingTo("25000");
        assertThat(balance.path("availableForPayout").decimalValue())
                .isEqualByComparingTo(fee == 0 ? "25000" : "0");
        assertThat(provider.sends).hasValue(0);
        assertThat(
                        j.queryForObject(
                                "select status from payments where payment_request_id=?",
                                String.class,
                                requestId))
                .isEqualTo("SUCCEEDED");
    }

    private void paid() {
        var p =
                payments.initiateInitial(
                        f.org(), f.order(), "SIMULATED", UUID.randomUUID().toString());
        var ref =
                j.queryForObject(
                        "select merchant_reference from payments where id=?", String.class, p.id());
        paymentProvider.status(ref, PaymentStatus.SUCCEEDED);
        incoming.reconcile(p.id());
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                j.queryForObject(
                                                        "select count(*) from merchant_ledger_entries",
                                                        Integer.class))
                                        .isEqualTo(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "account"})
    void anotherFundingSourceCannotSpendHistoricalCollections(String changed) throws Exception {
        paid();
        closed();
        var d = destination();
        if (changed.equals("provider")) provider.sourceProvider = "DIFFERENT";
        else provider.sourceAccount = "different-account";
        var b =
                read(
                        auth(get(base() + "/merchant-balance"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(b.path("grossMerchantCollections").decimalValue()).isEqualByComparingTo("25000");
        assertThat(b.path("availableForPayout").decimalValue()).isZero();
        request(d, UUID.randomUUID(), Map.of()).andExpect(status().isConflict());
        assertThat(provider.sends).hasValue(0);
    }

    @Test
    void collectionUnderReconciliationCannotFundPayout() throws Exception {
        paid();
        closed();
        var d = destination();
        j.update(
                "update payments set reconciliation_required=true,reconciliation_reason='PROVIDER_STATE_UNCERTAIN' where payment_request_id=?",
                requestId);
        var balance =
                read(
                        auth(get(base() + "/merchant-balance"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(balance.path("grossMerchantCollections").decimalValue())
                .isEqualByComparingTo("25000");
        assertThat(balance.path("availableForPayout").decimalValue()).isZero();
        request(d, UUID.randomUUID(), Map.of()).andExpect(status().isConflict());
        assertThat(provider.sends).hasValue(0);
    }

    private void closed() {
        j.update(
                "update grouping_campaigns set status='CLOSED',closed_at=now() where id=?",
                f.campaign());
    }

    private void role(String role) {
        j.update("update organization_members set role=? where organization_id=?", role, f.org());
    }

    private UUID destination() throws Exception {
        return UUID.fromString(
                read(auth(
                                        put(base() + "/payout-destination"),
                                        Map.of(
                                                "method",
                                                "WAVE",
                                                "phoneE164",
                                                "77 123 45 67",
                                                "holderName",
                                                "Titulaire",
                                                "confirmed",
                                                true))
                                .andExpect(status().isOk())
                                .andReturn())
                        .path("id")
                        .asText());
    }

    private UUID start(UUID d) throws Exception {
        return UUID.fromString(
                read(request(d, UUID.randomUUID(), Map.of()).andExpect(status().isOk()).andReturn())
                        .path("id")
                        .asText());
    }

    private ResultActions request(UUID d, UUID key, Map<String, Object> extra) throws Exception {
        var body = new HashMap<String, Object>(Map.of("destinationId", d, "confirmed", true));
        body.putAll(extra);
        return auth(post(payoutPath()).header("Idempotency-Key", key), body);
    }

    private JsonNode balance() throws Exception {
        return read(auth(get(base() + "/merchant-balance")).andExpect(status().isOk()).andReturn());
    }

    private ResultActions reconcile(UUID id) throws Exception {
        return auth(post(base() + "/merchant-payouts/" + id + "/reconcile"));
    }

    private void delivered(PaymentRequestSettled event) {
        delivered(event, event.eventId());
    }

    private void delivered(ShippingCreditRefundSettled event) {
        delivered(event, event.eventId());
    }

    private void delivered(Object event, UUID eventId) {
        String query =
                "select count(*) from event_publication where listener_id like '%MerchantLedgerListeners%' and serialized_event like ? and completion_date is not null";
        int before = j.queryForObject(query, Integer.class, "%" + eventId + "%");
        new TransactionTemplate(tx).executeWithoutResult(s -> events.publishEvent(event));
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                j.queryForObject(
                                                        query, Integer.class, "%" + eventId + "%"))
                                        .isEqualTo(before + 1));
    }

    private PaymentRequestSettled event(UUID request) {
        var r = payments.request(f.org(), request);
        return new PaymentRequestSettled(
                UUID.randomUUID(),
                r.closedAt(),
                f.org(),
                f.order(),
                r.id(),
                r.settledPaymentId(),
                r.purpose(),
                r.expectedAmount(),
                r.currency());
    }

    @Test
    void initialCreditExcludesFeeAndRemainsUnavailableWhileOpen() throws Exception {
        paid();
        var b = balance();
        assertThat(b.path("grossMerchantCollections").decimalValue()).isEqualByComparingTo("25000");
        assertThat(b.path("platformFees").decimalValue()).isEqualByComparingTo("300");
        assertThat(b.path("collectedBalance").decimalValue()).isEqualByComparingTo("25000");
        assertThat(b.path("availableForPayout").decimalValue()).isZero();
        request(destination(), UUID.randomUUID(), Map.of()).andExpect(status().isConflict());
        closed();
        assertThat(balance().path("availableForPayout").decimalValue())
                .isEqualByComparingTo("25000");
    }

    @Test
    void pendingPaymentDoesNotCreditAnything() throws Exception {
        payments.initiateInitial(f.org(), f.order(), "SIMULATED", UUID.randomUUID().toString());
        assertThat(balance().path("grossMerchantCollections").decimalValue()).isZero();
    }

    @Test
    void collectionAndAcceptanceReplaysRemainIdempotent() {
        paid();
        new TransactionTemplate(tx)
                .executeWithoutResult(
                        s -> {
                            events.publishEvent(event(requestId));
                            events.publishEvent(event(requestId));
                        });
        await().during(Duration.ofMillis(200))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(
                                                j.queryForObject(
                                                        "select count(*) from merchant_ledger_entries",
                                                        Integer.class))
                                        .isEqualTo(1));
    }

    @Test
    void shippingCollectionIsFullyCreditedWithoutANewFee() throws Exception {
        paid();
        closed();
        UUID r = MerchantSettlementFixtures.settled(j, f, "SHIPPING_BALANCE");
        delivered(event(r));
        assertThat(balance().path("availableForPayout").decimalValue())
                .isEqualByComparingTo("27000");
        assertThat(balance().path("platformFees").decimalValue()).isEqualByComparingTo("300");
        delivered(event(r));
        assertThat(j.queryForObject("select count(*) from merchant_ledger_entries", Integer.class))
                .isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BICTORYS_PAYOUT", "MANUAL_EXTERNAL"})
    void onlyPlatformFundedRefundsDebitTheMerchant(String method) throws Exception {
        paid();
        closed();
        UUID id = MerchantSettlementFixtures.refund(j, f, method);
        var e =
                new ShippingCreditRefundSettled(
                        UUID.randomUUID(),
                        Instant.now(),
                        f.org(),
                        f.order(),
                        id,
                        new BigDecimal("500"),
                        "XOF");
        delivered(e);
        delivered(e);
        assertThat(balance().path("collectedBalance").decimalValue())
                .isEqualByComparingTo(method.equals("BICTORYS_PAYOUT") ? "24500" : "25000");
        assertThat(balance().path("platformFundedRefunds").decimalValue())
                .isEqualByComparingTo(method.equals("BICTORYS_PAYOUT") ? "500" : "0");
    }

    @Test
    void disabledProviderLeavesBalancesAndDestinationUsableWithoutCreatingPayout()
            throws Exception {
        paid();
        closed();
        provider.available = false;
        UUID d = destination();
        request(d, UUID.randomUUID(), Map.of())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MERCHANT_PAYOUT_DISABLED"));
        assertThat(balance().path("availableForPayout").decimalValue())
                .isEqualByComparingTo("25000");
        assertThat(balance().path("providerAvailable").asBoolean()).isFalse();
        assertThat(j.queryForObject("select count(*) from merchant_payouts", Integer.class))
                .isZero();
        assertThat(provider.sends).hasValue(0);
    }

    @Test
    void pendingReservesAllFundsAndSuccessDebitsOnce() throws Exception {
        paid();
        closed();
        UUID d = destination(), key = UUID.randomUUID();
        UUID id =
                UUID.fromString(
                        read(request(d, key, Map.of()).andExpect(status().isOk()).andReturn())
                                .path("id")
                                .asText());
        request(d, key, Map.of()).andExpect(status().isOk());
        assertThat(provider.sends).hasValue(1);
        assertThat(balance().path("reservedForPayout").decimalValue())
                .isEqualByComparingTo("25000");
        assertThat(balance().path("availableForPayout").decimalValue()).isZero();
        assertThat(balance().path("merchantPayouts").decimalValue()).isZero();
        assertThat(provider.sent.phoneE164()).isEqualTo("+221771234567");
        assertThat(provider.sent.amount()).isEqualByComparingTo("25000");
        provider.status = "PENDING";
        reconcile(id).andExpect(status().isOk());
        assertThat(balance().path("reservedForPayout").decimalValue())
                .isEqualByComparingTo("25000");
        provider.status = "SUCCEEDED";
        reconcile(id).andExpect(status().isOk());
        reconcile(id).andExpect(status().isOk());
        assertThat(balance().path("merchantPayouts").decimalValue()).isEqualByComparingTo("25000");
        assertThat(balance().path("reservedForPayout").decimalValue()).isZero();
        assertThat(
                        j.queryForObject(
                                "select count(*) from merchant_ledger_entries where type='MERCHANT_PAYOUT'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(j.queryForObject("select status from payments", String.class))
                .isEqualTo("SUCCEEDED");
        assertThatThrownBy(
                        () ->
                                j.update(
                                        "update merchant_payouts set status='PENDING',succeeded_at=null where id=?",
                                        id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void timeoutKeepsReservationAndNeverSendsTwice() throws Exception {
        paid();
        closed();
        UUID d = destination(), key = UUID.randomUUID();
        provider.timeout = true;
        request(d, key, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));
        request(d, key, Map.of()).andExpect(status().isOk());
        request(d, UUID.randomUUID(), Map.of()).andExpect(status().isConflict());
        assertThat(provider.sends).hasValue(1);
        assertThat(balance().path("reservedForPayout").decimalValue())
                .isEqualByComparingTo("25000");
        provider.timeout = false;
        UUID id = j.queryForObject("select id from merchant_payouts", UUID.class);
        reconcile(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andExpect(
                        jsonPath("$.reconciliationReason").value("PAYOUT_REFERENCE_UNAVAILABLE"));
        payouts.verified(f.org(), id, provider.verify(payouts.get(f.org(), id)));
        assertThat(payouts.get(f.org(), id).status()).isEqualTo("SUCCEEDED");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "auth",
                "provider",
                "account",
                "reference",
                "transaction",
                "amount",
                "currency",
                "method",
                "phone"
            })
    void mismatchedEvidenceNeverDebitsOrReleasesFunds(String defect) throws Exception {
        paid();
        closed();
        UUID id = start(destination());
        provider.defect = defect;
        reconcile(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));
        assertThat(balance().path("reservedForPayout").decimalValue())
                .isEqualByComparingTo("25000");
        assertThat(balance().path("merchantPayouts").decimalValue()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "amount",
                "currency",
                "actorId",
                "organizationId",
                "providerTransactionId",
                "status"
            })
    void browserCannotChooseFinancialFields(String field) throws Exception {
        paid();
        closed();
        request(destination(), UUID.randomUUID(), Map.of(field, "forged"))
                .andExpect(status().isBadRequest());
        assertThat(provider.sends).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "OPERATOR", "VIEWER"})
    void onlyOwnerCanChangeDestination(String role) throws Exception {
        role(role);
        auth(
                        put(base() + "/payout-destination"),
                        Map.of(
                                "method",
                                "WAVE",
                                "phoneE164",
                                "771234567",
                                "holderName",
                                "A",
                                "confirmed",
                                true))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER"})
    void onlyOwnerCanRequestPayouts(String role) throws Exception {
        paid();
        closed();
        UUID d = destination();
        role(role);
        request(d, UUID.randomUUID(), Map.of()).andExpect(status().isOk());
        assertThat(provider.sends).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "OPERATOR", "VIEWER"})
    void otherRolesCannotRequestOrReconcilePayouts(String role) throws Exception {
        paid();
        closed();
        UUID d = destination(), id = start(d);
        role(role);
        request(d, UUID.randomUUID(), Map.of()).andExpect(status().isForbidden());
        reconcile(id).andExpect(status().isForbidden());
        if (role.equals("OPERATOR"))
            auth(get(base() + "/merchant-balance")).andExpect(status().isForbidden());
        else assertThat(balance().path("canManageSettlements").asBoolean()).isFalse();
    }

    @Test
    void destinationRevisionNeverRewritesPendingPayoutAndNumbersAreMasked() throws Exception {
        paid();
        closed();
        UUID old = destination(), id = start(old);
        var changed =
                read(
                        auth(
                                        put(base() + "/payout-destination"),
                                        Map.of(
                                                "previousDestinationId",
                                                old,
                                                "method",
                                                "ORANGE_MONEY",
                                                "phoneE164",
                                                "00221770000000",
                                                "holderName",
                                                "Autre titulaire",
                                                "confirmed",
                                                true))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(changed.toString()).doesNotContain("+221770000000");
        assertThat(
                        j.queryForObject(
                                "select status from merchant_payout_destinations where id=?",
                                String.class,
                                old))
                .isEqualTo("DISABLED");
        assertThat(
                        j.queryForObject(
                                "select phone_e164 from merchant_payouts where id=?",
                                String.class,
                                id))
                .isEqualTo("+221771234567");
        assertThat(read(auth(get(base() + "/merchant-payouts")).andReturn()).toString())
                .doesNotContain("+221771234567", "requestedBy", "providerIdempotencyKey");
        auth(
                        put(base() + "/payout-destination"),
                        Map.of(
                                "previousDestinationId",
                                old,
                                "method",
                                "WAVE",
                                "phoneE164",
                                "771234567",
                                "holderName",
                                "A",
                                "confirmed",
                                true))
                .andExpect(status().isConflict());
        assertThatThrownBy(
                        () ->
                                j.update(
                                        "update merchant_payout_destinations set phone_e164='+221770000000' where id=?",
                                        old))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void negativeBalanceAfterPlatformRefundBlocksNewPayoutsAndFutureCreditsCompensate()
            throws Exception {
        paid();
        closed();
        UUID d = destination(), id = start(d);
        reconcile(id).andExpect(status().isOk());
        UUID refund = MerchantSettlementFixtures.refund(j, f, "BICTORYS_PAYOUT");
        delivered(
                new ShippingCreditRefundSettled(
                        UUID.randomUUID(),
                        Instant.now(),
                        f.org(),
                        f.order(),
                        refund,
                        new BigDecimal("500"),
                        "XOF"));
        assertThat(balance().path("collectedBalance").decimalValue()).isEqualByComparingTo("-500");
        assertThat(balance().path("deficit").decimalValue()).isEqualByComparingTo("500");
        request(d, UUID.randomUUID(), Map.of()).andExpect(status().isConflict());
        var shipping = MerchantSettlementFixtures.settled(j, f, "SHIPPING_BALANCE");
        delivered(event(shipping));
        assertThat(balance().path("availableForPayout").decimalValue())
                .isEqualByComparingTo("1500");
    }

    @Test
    void concurrentRequestsProduceOneProviderCall() throws Exception {
        paid();
        closed();
        UUID d = destination();
        provider.entered = new CountDownLatch(1);
        provider.release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () ->
                                    request(d, UUID.randomUUID(), Map.of())
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            assertThat(provider.entered.await(10, TimeUnit.SECONDS)).isTrue();
            var second =
                    executor.submit(
                            () ->
                                    request(d, UUID.randomUUID(), Map.of())
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(409);
            provider.release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            provider.release.countDown();
        }
        assertThat(provider.sends).hasValue(1);
    }

    @Test
    void debtFromAnotherCampaignIsCompensatedBeforeReserving() throws Exception {
        paid();
        closed();
        UUID d = destination(), id = start(d);
        reconcile(id).andExpect(status().isOk());
        UUID refund = MerchantSettlementFixtures.refund(j, f, "BICTORYS_PAYOUT");
        delivered(
                new ShippingCreditRefundSettled(
                        UUID.randomUUID(),
                        Instant.now(),
                        f.org(),
                        f.order(),
                        refund,
                        new BigDecimal("500"),
                        "XOF"));
        f = MerchantSettlementFixtures.seed(j, f);
        closed();
        UUID r = MerchantSettlementFixtures.settled(j, f, "INITIAL_ORDER");
        delivered(event(r));
        assertThat(balance().path("availableForPayout").decimalValue())
                .isEqualByComparingTo("24500");
        start(d);
        assertThat(provider.sent.amount()).isEqualByComparingTo("24500");
        assertThat(balance().path("availableForPayout").decimalValue()).isZero();
    }

    @Test
    void reusedProviderTransactionCannotSettleAnotherPayout() throws Exception {
        paid();
        closed();
        UUID d = destination(), first = start(d);
        reconcile(first).andExpect(status().isOk());
        f = MerchantSettlementFixtures.seed(j, f);
        closed();
        UUID r = MerchantSettlementFixtures.settled(j, f, "INITIAL_ORDER");
        delivered(event(r));
        provider.transactionOverride = "MP-" + first;
        UUID second = start(d);
        reconcile(second)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andExpect(
                        jsonPath("$.reconciliationReason")
                                .value("PAYOUT_TRANSACTION_ALREADY_USED"));
        assertThat(balance().path("merchantPayouts").decimalValue()).isEqualByComparingTo("25000");
        assertThat(balance().path("reservedForPayout").decimalValue())
                .isEqualByComparingTo("25000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELLED"})
    void confirmedFailureReleasesFundsButLateSuccessStillDebitsOnce(String terminal)
            throws Exception {
        paid();
        closed();
        UUID id = start(destination());
        provider.status = terminal;
        reconcile(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value(terminal));
        assertThat(balance().path("availableForPayout").decimalValue())
                .isEqualByComparingTo("25000");
        provider.status = "SUCCEEDED";
        reconcile(id).andExpect(status().isOk());
        reconcile(id).andExpect(status().isOk());
        assertThat(balance().path("merchantPayouts").decimalValue()).isEqualByComparingTo("25000");
    }

    @Test
    void olderPendingEvidenceCannotMakeAnOlderFailureReleaseFunds() throws Exception {
        paid();
        closed();
        UUID id = start(destination());
        var p = payouts.get(f.org(), id);
        var latest = Instant.now();
        java.util.function.BiFunction<String, Instant, MerchantPayoutProvider.Proof> proof =
                (state, time) ->
                        new MerchantPayoutProvider.Proof(
                                true,
                                p.provider(),
                                p.providerAccountReference(),
                                p.businessReference(),
                                p.providerTransactionId(),
                                p.amount(),
                                p.currency(),
                                p.method(),
                                p.phoneE164(),
                                state,
                                time);
        payouts.verified(f.org(), id, proof.apply("PENDING", latest));
        payouts.verified(f.org(), id, proof.apply("PENDING", latest.minusSeconds(20)));
        payouts.verified(f.org(), id, proof.apply("FAILED", latest.minusSeconds(10)));
        assertThat(balance().path("reservedForPayout").decimalValue())
                .isEqualByComparingTo("25000");
        assertThat(payouts.get(f.org(), id).status()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void disablingProviderBeforeDispatchPreventsEvenAnIdempotentRetryFromSending()
            throws Exception {
        paid();
        closed();
        UUID d = destination(), key = UUID.randomUUID();
        // Crash boundary: a committed reservation exists, but no network dispatch happened yet.
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var principal =
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture")
                        .header("alg", "HS256")
                        .subject(f.actor().toString())
                        .build();
        context.setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication
                        .JwtAuthenticationToken(principal, List.of()));
        try {
            payouts.reserve(f.org(), f.campaign(), key, d, true);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        provider.available = false;
        request(d, key, Map.of()).andExpect(status().isServiceUnavailable());
        assertThat(provider.sends).hasValue(0);
        assertThat(balance().path("reservedForPayout").decimalValue())
                .isEqualByComparingTo("25000");
    }

    @Test
    void foreignOrganizationAndInactiveMembershipCannotReadOrMutate() throws Exception {
        var other = MerchantSettlementFixtures.seed(j);
        auth(get("/api/organizations/" + other.org() + "/merchant-balance"))
                .andExpect(status().isForbidden());
        auth(get(base() + "/grouping-campaigns/" + other.campaign() + "/merchant-settlement"))
                .andExpect(status().isNotFound());
        j.update(
                "update organization_members set status='REVOKED',revoked_at=now() where organization_id=?",
                f.org());
        auth(get(base() + "/merchant-balance")).andExpect(status().isForbidden());
    }

    @Test
    void ledgerConsumerFailureIsDurableAndRecoverableWithoutDoubleCredit() {
        j.execute(
                "alter table merchant_ledger_entries add constraint fixture_ledger_failure check(false)");
        try {
            var p =
                    payments.initiateInitial(
                            f.org(), f.order(), "SIMULATED", UUID.randomUUID().toString());
            var ref =
                    j.queryForObject(
                            "select merchant_reference from payments where id=?",
                            String.class,
                            p.id());
            paymentProvider.status(ref, PaymentStatus.SUCCEEDED);
            incoming.reconcile(p.id());
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(
                            () ->
                                    assertThat(
                                                    j.queryForObject(
                                                            "select count(*) from event_publication where status='FAILED' and listener_id like '%MerchantLedgerListeners%'",
                                                            Integer.class))
                                            .isPositive());
            assertThat(j.queryForObject("select status from payments", String.class))
                    .isEqualTo("SUCCEEDED");
        } finally {
            j.execute("alter table merchant_ledger_entries drop constraint fixture_ledger_failure");
        }
        failed.resubmit(ResubmissionOptions.defaults().withMinAge(Duration.ZERO));
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                j.queryForObject(
                                                        "select count(*) from merchant_ledger_entries",
                                                        Integer.class))
                                        .isEqualTo(1));
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                j.queryForObject(
                                                        "select count(*) from event_publication where completion_date is null",
                                                        Integer.class))
                                        .isZero());
    }

    private ResultActions auth(MockHttpServletRequestBuilder r) throws Exception {
        return mvc.perform(r.header("Authorization", "Bearer " + jwt));
    }

    private ResultActions auth(MockHttpServletRequestBuilder r, Object body) throws Exception {
        return auth(r.contentType("application/json").content(json.writeValueAsString(body)));
    }

    private JsonNode read(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String token(UUID actor) {
        var claims =
                JwtClaimsSet.builder()
                        .claim("sid", UUID.randomUUID().toString())
                        .issuer("https://groupia.test")
                        .audience(List.of("groupia-api-test"))
                        .subject(actor.toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
