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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import tools.jackson.databind.*;

/** All transfers in this test use a synthetic provider and isolated PostgreSQL. */
@SpringBootTest(properties = {"groupia.settlement.payouts.enabled=true",
        "groupia.settlement.direct-payouts.enabled=true"})
@AutoConfigureMockMvc
@Import({
    TestcontainersConfiguration.class,
    PaymentTestConfiguration.class,
    MerchantPayoutTestConfiguration.class
})
class UnifiedMerchantPayoutIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    @Autowired MerchantPayoutTestConfiguration.FakeMerchantProvider provider;
    @MockitoBean PaymentOperations payments;
    @MockitoBean CommercialRefunds refunds;
    UUID org, actor, orderId, requestId, paymentId;
    String jwt;

    @BeforeEach
    void setup() {
        jdbc.execute(
                "TRUNCATE merchant_ledger_entries,merchant_payouts,merchant_payout_destinations,"
                        + "outgoing_provider_operations,provider_collections,direct_orders,"
                        + "direct_storefronts,organizations,user_accounts,event_publication CASCADE");
        provider.reset();
        actor = UUID.randomUUID();
        org = UUID.randomUUID();
        orderId = UUID.randomUUID();
        requestId = UUID.randomUUID();
        paymentId = UUID.randomUUID();
        var storefront = UUID.randomUUID();
        jdbc.update(
                "insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at)"
                        + " values (?,? ,?,'ACTIVE',now(),now())",
                actor, "+221771234567", passwords.encode("Unified payout fixture 2026!"));
        jdbc.update(
                "insert into organizations(id,name,status,created_at,updated_at)"
                        + " values (?,'Boutique de test','ACTIVE',now(),now())", org);
        jdbc.update(
                "insert into organization_members(id,organization_id,user_account_id,role,status,joined_at)"
                        + " values (?,?,?,'OWNER','ACTIVE',now())",
                UUID.randomUUID(), org, actor);
        jwt = token(actor);
        jdbc.update(
                "insert into direct_storefronts(id,organization_id,public_identifier,display_name,"
                        + "created_at,updated_at) values (?,?,?,'Vitrine de test',now(),now())",
                storefront, org, UUID.randomUUID());
        String number = "DIR-" + String.format(
                "%012d", Math.floorMod(orderId.getMostSignificantBits(), 1_000_000_000_000L));
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
                    20000,0,'{"type":"FIXED","amount":0}'::jsonb,
                    20000,true,?,repeat('a',64),now()-interval '15 minutes',
                    0,20000,now()-interval '10 minutes',
                    now()-interval '9 minutes',?,
                    now()-interval '8 minutes',?,
                    now()-interval '7 minutes',now()-interval '6 minutes')
                """,
                orderId, org, storefront, number, UUID.randomUUID().toString(), actor, actor);
        jdbc.update(
                """
                insert into provider_collections(
                    id,organization_id,payment_request_id,payment_id,target_type,target_id,
                    purpose,provider,provider_account,provider_transaction_id,currency,
                    principal,seller_principal,platform_fee,confirmed_at)
                values (?,?,?,?,'DIRECT_ORDER',?,'DIRECT_ORDER_INITIAL',
                    'SIMULATED','test-merchant',?,'XOF',20000,20000,0,now()-interval '10 minutes')
                """,
                requestId, org, requestId, paymentId, orderId, "TX-" + paymentId);
        var proof = verified(false);
        when(payments.settlementEvidence(org, requestId)).thenReturn(proof);
        when(payments.requestState(org, requestId))
                .thenReturn(new PaymentRequestState(
                        null, PaymentOperationalStatus.SETTLED, false, false));
    }

    private PaymentSettlementEvidence verified(boolean reversed) {
        return new PaymentSettlementEvidence(
                org, requestId, paymentId, PaymentTarget.directOrder(orderId),
                PaymentPurpose.DIRECT_ORDER_INITIAL, new BigDecimal("20000"),
                "XOF", AcceptanceStatus.CLOSED, PaymentStatus.SUCCEEDED,
                "SIMULATED", "test-merchant", "TX-" + paymentId,
                "GP-" + paymentId, Instant.now().minusSeconds(600), null, reversed);
    }

    private String base() {
        return "/api/organizations/" + org;
    }

    private ResultActions auth(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + jwt));
    }

    private ResultActions auth(MockHttpServletRequestBuilder request, Object body)
            throws Exception {
        return auth(request.contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode read(ResultActions action) throws Exception {
        return json.readTree(
                action.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private UUID destination() throws Exception {
        return UUID.fromString(
                read(auth(put(base() + "/payout-destination"),
                        Map.of("method", "WAVE", "phoneE164", "+221771234567",
                                "holderName", "Vendeuse", "confirmed", true)))
                        .path("id").asText());
    }

    private ResultActions request(UUID destination, UUID key) throws Exception {
        return auth(
                post(base() + "/direct-orders/" + orderId + "/merchant-payouts")
                        .header("Idempotency-Key", key),
                Map.of("destinationId", destination, "confirmed", true));
    }

    @Test
    void completedDirectSaleUsesSamePayoutEngineAndDebitsOnlyOnce() throws Exception {
        var before = read(auth(get(base() + "/merchant-balance")));
        assertThat(before.path("directOrders").size()).isEqualTo(1);
        assertThat(before.path("directOrders").get(0).path("availableForPayout").decimalValue())
                .isEqualByComparingTo("20000");
        assertThat(before.path("availableForPayout").decimalValue()).isEqualByComparingTo("20000");
        UUID dest = destination(), key = UUID.randomUUID();
        var first = read(request(dest, key));
        var replay = read(request(dest, key));
        assertThat(replay.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(first.path("campaignId").isNull()).isTrue();
        assertThat(first.path("directOrderId").asText()).isEqualTo(orderId.toString());
        assertThat(provider.sends).hasValue(1);
        assertThat(read(auth(get(base() + "/merchant-balance")))
                        .path("directOrders").get(0).path("availableForPayout").decimalValue())
                .isZero();
        UUID payout = UUID.fromString(first.path("id").asText());
        read(auth(post(base() + "/merchant-payouts/" + payout + "/reconcile")));
        read(auth(post(base() + "/merchant-payouts/" + payout + "/reconcile")));
        assertThat(read(auth(get(base() + "/merchant-balance")))
                        .path("directOrders").get(0).path("merchantPayouts").decimalValue())
                .isEqualByComparingTo("20000");
        assertThat(jdbc.queryForObject(
                        "select count(*) from merchant_payouts where organization_id=? and direct_order_id=?",
                        Long.class, org, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from merchant_ledger_entries where organization_id=?",
                        Long.class, org)).isZero();
        assertThat(provider.sends).hasValue(1);
        request(dest, UUID.randomUUID()).andExpect(status().isConflict());
    }

    @Test
    void pendingUncertainPayoutReservesDirectFundsAndCannotBeResent() throws Exception {
        UUID dest = destination(), key = UUID.randomUUID();
        provider.timeout = true;
        var first = read(request(dest, key));
        assertThat(first.path("reconciliationRequired").asBoolean()).isTrue();
        read(request(dest, key));
        assertThat(provider.sends).hasValue(1);
        request(dest, UUID.randomUUID()).andExpect(status().isConflict());
        assertThat(read(auth(get(base() + "/merchant-balance")))
                        .path("directOrders").get(0).path("reservedForPayout").decimalValue())
                .isEqualByComparingTo("20000");
        assertThat(provider.sends).hasValue(1);
    }

    @Test
    void directFundsMustBeCompletedVerifiedAndOnSameSourceAccount() throws Exception {
        UUID dest = destination();
        jdbc.update("update direct_orders set status='PICKED_UP',completed_at=null where id=?", orderId);
        request(dest, UUID.randomUUID()).andExpect(status().isConflict());
        jdbc.update("update direct_orders set status='COMPLETED',completed_at=now() where id=?", orderId);
        when(payments.settlementEvidence(org, requestId)).thenReturn(verified(true));
        request(dest, UUID.randomUUID()).andExpect(status().isConflict());
        when(payments.settlementEvidence(org, requestId)).thenReturn(verified(false));
        provider.sourceAccount = "wrong-wallet";
        request(dest, UUID.randomUUID()).andExpect(status().isConflict());
        assertThat(provider.sends).hasValue(0);
        auth(get("/api/organizations/" + UUID.randomUUID() + "/merchant-balance"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pendingCommercialRefundBlocksDirectPayoutEvenWhenOrderStillLooksCompleted()
            throws Exception {
        UUID dest = destination();
        when(refunds.find(org, PaymentTarget.directOrder(orderId)))
                .thenReturn(
                        new CommercialRefunds.CaseView(
                                UUID.randomUUID(),
                                PaymentTarget.directOrder(orderId),
                                "REFUND_DUE",
                                "XOF",
                                new BigDecimal("20000"),
                                BigDecimal.ZERO,
                                new BigDecimal("20000"),
                                BigDecimal.ZERO,
                                "Annulation concurrente",
                                Instant.now(),
                                null,
                                null,
                                null,
                                0L));
        request(dest, UUID.randomUUID()).andExpect(status().isConflict());
        assertThat(provider.sends).hasValue(0);
    }

    @Test
    void cannotReuseDirectPayoutIdempotencyKeyForCampaignPath() throws Exception {
        UUID dest = destination(), key = UUID.randomUUID();
        read(request(dest, key));
        // The key represents one immutable source, not a reusable payout authorization.
        auth(
                        post(base()
                                        + "/grouping-campaigns/"
                                        + UUID.randomUUID()
                                        + "/merchant-payouts")
                                .header("Idempotency-Key", key),
                        Map.of("destinationId", dest, "confirmed", true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value("PAYOUT_IDEMPOTENCY_CONFLICT"));
        assertThat(provider.sends).hasValue(1);
    }

    private String token(UUID id) {
        var claims = JwtClaimsSet.builder()
                .claim("sid", UUID.randomUUID().toString())
                .issuer("https://groupia.test")
                .audience(List.of("groupia-api-test"))
                .subject(id.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(1800))
                .build();
        return encoder.encode(
                JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
