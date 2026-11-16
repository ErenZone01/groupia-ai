package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.application.service.CommercialRefundService;
import sn.groupia.settlement.domain.model.MerchantPayout;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;
import tools.jackson.databind.*;

/** All transfers in this test use a synthetic provider and isolated PostgreSQL. */
@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "groupia.settlement.payouts.enabled=true",
            "groupia.settlement.direct-payouts.enabled=true"
        })
@AutoConfigureMockMvc
@Import({
    TestcontainersConfiguration.class,
    PaymentTestConfiguration.class,
    MerchantPayoutTestConfiguration.class
})
class PayoutAdministrationIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentTestConfiguration.MutablePaymentClock clock;
    @Autowired MerchantFinanceStore feeStore;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    @MockitoSpyBean MerchantPayoutTestConfiguration.FakeMerchantProvider provider;
    @Autowired sn.groupia.settlement.application.service.MerchantPayoutTransactions policy;
    @Autowired sn.groupia.settlement.application.service.PayoutAdministrationService administration;
    @Autowired sn.groupia.settlement.infrastructure.ReviewedPayoutEvidence evidenceFiles;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path approvalDirectory;
    @MockitoBean PaymentOperations payments;
    @MockitoSpyBean CommercialRefundService refunds;
    UUID org, actor, orderId, requestId, paymentId;
    String jwt;

    @BeforeEach
    void setup() {
        jdbc.execute(
                "TRUNCATE merchant_payout_fee_quote_bindings,merchant_payout_fee_quotes,saspay_company_fee_events,merchant_payout_provider_fees,merchant_ledger_entries,merchant_payouts,merchant_payout_destinations,"
                        + "outgoing_provider_operations,provider_collections,direct_orders,"
                        + "direct_storefronts,organizations,user_accounts,event_publication CASCADE");
        provider.reset();
        clock.reset();
        doReturn("SASPAY").when(provider).name();
        provider.sourceProvider = "SASPAY";
        provider.sourceAccount = "fixture";
        doReturn(true).when(provider).administrativeApprovalEligible();
        ReflectionTestUtils.setField(policy, "saspayLivePilotReleased", false);
        doReturn(new BigDecimal("37")).when(provider).payoutFeeQuote(any(), any());
        ReflectionTestUtils.setField(administration, "enabled", true);

        actor = UUID.randomUUID();
        org = UUID.randomUUID();
        orderId = UUID.randomUUID();
        requestId = UUID.randomUUID();
        paymentId = UUID.randomUUID();
        var storefront = UUID.randomUUID();
        jdbc.update(
                "insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at)"
                        + " values (?,? ,?,'ACTIVE',now(),now())",
                actor,
                "+221771234567",
                passwords.encode("Unified payout fixture 2026!"));
        jdbc.update(
                "insert into organizations(id,name,status,created_at,updated_at)"
                        + " values (?,'Boutique de test','ACTIVE',now(),now())",
                org);
        jdbc.update(
                "insert into organization_members(id,organization_id,user_account_id,role,status,joined_at)"
                        + " values (?,?,?,'OWNER','ACTIVE',now())",
                UUID.randomUUID(),
                org,
                actor);
        jwt = token(actor);
        ReflectionTestUtils.setField(administration, "operators", actor.toString());
        ReflectionTestUtils.setField(policy, "saspayPayoutPilotEnabled", true);
        ReflectionTestUtils.setField(policy, "saspayPayoutPilotOrganizationId", org.toString());
        ReflectionTestUtils.setField(policy, "saspayPayoutPilotMaxXof", new BigDecimal("1000"));

        jdbc.update(
                "insert into direct_storefronts(id,organization_id,public_identifier,display_name,"
                        + "created_at,updated_at) values (?,?,?,'Vitrine de test',now(),now())",
                storefront,
                org,
                UUID.randomUUID());
        seedOrder(storefront);
    }

    private void seedOrder(UUID storefront) {
        String number =
                "DIR-"
                        + String.format(
                                "%012d",
                                Math.floorMod(
                                        orderId.getMostSignificantBits(), 1_000_000_000_000L));
        jdbc.update(
                """
                insert into direct_orders(
                    id,organization_id,storefront_id,order_number,status,fulfillment_method,
                    currency,customer_contact_snapshot,goods_subtotal_xof,platform_fee_xof,
                    platform_fee_rule_snapshot,amount_before_shipping_xof,shipping_quoted,
                    checkout_idempotency_key,checkout_request_fingerprint,placed_at,
                    shipping_fee_xof,accepted_total_xof,payment_confirmed_at,
                    preparing_at,preparing_by,ready_at,ready_by,handed_over_at,completed_at)
                values (?,?,?,?,'COMPLETED','PICKUP','XOF',
                    '{"firstName":"Awa","lastName":"Fixture","phoneE164":"+221771234567"}'::jsonb,
                    1000,0,'{"type":"FIXED","amount":0}'::jsonb,
                    1000,true,?,repeat('a',64),now()-interval '15 minutes',
                    0,1000,now()-interval '10 minutes',
                    now()-interval '9 minutes',?,
                    now()-interval '8 minutes',?,
                    now()-interval '7 minutes',now()-interval '6 minutes')
                """,
                orderId,
                org,
                storefront,
                number,
                UUID.randomUUID().toString(),
                actor,
                actor);
        jdbc.update(
                """
                insert into provider_collections(
                    id,organization_id,payment_request_id,payment_id,target_type,target_id,
                    purpose,provider,provider_account,provider_transaction_id,currency,
                    principal,seller_principal,platform_fee,confirmed_at)
                values (?,?,?,?,'DIRECT_ORDER',?,'DIRECT_ORDER_INITIAL',
                    'SASPAY','fixture',?,'XOF',1000,1000,0,now()-interval '10 minutes')
                """,
                requestId,
                org,
                requestId,
                paymentId,
                orderId,
                "TX-" + paymentId);
        var proof = verified(false);
        when(payments.settlementEvidence(org, requestId)).thenReturn(proof);
        var obligation =
                new PaymentRequestView(
                        requestId,
                        org,
                        null,
                        PaymentPurpose.DIRECT_ORDER_INITIAL,
                        "DIRECT_ORDER_INITIAL:" + orderId,
                        new BigDecimal("1000"),
                        "XOF",
                        new BigDecimal("1000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        AcceptanceStatus.CLOSED,
                        Instant.now().plusSeconds(1200),
                        paymentId,
                        Instant.now().minusSeconds(1200),
                        Instant.now().minusSeconds(600),
                        null,
                        0L,
                        PaymentTarget.directOrder(orderId));
        when(payments.requestState(org, requestId))
                .thenReturn(
                        new PaymentRequestState(
                                obligation, PaymentOperationalStatus.SETTLED, false, false));
    }

    private PaymentSettlementEvidence verified(boolean reversed) {
        return new PaymentSettlementEvidence(
                org,
                requestId,
                paymentId,
                PaymentTarget.directOrder(orderId),
                PaymentPurpose.DIRECT_ORDER_INITIAL,
                new BigDecimal("1000"),
                "XOF",
                AcceptanceStatus.CLOSED,
                PaymentStatus.SUCCEEDED,
                "SASPAY",
                "fixture",
                "TX-" + paymentId,
                "GP-" + paymentId,
                Instant.now().minusSeconds(600),
                null,
                reversed);
    }

    private String base() {
        return "/api/organizations/" + org;
    }

    private ResultActions auth(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + jwt));
    }

    private ResultActions auth(MockHttpServletRequestBuilder request, Object body)
            throws Exception {
        return auth(
                request.contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)));
    }

    private JsonNode read(ResultActions action) throws Exception {
        var result = action.andReturn();
        if (result.getResponse().getStatus() != 200) {
            // ApiExceptionHandler intentionally hides internal exceptions from HTTP
            // clients. Synthetic integration tests must surface the *actual* cause,
            // otherwise an unexpected financial failure is reported only as HTTP 500.
            Exception cause = result.getResolvedException();
            if (cause != null)
                throw new AssertionError(
                        "Unexpected payout HTTP status " + result.getResponse().getStatus(), cause);
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private UUID destination() throws Exception {
        return UUID.fromString(
                read(auth(
                                put(base() + "/payout-destination"),
                                Map.of(
                                        "method",
                                        "WAVE",
                                        "phoneE164",
                                        "+221771234567",
                                        "holderName",
                                        "Vendeuse",
                                        "confirmed",
                                        true)))
                        .path("id")
                        .asText());
    }

    private UUID quote(UUID order, UUID destination) throws Exception {
        return UUID.fromString(
                read(auth(
                                get(base() + "/direct-orders/" + order + "/merchant-payout-quote")
                                        .param("destinationId", destination.toString())
                                        .param("amount", "1000")))
                        .path("quoteId")
                        .asText());
    }

    private ResultActions request(UUID order, UUID destination, UUID key, UUID quote)
            throws Exception {
        return auth(
                post(base() + "/direct-orders/" + order + "/merchant-payouts")
                        .header("Idempotency-Key", key),
                Map.of("destinationId", destination, "feeQuoteId", quote, "confirmed", true));
    }

    private MerchantPayout uncertain(UUID dest) throws Exception {
        provider.timeout = true;
        var q = quote(orderId, dest);
        var id =
                UUID.fromString(
                        read(request(orderId, dest, UUID.randomUUID(), q)).path("id").asText());
        // Same reserved, reference-less state as a historical recovery timeout.
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(
                        s -> {
                            feeStore.state(
                                    id,
                                    "REVIEW_REQUIRED",
                                    null,
                                    null,
                                    null,
                                    true,
                                    "PAYOUT_MANUAL_RECOVERY_UNCERTAIN");
                            return null;
                        });
        return feeStore.payout(org, id);
    }

    private UUID anotherOrder() {
        UUID storefront =
                jdbc.queryForObject(
                        "select storefront_id from direct_orders where id=?", UUID.class, orderId);
        orderId = UUID.randomUUID();
        requestId = UUID.randomUUID();
        paymentId = UUID.randomUUID();
        seedOrder(storefront);
        ReflectionTestUtils.setField(policy, "saspayLivePilotDirectOrderId", orderId.toString());
        return orderId;
    }

    private Map<String, Object> approval(MerchantPayout p, String action, UUID target, UUID dest)
            throws Exception {
        var a = new LinkedHashMap<String, Object>();
        a.put("reference", "FIXTURE-" + p.id() + "-" + action);
        a.put("action", action);
        a.put("organizationId", org);
        a.put("payoutId", p.id());
        a.put("payoutVersion", p.version());
        a.put("operatorId", actor);
        a.put("providerAccount", p.providerAccountReference());
        a.put(
                "originalKeySha256",
                sn.groupia.settlement.infrastructure.ReviewedPayoutEvidence.sha256(
                        p.providerIdempotencyKey().toString()));
        a.put(
                "businessReferenceSha256",
                sn.groupia.settlement.infrastructure.ReviewedPayoutEvidence.sha256(
                        p.businessReference()));
        a.put("amount", new BigDecimal("1000"));
        a.put("currency", "XOF");
        a.put(
                "evidenceType",
                action.equals("INDEPENDENT_PILOT")
                        ? "INDEPENDENT_FUNDS_RISK_REVIEW"
                        : "PROVIDER_ATTESTATION");
        a.put("evidenceReference", "SIMULATED-SUPPORT-42");
        a.put("allAttemptsCovered", true);
        a.put("definitiveNonExecution", true);
        a.put("futureExecutionImpossible", true);
        a.put("reviewedAt", clock.instant().toString());
        a.put("expiresAt", clock.instant().plusSeconds(600).toString());
        a.put("directOrderId", target);
        a.put("destinationId", dest);
        return a;
    }

    private void pin(Map<String, Object> approval) throws Exception {
        byte[] artifact =
                "SIMULATED EVIDENCE ONLY: all gateway attempts closed; no transfer or future execution."
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        approval.put(
                "evidenceSha256",
                sn.groupia.settlement.infrastructure.ReviewedPayoutEvidence.sha256(artifact));
        var path = approvalDirectory.resolve("review.json");
        byte[] manifest = json.writeValueAsBytes(approval);
        java.nio.file.Files.write(path, manifest);
        java.nio.file.Files.write(java.nio.file.Path.of(path + ".evidence"), artifact);
        ReflectionTestUtils.setField(evidenceFiles, "approvalFile", path.toString());
        ReflectionTestUtils.setField(
                evidenceFiles,
                "approvalSha256",
                sn.groupia.settlement.infrastructure.ReviewedPayoutEvidence.sha256(manifest));
    }

    private ResultActions decision(MerchantPayout p, Map<String, Object> approval)
            throws Exception {
        return auth(
                post(base() + "/merchant-payouts/" + p.id() + "/administration"),
                Map.of(
                        "action",
                        approval.get("action"),
                        "approvalReference",
                        approval.get("reference"),
                        "expectedVersion",
                        p.version(),
                        "reason",
                        "Preuve synthétique vérifiée pour cette intention uniquement.",
                        "confirmed",
                        true));
    }

    private JsonNode balance() throws Exception {
        return read(auth(get(base() + "/merchant-balance")));
    }

    private void allowSuccess() {
        provider.timeout = false;
        doAnswer(
                        invocation -> {
                            MerchantPayout p = invocation.getArgument(0);
                            return new sn.groupia.settlement.domain.service.MerchantPayoutProvider
                                    .Proof(
                                    true,
                                    "SASPAY",
                                    "fixture",
                                    p.businessReference(),
                                    p.providerTransactionId(),
                                    p.amount(),
                                    "XOF",
                                    "WAVE",
                                    p.phoneE164(),
                                    "SUCCEEDED",
                                    Instant.now(),
                                    new BigDecimal("37"));
                        })
                .when(provider)
                .verify(any());
    }

    @Test
    void provenClosureReleasesOnlyReservationPreservesIdentityAndAllowsFreshIntent()
            throws Exception {
        UUID dest = destination();
        var old = uncertain(dest);
        assertThat(balance().path("reservedForPayout").decimalValue()).isEqualByComparingTo("1000");
        var approval = approval(old, "CLOSE_NON_EXECUTED", null, null);
        pin(approval);
        var closed = read(decision(old, approval));
        assertThat(closed.path("status").asText()).isEqualTo("NON_EXECUTE");
        assertThat(read(decision(old, approval)).path("authorizationId"))
                .isEqualTo(closed.path("authorizationId"));
        var preserved = feeStore.payout(org, old.id());
        assertThat(preserved.providerIdempotencyKey()).isEqualTo(old.providerIdempotencyKey());
        assertThat(preserved.businessReference()).isEqualTo(old.businessReference());
        assertThat(balance().path("grossMerchantCollections").decimalValue())
                .isEqualByComparingTo("1000");
        assertThat(balance().path("availableForPayout").decimalValue())
                .isEqualByComparingTo("1000");
        assertThat(policy.claim(org, old.id())).isFalse();
        read(auth(post(base() + "/merchant-payouts/" + old.id() + "/reconcile")));
        assertThat(feeStore.payout(org, old.id()).status()).isEqualTo("NON_EXECUTE");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update merchant_payouts set status='CREATED' where id=?",
                                        old.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "delete from merchant_payout_administrations where payout_id=?",
                                        old.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        allowSuccess();
        UUID key = UUID.randomUUID(), quote = quote(orderId, dest);
        var fresh = read(request(orderId, dest, key, quote));
        var freshId = UUID.fromString(fresh.path("id").asText());
        assertThat(feeStore.payout(org, freshId).providerIdempotencyKey())
                .isNotEqualTo(old.providerIdempotencyKey());
        assertThat(freshId).isNotEqualTo(old.id());
        read(request(orderId, dest, key, quote));
        assertThat(provider.sends).hasValue(2); // old simulated timeout, then one new dispatch
        read(auth(post(base() + "/merchant-payouts/" + freshId + "/reconcile")));
        read(auth(post(base() + "/merchant-payouts/" + freshId + "/reconcile")));
        assertThat(balance().path("merchantPayouts").decimalValue()).isEqualByComparingTo("1000");
        assertThat(balance().path("availableForPayout").decimalValue()).isZero();
    }

    @Test
    void independentGrantWithDispatchClosedCannotReserveOrSendAndPreservesHistory()
            throws Exception {
        UUID dest = destination();
        var old = uncertain(dest);
        UUID second = anotherOrder();
        provider.available = false;
        assertThat(policy.available()).isFalse();
        var a = approval(old, "INDEPENDENT_PILOT", second, dest);
        pin(a);
        clearInvocations(provider);
        var grant = read(decision(old, a));
        assertThat(grant.path("authorizationId").asText()).isNotBlank();
        assertThat(read(decision(old, a)).path("authorizationId"))
                .isEqualTo(grant.path("authorizationId"));
        request(second, dest, UUID.randomUUID(), UUID.randomUUID())
                .andExpect(status().isServiceUnavailable());
        auth(get(base() + "/direct-orders/" + second + "/merchant-payout-quote")
                        .param("destinationId", dest.toString())
                        .param("amount", "1000"))
                .andExpect(status().isServiceUnavailable());
        assertThat(feeStore.payout(org, old.id())).isEqualTo(old);
        assertThat(jdbc.queryForObject("select count(*) from merchant_payouts", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from merchant_payout_pilot_grants", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from merchant_payout_pilot_bindings",
                                Integer.class))
                .isZero();
        var funds = balance();
        assertThat(funds.path("grossMerchantCollections").decimalValue())
                .isEqualByComparingTo("2000");
        assertThat(funds.path("reservedForPayout").decimalValue()).isEqualByComparingTo("1000");
        assertThat(funds.path("availableForPayout").decimalValue()).isZero();
        verify(provider, never()).initiate(any());
        verify(provider, never()).verify(any());
        verify(provider, never()).payoutFeeQuote(any(), any());
        assertThat(provider.sends).hasValue(1); // only the earlier simulated uncertain attempt
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "invalid", "other"})
    void closedDispatchStillRequiresExactConfiguredOrder(String target) throws Exception {
        UUID dest = destination();
        var old = uncertain(dest);
        UUID second = anotherOrder();
        provider.available = false;
        ReflectionTestUtils.setField(
                policy,
                "saspayLivePilotDirectOrderId",
                target.equals("other") ? old.directOrderId().toString() : target);
        var a = approval(old, "INDEPENDENT_PILOT", second, dest);
        pin(a);
        decision(old, a)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("code").value("SASPAY_PAYOUT_LIVE_PILOT_TARGET_MISMATCH"));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from merchant_payout_administrations",
                                Integer.class))
                .isZero();
        assertThat(feeStore.payout(org, old.id())).isEqualTo(old);
        assertThat(provider.sends).hasValue(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"provider", "operator", "permission", "amount", "destination", "funding"})
    void closedDispatchDoesNotBypassIndependentApprovalChecks(String defect) throws Exception {
        UUID dest = destination();
        var old = uncertain(dest);
        UUID second = anotherOrder();
        provider.available = false;
        var a = approval(old, "INDEPENDENT_PILOT", second, dest);
        switch (defect) {
            case "provider" -> doReturn(false).when(provider).administrativeApprovalEligible();
            case "operator" -> ReflectionTestUtils.setField(administration, "operators", "");
            case "permission" ->
                    jdbc.update(
                            "update organization_members set role='VIEWER' where user_account_id=?",
                            actor);
            case "amount" -> a.put("amount", new BigDecimal("1001"));
            case "destination" -> a.put("destinationId", UUID.randomUUID());
            case "funding" -> provider.sourceAccount = "another-merchant";
        }
        pin(a);
        int status = decision(old, a).andReturn().getResponse().getStatus();
        assertThat(status).isIn(403, 409);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from merchant_payout_pilot_grants", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from merchant_payout_administrations",
                                Integer.class))
                .isZero();
        assertThat(feeStore.payout(org, old.id())).isEqualTo(old);
        assertThat(provider.sends).hasValue(1);
    }

    @Test
    void independentPilotNeverReleasesOrReusesHistoricalReserveAndDispatchesOnlyOnce()
            throws Exception {
        UUID dest = destination();
        var old = uncertain(dest);
        UUID second = anotherOrder();
        assertThat(balance().path("grossMerchantCollections").decimalValue())
                .isEqualByComparingTo("2000");
        assertThat(balance().path("reservedForPayout").decimalValue()).isEqualByComparingTo("1000");
        assertThat(balance().path("availableForPayout").decimalValue())
                .isEqualByComparingTo("1000");
        auth(get(base() + "/direct-orders/" + second + "/merchant-payout-quote")
                        .param("destinationId", dest.toString())
                        .param("amount", "1000"))
                .andExpect(status().isConflict());
        var approval = approval(old, "INDEPENDENT_PILOT", second, dest);
        pin(approval);
        read(decision(old, approval));
        read(decision(old, approval));
        assertThat(feeStore.payout(org, old.id())).isEqualTo(old);
        UUID quote = quote(second, dest), key = UUID.randomUUID();
        allowSuccess();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> read(request(second, dest, key, quote)));
            var b = pool.submit(() -> read(request(second, dest, key, quote)));
            assertThat(a.get().path("id")).isEqualTo(b.get().path("id"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(provider.sends).hasValue(2);
        assertThat(balance().path("reservedForPayout").decimalValue()).isEqualByComparingTo("2000");
        assertThat(balance().path("availableForPayout").decimalValue()).isZero();
        read(auth(post(base() + "/merchant-payouts/" + provider.sent.id() + "/reconcile")));
        assertThat(balance().path("merchantPayouts").decimalValue()).isEqualByComparingTo("1000");
        assertThat(balance().path("reservedForPayout").decimalValue()).isEqualByComparingTo("1000");
        assertThat(feeStore.payout(org, old.id())).isEqualTo(old);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from merchant_payout_pilot_bindings",
                                Integer.class))
                .isEqualTo(1);
        request(second, dest, UUID.randomUUID(), quote).andExpect(status().isConflict());
    }

    @Test
    void missingTamperedIncompleteExpiredOrWrongScopeProofCannotReleaseFunds() throws Exception {
        UUID dest = destination();
        var old = uncertain(dest);
        var approval = approval(old, "CLOSE_NON_EXECUTED", null, null);
        ReflectionTestUtils.setField(evidenceFiles, "approvalFile", "");
        decision(old, approval).andExpect(status().isConflict());
        pin(approval);
        java.nio.file.Files.writeString(
                approvalDirectory.resolve("review.json.evidence"), "tampered");
        decision(old, approval).andExpect(status().isConflict());
        approval.put("allAttemptsCovered", false);
        pin(approval);
        decision(old, approval).andExpect(status().isConflict());
        approval.put("allAttemptsCovered", true);
        approval.put("originalKeySha256", "0".repeat(64));
        pin(approval);
        decision(old, approval).andExpect(status().isConflict());
        approval = approval(old, "CLOSE_NON_EXECUTED", null, null);
        approval.put("expiresAt", clock.instant().minusSeconds(1).toString());
        pin(approval);
        decision(old, approval).andExpect(status().isConflict());
        assertThat(feeStore.payout(org, old.id())).isEqualTo(old);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from merchant_payout_administrations",
                                Integer.class))
                .isZero();
    }

    @Test
    void operatorAuthorizationOrganizationIsolationAndChangedVersionAreEnforced() throws Exception {
        UUID dest = destination();
        var old = uncertain(dest);
        var approval = approval(old, "CLOSE_NON_EXECUTED", null, null);
        pin(approval);
        ReflectionTestUtils.setField(administration, "operators", UUID.randomUUID().toString());
        decision(old, approval).andExpect(status().isForbidden());
        ReflectionTestUtils.setField(administration, "operators", actor.toString());
        UUID actualOrg = org;
        org = UUID.randomUUID();
        decision(old, approval).andExpect(status().isForbidden());
        org = actualOrg;
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(
                        s -> {
                            feeStore.state(
                                    old.id(),
                                    "REVIEW_REQUIRED",
                                    null,
                                    null,
                                    null,
                                    true,
                                    "PAYOUT_PROVIDER_UNCERTAIN");
                            return null;
                        });
        decision(old, approval).andExpect(status().isConflict());
        assertThat(feeStore.payout(org, old.id()).reserved()).isTrue();
    }

    @Test
    void transactionRollbackRestoresReserveAndRemovesDecisionAudit() throws Exception {
        UUID dest = destination();
        var old = uncertain(dest);
        var a = approval(old, "CLOSE_NON_EXECUTED", null, null);
        pin(a);
        // Exercise the same HTTP operation inside an outer rollback transaction.
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(
                        s -> {
                            try {
                                read(decision(old, a));
                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            }
                            assertThat(feeStore.payout(org, old.id()).status())
                                    .isEqualTo("NON_EXECUTE");
                            s.setRollbackOnly();
                            return null;
                        });
        assertThat(feeStore.payout(org, old.id())).isEqualTo(old);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from merchant_payout_administrations",
                                Integer.class))
                .isZero();
    }

    @Test
    void lateCorrelatedProofFreezesNewWithdrawalsWithoutReopeningClosedIntent() throws Exception {
        UUID dest = destination();
        var old = uncertain(dest);
        var a = approval(old, "CLOSE_NON_EXECUTED", null, null);
        pin(a);
        read(decision(old, a));
        policy.verified(
                org,
                old.id(),
                new sn.groupia.settlement.domain.service.MerchantPayoutProvider.Proof(
                        true,
                        "SASPAY",
                        "fixture",
                        old.businessReference(),
                        "LATE-TX",
                        old.amount(),
                        "XOF",
                        "WAVE",
                        old.phoneE164(),
                        "SUCCEEDED",
                        Instant.now(),
                        new BigDecimal("37")));
        assertThat(feeStore.payout(org, old.id()).status()).isEqualTo("NON_EXECUTE");
        assertThat(feeStore.hasResolutionIncident(org)).isTrue();
        assertThat(balance().path("availableForPayout").decimalValue()).isZero();
        auth(get(base() + "/direct-orders/" + orderId + "/merchant-payout-quote")
                        .param("destinationId", dest.toString())
                        .param("amount", "1000"))
                .andExpect(status().isConflict());
        assertThat(provider.sends).hasValue(1);
    }

    @Test
    void twoConcurrentClosuresProduceOneAuditAndOneRelease() throws Exception {
        UUID dest = destination();
        var old = uncertain(dest);
        var a = approval(old, "CLOSE_NON_EXECUTED", null, null);
        pin(a);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> read(decision(old, a)));
            var second = pool.submit(() -> read(decision(old, a)));
            assertThat(first.get().path("authorizationId"))
                    .isEqualTo(second.get().path("authorizationId"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from merchant_payout_administrations",
                                Integer.class))
                .isEqualTo(1);
        assertThat(balance().path("availableForPayout").decimalValue())
                .isEqualByComparingTo("1000");
        assertThat(provider.sends).hasValue(1);
    }

    @Test
    void competingNewKeysCannotConsumeIndependentGrantTwice() throws Exception {
        UUID dest = destination();
        var old = uncertain(dest);
        UUID second = anotherOrder();
        var a = approval(old, "INDEPENDENT_PILOT", second, dest);
        pin(a);
        read(decision(old, a));
        UUID quote = quote(second, dest);
        provider.timeout = false;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first =
                    pool.submit(
                            () ->
                                    request(second, dest, UUID.randomUUID(), quote)
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            var next =
                    pool.submit(
                            () ->
                                    request(second, dest, UUID.randomUUID(), quote)
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            assertThat(List.of(first.get(), next.get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from merchant_payout_pilot_bindings",
                                Integer.class))
                .isEqualTo(1);
        assertThat(provider.sends).hasValue(2);
        assertThat(feeStore.payout(org, old.id())).isEqualTo(old);
    }

    @Test
    void reversedHistoricCollectionCannotFundAnIndependentPilotEvenWithSecondReceipt()
            throws Exception {
        UUID dest = destination();
        var old = uncertain(dest);
        var reversed = verified(true);
        UUID oldRequest = requestId;
        UUID second = anotherOrder();
        when(payments.settlementEvidence(org, oldRequest)).thenReturn(reversed);
        provider.available = false;
        var a = approval(old, "INDEPENDENT_PILOT", second, dest);
        pin(a);
        decision(old, a).andExpect(status().isConflict());
        assertThat(balance().path("availableForPayout").decimalValue()).isZero();
        assertThat(feeStore.payout(org, old.id())).isEqualTo(old);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from merchant_payout_pilot_grants", Integer.class))
                .isZero();
    }

    @Test
    void failedReservationDoesNotConsumeGrantAndExpiredGrantCannotDispatch() throws Exception {
        UUID dest = destination();
        var old = uncertain(dest);
        UUID second = anotherOrder();
        var a = approval(old, "INDEPENDENT_PILOT", second, dest);
        pin(a);
        read(decision(old, a));
        UUID expiredQuote = quote(second, dest);
        clock.advance(java.time.Duration.ofMinutes(4));
        request(second, dest, UUID.randomUUID(), expiredQuote).andExpect(status().isConflict());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from merchant_payout_pilot_bindings",
                                Integer.class))
                .isZero();
        UUID validQuote = quote(second, dest);
        // Reserve without dispatch, exactly as a crash between the two transactions.
        var context = SecurityContextHolder.getContext();
        var jwtAuth =
                new org.springframework.security.oauth2.server.resource.authentication
                        .JwtAuthenticationToken(
                        Jwt.withTokenValue("fixture")
                                .header("alg", "HS256")
                                .subject(actor.toString())
                                .build(),
                        List.of());
        context.setAuthentication(jwtAuth);
        MerchantPayout reserved;
        try {
            reserved = policy.reserveDirect(org, second, UUID.randomUUID(), dest, true, validQuote);
        } finally {
            SecurityContextHolder.clearContext();
        }
        clock.advance(java.time.Duration.ofMinutes(7));
        assertThatThrownBy(() -> policy.claim(org, reserved.id())).hasMessageContaining("expirée");
        assertThat(feeStore.payout(org, reserved.id()).reserved()).isTrue();
        assertThat(feeStore.payout(org, old.id())).isEqualTo(old);
        assertThat(provider.sends).hasValue(1);
    }

    @Test
    void independentSettlementAndLateHistoricalSuccessDebitDistinctReceiptsOnlyOnce()
            throws Exception {
        UUID dest = destination();
        var old = uncertain(dest);
        UUID second = anotherOrder();
        var a = approval(old, "INDEPENDENT_PILOT", second, dest);
        pin(a);
        read(decision(old, a));
        allowSuccess();
        UUID quote = quote(second, dest);
        UUID fresh =
                UUID.fromString(
                        read(request(second, dest, UUID.randomUUID(), quote)).path("id").asText());
        read(auth(post(base() + "/merchant-payouts/" + fresh + "/reconcile")));
        var late =
                new sn.groupia.settlement.domain.service.MerchantPayoutProvider.Proof(
                        true,
                        "SASPAY",
                        "fixture",
                        old.businessReference(),
                        "VERIFIED-HISTORICAL",
                        old.amount(),
                        "XOF",
                        "WAVE",
                        old.phoneE164(),
                        "SUCCEEDED",
                        clock.instant(),
                        new BigDecimal("37"));
        policy.verified(org, old.id(), late);
        policy.verified(org, old.id(), late);
        assertThat(balance().path("grossMerchantCollections").decimalValue())
                .isEqualByComparingTo("2000");
        assertThat(balance().path("merchantPayouts").decimalValue()).isEqualByComparingTo("2000");
        assertThat(balance().path("reservedForPayout").decimalValue()).isZero();
        assertThat(balance().path("availableForPayout").decimalValue()).isZero();
        assertThat(feeStore.payout(org, old.id()).providerIdempotencyKey())
                .isEqualTo(old.providerIdempotencyKey());
        assertThat(provider.sends).hasValue(2); // verification never sends another transfer
    }

    @Test
    void knownProviderReferenceAndUnprivilegedOperatorCannotClosePayout() throws Exception {
        UUID dest = destination();
        var old = uncertain(dest);
        policy.initiated(org, old.id(), "KNOWN-PROVIDER-ID");
        var withId = feeStore.payout(org, old.id());
        var a = approval(withId, "CLOSE_NON_EXECUTED", null, null);
        pin(a);
        decision(withId, a)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value("PAYOUT_RESOLUTION_STATE_CHANGED"));
        jdbc.update(
                "update organization_members set role='VIEWER' where organization_id=? and user_account_id=?",
                org,
                actor);
        decision(withId, a).andExpect(status().isForbidden());
        assertThat(feeStore.payout(org, old.id()).reserved()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from merchant_payout_administrations",
                                Integer.class))
                .isZero();
    }

    private String token(UUID id) {
        var claims =
                JwtClaimsSet.builder()
                        .claim("sid", UUID.randomUUID().toString())
                        .issuer("https://groupia.test")
                        .audience(List.of("groupia-api-test"))
                        .subject(id.toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(1800))
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
