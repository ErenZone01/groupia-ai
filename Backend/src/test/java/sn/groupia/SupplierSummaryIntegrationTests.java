package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import sn.groupia.organization.api.*;
import sn.groupia.shared.api.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SupplierSummaryIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    @Autowired EntityManagerFactory emf;
    @MockitoSpyBean NamedParameterJdbcTemplate summaryJdbc;
    @MockitoSpyBean OrganizationAccess access;
    private static String hash;
    private UUID actor, org, product, variant;
    private String jwt;
    private int customerSequence;
    private JsonNode campaign;

    @BeforeEach
    void setup() throws Exception {
        TestEventDrain.awaitIdle(jdbc);
        jdbc.execute(
                "TRUNCATE TABLE outgoing_provider_operations,order_fulfillment_events, shipping_calculations, shipping_measurements, shipping_assessments, customer_access_sessions, otp_challenges, order_access_links, customer_access_limits, shipment_facts, shipments, supplier_order_anomalies, supplier_orders, payment_webhook_events, payments, payment_requests, order_items, orders, customers, grouping_changes, grouping_offers, grouping_campaigns, product_variants, products, organization_members, organizations, user_accounts, event_publication CASCADE");
        if (hash == null) hash = passwords.encode("Supplier fixture 2026!");
        actor = UUID.randomUUID();
        jdbc.update(
                "insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at) values (?,'+221771234567',?,'ACTIVE',current_timestamp,current_timestamp)",
                actor,
                hash);
        jwt = token(actor);
        org =
                UUID.fromString(
                        read(auth(post("/api/organizations"), Map.of("name", "Boutique"))
                                        .andExpect(status().isCreated())
                                        .andReturn())
                                .path("id")
                                .asText());
        var p = product(org, Map.of("color", "Noir", "size", "M"));
        product = UUID.fromString(p.path("id").asText());
        variant = UUID.fromString(p.path("variants").get(0).path("id").asText());
        campaign = createCampaign(org, variant);
    }

    @Test
    void emptySummaryIsXofAndContainsNoPrivateData() throws Exception {
        summary()
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.groupingCampaignId").value(campaign.path("id").asText()))
                .andExpect(jsonPath("$.acceptedOrderCount").value(0))
                .andExpect(jsonPath("$.totalUnits").value(0))
                .andExpect(jsonPath("$.goodsAmount").value(0))
                .andExpect(jsonPath("$.currency").value("XOF"))
                .andExpect(jsonPath("$.lines").isEmpty())
                .andExpect(jsonPath("$.customerContactSnapshot").doesNotExist());
    }

    @Test
    void confirmedIncludesOnlyGoodsAndImmutableSnapshots() throws Exception {
        order(campaign, offer(), 2, 5000, 0, "CONFIRMED");
        var body = read(summary().andExpect(status().isOk()).andReturn());
        assertThat(body.path("acceptedOrderCount").asLong()).isEqualTo(1);
        assertThat(body.path("totalUnits").asLong()).isEqualTo(2);
        assertThat(body.path("goodsAmount").decimalValue()).isEqualByComparingTo("10000");
        assertThat(body.path("currency").asText()).isEqualTo("XOF");
        assertThat(
                        body.path("lines")
                                .get(0)
                                .path("variantAttributesSnapshot")
                                .path("color")
                                .asText())
                .isEqualTo("noir");
        assertThat(body.toString())
                .doesNotContain(
                        "trackingToken",
                        "phone",
                        "email",
                        "PaymentRequest",
                        "merchantReference",
                        "platformFeeAmount");
        assertThat(jdbc.queryForObject("select initial_amount_due from orders", BigDecimal.class))
                .isEqualByComparingTo("11300");
    }

    @Test
    void sameVariantAcrossCustomersAggregates() throws Exception {
        order(campaign, offer(), 2, 5000, 0, "CONFIRMED");
        order(campaign, offer(), 5, 5000, 0, "CONFIRMED");
        summary()
                .andExpect(jsonPath("$.acceptedOrderCount").value(2))
                .andExpect(jsonPath("$.totalUnits").value(7))
                .andExpect(jsonPath("$.goodsAmount").value(35000))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].totalQuantity").value(7));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_PAYMENT", "EXPIRED", "CANCELLED"})
    void unacceptedStatusesAreExcluded(String state) throws Exception {
        order(campaign, offer(), 9, 5000, 0, state);
        summary()
                .andExpect(jsonPath("$.acceptedOrderCount").value(0))
                .andExpect(jsonPath("$.lines").isEmpty());
    }

    @Test
    void completedRetainsTheAcceptedSale() throws Exception {
        order(campaign, offer(), 3, 5000, 0, "COMPLETED");
        summary()
                .andExpect(jsonPath("$.acceptedOrderCount").value(1))
                .andExpect(jsonPath("$.totalUnits").value(3))
                .andExpect(jsonPath("$.goodsAmount").value(15000));
    }

    @Test
    void distinctProductsWithSameNamesAndAttributesAreNotMerged() throws Exception {
        var p = product(org, Map.of("color", "Noir", "size", "M"));
        // Identical public snapshots, including absent SKU: only stable IDs distinguish products.
        jdbc.update("update product_variants set sku=null where organization_id=?", org);
        String second = addOffer(UUID.fromString(p.path("variants").get(0).path("id").asText()));
        order(campaign, offer(), 1, 5000, 0, "CONFIRMED");
        order(campaign, second, 2, 5000, 0, "CONFIRMED");
        summary()
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.totalUnits").value(3));
    }

    @Test
    void distinctVariantsOfSameProductAreNotMerged() throws Exception {
        var second =
                read(
                        auth(
                                        post(
                                                "/api/organizations/"
                                                        + org
                                                        + "/products/"
                                                        + product
                                                        + "/variants"),
                                        Map.of(
                                                "version",
                                                0,
                                                "variant",
                                                Map.of(
                                                        "attributes",
                                                        Map.of("color", "Blanc", "size", "L"),
                                                        "saleUnit",
                                                        "UNIT")))
                                .andExpect(status().isOk())
                                .andReturn());
        UUID secondVariant = null;
        for (var v : second.path("variants"))
            if (v.path("attributes").path("color").asText().equals("blanc"))
                secondVariant = UUID.fromString(v.path("id").asText());
        assertThat(secondVariant).isNotNull();
        String secondOffer = addOffer(secondVariant);
        order(campaign, offer(), 1, 5000, 0, "CONFIRMED");
        order(campaign, secondOffer, 2, 5000, 0, "CONFIRMED");
        summary()
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.totalUnits").value(3));
    }

    @Test
    void currentCatalogueAndOfferChangesDoNotRewriteHistory() throws Exception {
        order(campaign, offer(), 2, 5000, 0, "CONFIRMED");
        var before = read(summary().andReturn());
        jdbc.update(
                "update products set name='Nouveau nom',description='Nouvelle description' where id=?",
                product);
        jdbc.update(
                "update product_variants set active=false,attributes='{\"color\":\"Rouge\"}'::jsonb where id=?",
                variant);
        jdbc.update(
                "update grouping_offers set unit_price=7000,price_revision=1 where id=?",
                UUID.fromString(offer()));
        assertThat(read(summary().andReturn())).isEqualTo(before);
    }

    @Test
    void acceptedDifferentPricesSumHistoricalLineTotalsWithoutSplittingByPrice() throws Exception {
        order(campaign, offer(), 2, 5000, 0, "CONFIRMED");
        jdbc.update(
                "update grouping_offers set unit_price=7000,price_revision=1 where id=?",
                UUID.fromString(offer()));
        order(campaign, offer(), 1, 7000, 1, "CONFIRMED");
        summary()
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.totalUnits").value(3))
                .andExpect(jsonPath("$.goodsAmount").value(17000));
    }

    @Test
    void changedDescriptionsProduceSeparateHistoricalSnapshotPartitions() throws Exception {
        order(campaign, offer(), 1, 5000, 0, "CONFIRMED");
        jdbc.update("update products set name='Chemise renommée' where id=?", product);
        order(campaign, offer(), 1, 5000, 0, "CONFIRMED");
        summary()
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.goodsAmount").value(10000));
    }

    @Test
    void expiredPendingOrderNeverChangesAcceptedSummary() throws Exception {
        order(campaign, offer(), 1, 5000, 0, "CONFIRMED");
        String pending = order(campaign, offer(), 4, 5000, 0, "PENDING_PAYMENT");
        var before = read(summary().andReturn());
        jdbc.update("update orders set status='EXPIRED' where order_number=?", pending);
        assertThat(read(summary().andReturn())).isEqualTo(before);
    }

    @Test
    void organizationAndCampaignScopesAreBothRequired() throws Exception {
        var other =
                UUID.fromString(
                        read(auth(post("/api/organizations"), Map.of("name", "Autre")).andReturn())
                                .path("id")
                                .asText());
        var p = product(other, Map.of("color", "Noir"));
        var c =
                createCampaign(
                        other, UUID.fromString(p.path("variants").get(0).path("id").asText()));
        order(c, c.path("offers").get(0).path("id").asText(), 7, 5000, 0, "CONFIRMED");
        summary().andExpect(jsonPath("$.acceptedOrderCount").value(0));
        auth(get(path(other, campaign.path("id").asText())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GROUPING_CAMPAIGN_NOT_FOUND"));
        auth(get(path(org, c.path("id").asText()))).andExpect(status().isNotFound());
        auth(get(path(other, c.path("id").asText()))).andExpect(jsonPath("$.totalUnits").value(7));
    }

    @Test
    void anonymousAndInactiveMembershipAreDenied() throws Exception {
        mvc.perform(get(path(org, campaign.path("id").asText())))
                .andExpect(status().isUnauthorized());
        jdbc.update(
                "update organization_members set status='REVOKED', revoked_at=current_timestamp where organization_id=?",
                org);
        summary().andExpect(status().isForbidden());
    }

    @Test
    void inactiveUserIsDenied() throws Exception {
        jdbc.update("update user_accounts set status='DISABLED' where id=?", actor);
        summary().andExpect(status().isUnauthorized());
    }

    @Test
    void inaccessibleOrganizationIsDenied() throws Exception {
        jdbc.update("delete from organization_members where organization_id=?", org);
        summary().andExpect(status().isForbidden());
    }

    @Test
    void archivedOrganizationIsDenied() throws Exception {
        jdbc.update("update organizations set status='ARCHIVED' where id=?", org);
        summary().andExpect(status().isNotFound());
    }

    @Test
    void anotherCampaignInSameOrganizationIsExcluded() throws Exception {
        var other = createCampaign(org, variant);
        order(other, other.path("offers").get(0).path("id").asText(), 9, 5000, 0, "CONFIRMED");
        summary()
                .andExpect(jsonPath("$.acceptedOrderCount").value(0))
                .andExpect(jsonPath("$.lines").isEmpty());
    }

    @Test
    void explicitlyRequiresViewOrdersPermission() throws Exception {
        clearInvocations(access);
        summary().andExpect(status().isOk());
        verify(access).requirePermission(org, OrganizationPermission.VIEW_ORDERS);
        doThrow(new ApiException(403, "ORGANIZATION_ACCESS_DENIED", "Interdit"))
                .when(access)
                .requirePermission(org, OrganizationPermission.VIEW_ORDERS);
        summary().andExpect(status().isForbidden());
    }

    @Test
    void constantSqlCountAndNoOrderAggregateHydration() throws Exception {
        order(campaign, offer(), 1, 5000, 0, "CONFIRMED");
        long one = readCounted();
        for (int i = 0; i < 25; i++) order(campaign, offer(), 1, 5000, 0, "CONFIRMED");
        long many = readCounted();
        assertThat(many).isEqualTo(one).isEqualTo(5);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private long readCounted() throws Exception {
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        clearInvocations(summaryJdbc);
        try {
            summary().andExpect(status().isOk());
            var sql = ArgumentCaptor.forClass(String.class);
            verify(summaryJdbc, times(1))
                    .query(sql.capture(), anyMap(), any(ResultSetExtractor.class));
            assertThat(sql.getValue()).doesNotContain("JOIN products", "JOIN product_variants");
            assertThat(stats.getCollectionFetchCount()).isZero();
            return stats.getPrepareStatementCount()
                    + 1; // Native JDBC aggregate + Hibernate authorization/ownership reads.
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }

    private JsonNode product(UUID owner, Map<String, String> attrs) throws Exception {
        return read(
                auth(
                                post("/api/organizations/" + owner + "/products"),
                                Map.of(
                                        "name",
                                        "Chemise",
                                        "description",
                                        "Description historique",
                                        "variants",
                                        List.of(
                                                Map.of(
                                                        "attributes",
                                                        attrs,
                                                        "sku",
                                                        "SKU-" + UUID.randomUUID(),
                                                        "saleUnit",
                                                        "UNIT"))))
                        .andExpect(status().isCreated())
                        .andReturn());
    }

    private JsonNode createCampaign(UUID owner, UUID variantId) throws Exception {
        String base = "/api/organizations/" + owner + "/grouping-campaigns";
        var c =
                read(
                        auth(
                                        post(base),
                                        Map.of(
                                                "title",
                                                "Groupage",
                                                "shippingTerms",
                                                Map.of(
                                                        "transportMode",
                                                        "AIR",
                                                        "shippingPricingMethod",
                                                        "FIXED",
                                                        "shippingCollectionMode",
                                                        "ESTIMATED_AT_CHECKOUT",
                                                        "shippingRate",
                                                        1000)))
                                .andExpect(status().isCreated())
                                .andReturn());
        c =
                read(
                        auth(
                                        post(base + "/" + c.path("id").asText() + "/offers"),
                                        Map.of(
                                                "campaignVersion",
                                                c.path("version").asLong(),
                                                "productVariantId",
                                                variantId,
                                                "unitPrice",
                                                5000,
                                                "commercialLabel",
                                                "Même libellé"))
                                .andExpect(status().isOk())
                                .andReturn());
        return read(
                auth(
                                post(base + "/" + c.path("id").asText() + "/open"),
                                Map.of("version", c.path("version").asLong()))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    private String addOffer(UUID variantId) throws Exception {
        campaign =
                read(
                        auth(
                                        post(
                                                "/api/organizations/"
                                                        + org
                                                        + "/grouping-campaigns/"
                                                        + campaign.path("id").asText()
                                                        + "/offers"),
                                        Map.of(
                                                "campaignVersion",
                                                campaign.path("version").asLong(),
                                                "productVariantId",
                                                variantId,
                                                "unitPrice",
                                                5000,
                                                "commercialLabel",
                                                "Même libellé"))
                                .andExpect(status().isOk())
                                .andReturn());
        return campaign.path("offers").get(campaign.path("offers").size() - 1).path("id").asText();
    }

    private String order(
            JsonNode c, String offer, int quantity, int price, long revision, String state)
            throws Exception {
        var response =
                read(
                        mvc.perform(
                                        post("/api/public/groupings/"
                                                        + c.path("publicIdentifier").asText()
                                                        + "/checkout")
                                                .header(
                                                        "Idempotency-Key",
                                                        UUID.randomUUID().toString())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "contact",
                                                                        Map.of(
                                                                                "firstName",
                                                                                "Awa",
                                                                                "lastName",
                                                                                "Diop",
                                                                                "phone",
                                                                                "+22177"
                                                                                        + String
                                                                                                .format(
                                                                                                        "%07d",
                                                                                                        ++customerSequence)),
                                                                        "lines",
                                                                        List.of(
                                                                                Map.of(
                                                                                        "offerId",
                                                                                        offer,
                                                                                        "quantity",
                                                                                        quantity,
                                                                                        "expectedUnitPrice",
                                                                                        price,
                                                                                        "expectedPriceRevision",
                                                                                        revision))))))
                                .andExpect(status().isCreated())
                                .andReturn());
        String number = response.path("orderNumber").asText();
        // Testcontainer-only fixtures for states whose fulfilment APIs are not part of this slice.
        jdbc.update(
                "update orders set status=?, confirmed_at=case when ? in ('CONFIRMED','COMPLETED') then placed_at else null end, completed_at=case when ?='COMPLETED' then placed_at else null end where order_number=?",
                state,
                state,
                state,
                number);
        return number;
    }

    private String offer() {
        return campaign.path("offers").get(0).path("id").asText();
    }

    private String path(UUID owner, String id) {
        return "/api/organizations/" + owner + "/grouping-campaigns/" + id + "/supplier-summary";
    }

    private ResultActions summary() throws Exception {
        return auth(get(path(org, campaign.path("id").asText())));
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

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String token(UUID id) {
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(),
                                JwtClaimsSet.builder()
                                        .issuer("https://groupia.test")
                                        .audience(List.of("groupia-api-test"))
                                        .subject(id.toString())
                                        .issuedAt(Instant.now())
                                        .expiresAt(Instant.now().plusSeconds(1800))
                                        .build()))
                .getTokenValue();
    }
}
