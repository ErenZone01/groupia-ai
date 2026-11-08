package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.settlement.domain.service.MarketplaceSimulationGateway;
import sn.groupia.settlement.infrastructure.provider.LocalMarketplaceSimulator;
import tools.jackson.databind.*;

@SpringBootTest(
        properties = {
            "groupia.marketplace.simulation.enabled=true",
            "groupia.marketplace.simulation.company-budget-xof=200"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PaymentTestConfiguration.class})
class MarketplaceSimulationIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    @MockitoBean CollectionFacts facts;
    @MockitoBean PaymentOperations payments;
    @MockitoSpyBean LocalMarketplaceSimulator gateway;
    UUID org, actor, collection;
    String jwt;
    CollectionFacts.Fact fact;

    @BeforeEach
    void setup() throws Exception {
        jdbc.execute("TRUNCATE organizations,user_accounts,event_publication CASCADE");
        actor = UUID.randomUUID();
        jdbc.update(
                "insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at) values(?,'+221771234567',?,'ACTIVE',now(),now())",
                actor,
                passwords.encode("Simulation fixture 2026!"));
        jwt = token(actor);
        org =
                UUID.fromString(
                        json.readTree(
                                        auth(
                                                        post("/api/organizations"),
                                                        Map.of("name", "Simulation"))
                                                .andExpect(status().isCreated())
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString())
                                .get("id")
                                .asText());
        collection = UUID.randomUUID();
        fact =
                fact(
                        collection,
                        "SASPAY",
                        "merchant-one",
                        PaymentTarget.directOrder(UUID.randomUUID()),
                        PaymentPurpose.DIRECT_ORDER_INITIAL,
                        true);
        configure(fact);
        when(facts.settledRequests(eq(org), isNull(), eq(100))).thenReturn(List.of(collection));
        auth(post(base() + "/refresh")).andExpect(status().isOk());
    }

    private CollectionFacts.Fact fact(
            UUID id,
            String provider,
            String account,
            PaymentTarget target,
            PaymentPurpose purpose,
            boolean fees) {
        var proof =
                new PaymentSettlementEvidence(
                        org,
                        id,
                        UUID.randomUUID(),
                        target,
                        purpose,
                        new BigDecimal("20000"),
                        "XOF",
                        AcceptanceStatus.CLOSED,
                        PaymentStatus.SUCCEEDED,
                        provider,
                        account,
                        "tx-" + id,
                        "GP-" + id,
                        Instant.parse("2026-11-08T00:00:00Z"),
                        null,
                        false);
        return new CollectionFacts.Fact(
                proof,
                new BigDecimal("20000"),
                BigDecimal.ZERO,
                fees ? new BigDecimal("500") : null,
                fees ? new BigDecimal("20500") : null);
    }

    private void configure(CollectionFacts.Fact f) {
        UUID id = f.evidence().paymentRequestId();
        when(facts.read(org, id)).thenReturn(f);
        var state = new PaymentRequestState(null, PaymentOperationalStatus.SETTLED, false, false);
        when(payments.requestState(org, id)).thenReturn(state);
        when(payments.lockRequestState(org, id)).thenReturn(state);
        when(payments.settlementEvidence(org, id)).thenReturn(f.evidence());
    }

    @Test
    void immutableLedgerSeparatesProvidersAccountsAndUnknownFees() throws Exception {
        var second = UUID.randomUUID();
        var third = UUID.randomUUID();
        configure(
                fact(
                        second,
                        "SASPAY",
                        "merchant-two",
                        PaymentTarget.order(UUID.randomUUID()),
                        PaymentPurpose.INITIAL_ORDER,
                        true));
        configure(
                fact(
                        third,
                        "BICTORYS",
                        "merchant-one",
                        PaymentTarget.order(UUID.randomUUID()),
                        PaymentPurpose.SHIPPING_BALANCE,
                        false));
        when(facts.settledRequests(eq(org), isNull(), eq(100)))
                .thenReturn(List.of(collection, second, third));
        assertThat(read(auth(post(base() + "/refresh"))).path("recorded").asInt()).isEqualTo(2);
        assertThat(read(auth(post(base() + "/refresh"))).path("recorded").asInt()).isZero();
        var rows = read(auth(get(base()))).path("items");
        assertThat(rows.size()).isEqualTo(3);
        var accounts = new HashSet<String>();
        int unknown = 0;
        for (var row : rows) {
            accounts.add(
                    row.path("provider").asText() + ":" + row.path("accountReference").asText());
            if (row.path("provider").asText().equals("BICTORYS")) {
                assertThat(row.path("collectionFee").isNull()).isTrue();
                unknown++;
            }
        }
        assertThat(accounts).hasSize(3);
        assertThat(unknown).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update provider_collections set principal=1 where id=?",
                                        collection))
                .hasMessageContaining("immutable");
        var changed =
                new CollectionFacts.Fact(
                        fact.evidence(),
                        new BigDecimal("19900"),
                        new BigDecimal("100"),
                        fact.collectionFee(),
                        fact.customerCharged());
        when(facts.read(org, collection)).thenReturn(changed);
        auth(post(base() + "/refresh")).andExpect(status().isInternalServerError());
    }

    @Test
    void payoutPreservesNetAndDuplicateIntentionDoesNotSpendTwice() throws Exception {
        var quote = quote("PAYOUT");
        assertThat(quote.path("recipientNet").asInt()).isEqualTo(20000);
        assertThat(quote.path("fee").asInt()).isEqualTo(200);
        assertThat(quote.path("realOperationsEnabled").asBoolean()).isFalse();
        String key = UUID.randomUUID().toString();
        var result = read(start(key, quote));
        var again = read(start(key, quote));
        assertThat(again).isEqualTo(result);
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.path("mode").asText()).isEqualTo("SIMULATION");
        assertThat(result.path("recipientNet").asInt()).isEqualTo(20000);
        assertThat(result.path("simulatedCompanyFunding").asInt()).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from marketplace_simulated_provider_receipts",
                                Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from merchant_ledger_entries", Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from outgoing_provider_operations", Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select seller_principal from provider_collections where id=?",
                                BigDecimal.class,
                                collection))
                .isEqualByComparingTo("20000");
        start(UUID.randomUUID().toString(), quote).andExpect(status().isConflict());
        auth(post(root() + "/marketplace-simulations/" + result.path("id").asText() + "/reconcile"))
                .andExpect(status().isOk());
        verify(gateway, times(1)).execute(any());
        UUID operation = UUID.fromString(result.path("id").asText());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update marketplace_simulations set recipient_net=19800 where id=?",
                                        operation))
                .hasMessageContaining("immutable");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update marketplace_simulations set status='CREATED',completed_at=null where id=?",
                                        operation))
                .hasMessageContaining("cannot regress");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "delete from marketplace_simulations where id=?",
                                        operation))
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void refundIsExplicitSimulationAndDoesNotClaimToReturnPayinFees() throws Exception {
        var quote = quote("REFUND");
        var result = read(start(UUID.randomUUID().toString(), quote));
        assertThat(result.path("kind").asText()).isEqualTo("REFUND");
        assertThat(result.path("recipientNet").asInt()).isEqualTo(20000);
        assertThat(jdbc.queryForObject("select count(*) from payment_refunds", Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select collection_fee from provider_collections where id=?",
                                BigDecimal.class,
                                collection))
                .isEqualByComparingTo("500");
    }

    @Test
    void lostResponseReconcilesDurableReceiptWithoutAnotherSend() throws Exception {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (reads.incrementAndGet() == 3)
                                throw new IllegalStateException(
                                        "Lost verification response after durable accept");
                            return invocation.callRealMethod();
                        })
                .when(gateway)
                .verify(any());
        var quote = quote("PAYOUT");
        var result = read(start(UUID.randomUUID().toString(), quote));
        assertThat(result.path("status").asText()).isEqualTo("REVIEW_REQUIRED");
        var recovered =
                read(
                        auth(
                                post(
                                        root()
                                                + "/marketplace-simulations/"
                                                + result.path("id").asText()
                                                + "/reconcile")));
        assertThat(recovered.path("status").asText()).isEqualTo("SUCCEEDED");
        verify(gateway, times(1)).execute(any());
        assertThat(
                        jdbc.queryForList(
                                "select status from marketplace_simulation_events order by occurred_at",
                                String.class))
                .containsExactly("CREATED", "REVIEW_REQUIRED", "SUCCEEDED");
    }

    @Test
    void mismatchedProofRetainsReservationUntilVerified() throws Exception {
        doAnswer(
                        i -> {
                            MarketplaceSimulationGateway.Command c = i.getArgument(0);
                            return new MarketplaceSimulationGateway.Proof(
                                    c,
                                    "SUCCESS",
                                    "SIM-" + c.id(),
                                    new BigDecimal("19800"),
                                    new BigDecimal("20200"));
                        })
                .when(gateway)
                .verify(any());
        var quote = quote("PAYOUT");
        var result = read(start(UUID.randomUUID().toString(), quote));
        assertThat(result.path("status").asText()).isEqualTo("REVIEW_REQUIRED");
        start(UUID.randomUUID().toString(), quote).andExpect(status().isConflict());
        doCallRealMethod().when(gateway).verify(any());
        var recovered =
                read(
                        auth(
                                post(
                                        root()
                                                + "/marketplace-simulations/"
                                                + result.path("id").asText()
                                                + "/reconcile")));
        assertThat(recovered.path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void verifiedFailureReleasesReservationAndTerminalSuccessNeverRegresses() throws Exception {
        doAnswer(
                        i -> {
                            MarketplaceSimulationGateway.Command c = i.getArgument(0);
                            return new MarketplaceSimulationGateway.Proof(
                                    c,
                                    "FAILED",
                                    "SIM-" + c.id(),
                                    c.recipientNet(),
                                    c.recipientNet().add(c.fee()));
                        })
                .when(gateway)
                .verify(any());
        var quote = quote("PAYOUT");
        var failed = read(start(UUID.randomUUID().toString(), quote));
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        doCallRealMethod().when(gateway).verify(any());
        var success = read(start(UUID.randomUUID().toString(), quote));
        assertThat(success.path("status").asText()).isEqualTo("SUCCEEDED");
        var again =
                read(
                        auth(
                                post(
                                        root()
                                                + "/marketplace-simulations/"
                                                + failed.path("id").asText()
                                                + "/reconcile")));
        assertThat(again.path("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void concurrentRequestsConsumeOneSourceAndCompanyBudgetIsSeparate() throws Exception {
        var quote = quote("PAYOUT");
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var a =
                    pool.submit(
                            () -> {
                                gate.await();
                                return start(UUID.randomUUID().toString(), quote)
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            var b =
                    pool.submit(
                            () -> {
                                gate.await();
                                return start(UUID.randomUUID().toString(), quote)
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            gate.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        UUID second = UUID.randomUUID();
        configure(
                fact(
                        second,
                        "SASPAY",
                        "another-account",
                        PaymentTarget.order(UUID.randomUUID()),
                        PaymentPurpose.INITIAL_ORDER,
                        true));
        when(facts.settledRequests(eq(org), isNull(), eq(100))).thenReturn(List.of(second));
        auth(post(base() + "/refresh")).andExpect(status().isOk());
        collection = second;
        start(UUID.randomUUID().toString(), quote("PAYOUT"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value("SIMULATION_FEE_FUNDING_REQUIRED"));
    }

    @Test
    void permissionsIsolationQuoteAndReconciliationBlockUnsafeActions() throws Exception {
        var quote = quote("PAYOUT");
        var wrong = json.createObjectNode();
        wrong.put("kind", "PAYOUT");
        wrong.put("reference", "wrong");
        start(UUID.randomUUID().toString(), wrong).andExpect(status().isConflict());
        when(payments.lockRequestState(org, collection))
                .thenReturn(
                        new PaymentRequestState(
                                null, PaymentOperationalStatus.REVIEW_REQUIRED, false, true));
        start(UUID.randomUUID().toString(), quote)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value("SIMULATION_SOURCE_UNCERTAIN"));
        auth(get("/api/organizations/" + UUID.randomUUID() + "/provider-collections"))
                .andExpect(status().isForbidden());
        jdbc.update(
                "update organization_members set role='VIEWER' where organization_id=? and user_account_id=?",
                org,
                actor);
        auth(get(base())).andExpect(status().isOk());
        start(UUID.randomUUID().toString(), quote).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from marketplace_simulations", Long.class))
                .isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "provider",
                "account",
                "currency",
                "organization",
                "collection",
                "kind",
                "principal",
                "fee",
                "net",
                "debited",
                "reference",
                "status"
            })
    void mismatchedSimulationEvidenceNeverSucceeds(String field) throws Exception {
        doAnswer(
                        invocation -> {
                            MarketplaceSimulationGateway.Command c = invocation.getArgument(0);
                            var other =
                                    new MarketplaceSimulationGateway.Command(
                                            c.id(),
                                            field.equals("organization")
                                                    ? UUID.randomUUID()
                                                    : c.organizationId(),
                                            field.equals("collection")
                                                    ? UUID.randomUUID()
                                                    : c.collectionId(),
                                            field.equals("kind") ? "REFUND" : c.kind(),
                                            field.equals("provider")
                                                    ? "BICTORYS"
                                                    : c.sourceProvider(),
                                            field.equals("account")
                                                    ? "different-account"
                                                    : c.sourceAccount(),
                                            field.equals("currency") ? "EUR" : c.currency(),
                                            field.equals("principal")
                                                    ? BigDecimal.ONE
                                                    : c.recipientNet(),
                                            field.equals("fee") ? BigDecimal.ZERO : c.fee());
                            return new MarketplaceSimulationGateway.Proof(
                                    other,
                                    field.equals("status") ? null : "SUCCESS",
                                    field.equals("reference") ? "unrelated" : "SIM-" + c.id(),
                                    field.equals("net") ? BigDecimal.ONE : c.recipientNet(),
                                    field.equals("debited")
                                            ? c.recipientNet()
                                            : c.recipientNet().add(c.fee()));
                        })
                .when(gateway)
                .verify(any());
        var result = read(start(UUID.randomUUID().toString(), quote("PAYOUT")));
        assertThat(result.path("status").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from marketplace_simulations where status='SUCCEEDED'",
                                Long.class))
                .isZero();
    }

    @Test
    void simulatedAmountsCannotBeSuppliedByTheClient() throws Exception {
        var quote = quote("PAYOUT");
        auth(
                        post(base() + "/" + collection + "/simulations")
                                .header("Idempotency-Key", UUID.randomUUID().toString()),
                        Map.of(
                                "kind",
                                "PAYOUT",
                                "quoteReference",
                                quote.path("reference").asText(),
                                "confirmed",
                                true,
                                "recipientNet",
                                1))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from marketplace_simulations", Long.class))
                .isZero();
    }

    private JsonNode quote(String kind) throws Exception {
        return read(auth(get(base() + "/" + collection + "/simulation-quote").param("kind", kind)));
    }

    private ResultActions start(String key, JsonNode q) throws Exception {
        return auth(
                post(base() + "/" + collection + "/simulations").header("Idempotency-Key", key),
                Map.of(
                        "kind",
                        q.path("kind").asText(),
                        "quoteReference",
                        q.path("reference").asText(),
                        "confirmed",
                        true));
    }

    private String root() {
        return "/api/organizations/" + org;
    }

    private String base() {
        return root() + "/provider-collections";
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

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(
                result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
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
