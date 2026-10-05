package sn.groupia;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import sn.groupia.grouping.domain.repository.Campaigns;
import sn.groupia.shared.api.ApiException;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class GroupingIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    @Autowired Campaigns campaigns;
    @Autowired PlatformTransactionManager transactions;
    private static String fixtureHash;
    private UUID actorId;
    private UUID organizationId;
    private String token;
    private JsonNode product;
    private UUID variantId;

    @BeforeEach
    void setup() throws Exception {
        jdbc.execute("TRUNCATE TABLE order_fulfillment_events, shipping_calculations, shipping_measurements, shipping_assessments, customer_access_sessions, otp_challenges, order_access_links, customer_access_limits, shipment_facts, shipments, supplier_order_anomalies, supplier_orders, payment_webhook_events, payments, payment_requests, order_items, orders, customers, grouping_changes, grouping_offers, grouping_campaigns, product_variants, products, organization_members, organizations, user_accounts, event_publication");
        if (fixtureHash == null) { fixtureHash = passwords.encode("Fixture groupage 2026!"); }
        actorId = account("+221771234567");
        token = token(actorId);
        organizationId = organization(token, "Organisation privée");
        product = product(organizationId, token, "Produit public");
        variantId = UUID.fromString(product.get("variants").get(0).get("id").asText());
    }

    @Test
    void createsDraftWithDistinctServerIdentifiersAndDerivedSlug() throws Exception {
        var campaign = create(Map.of("title", "  Équipements Été 2026 !  "));
        assertThat(campaign.get("status").asText()).isEqualTo("DRAFT");
        assertThat(campaign.get("slug").asText()).isEqualTo("equipements-ete-2026");
        assertThat(campaign.get("currency").asText()).isEqualTo("XOF");
        assertThat(campaign.get("version").asLong()).isZero();
        assertThat(UUID.fromString(campaign.get("publicIdentifier").asText()).version()).isEqualTo(4);
        assertThat(campaign.get("publicIdentifier").asText()).isNotEqualTo(id(campaign));
        assertThat(campaign.get("shippingTerms").isNull()).isTrue();
        publicGet(campaign).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GROUPING_CAMPAIGN_NOT_FOUND"));
        for (String field : List.of("publicIdentifier", "slug", "organizationId", "status", "currency")) {
            perform(post(base()), Map.of("title", "Injection", field, "forged")).andExpect(status().isBadRequest());
        }
    }

    @Test
    void paginatesWithoutOfferCollectionsAndFiltersStatus() throws Exception {
        var cancelled = create(Map.of("title", "A"));
        perform(post(path(cancelled) + "/cancel"), Map.of("version", 0, "reason", "Annulation privée")).andExpect(status().isOk());
        create(Map.of("title", "B")); create(Map.of("title", "C"));
        perform(get(base() + "?page=0&size=2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2))).andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[0].offers").doesNotExist());
        perform(get(base() + "?page=1&size=2")).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)));
        perform(get(base() + "?status=CANCELLED")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        perform(get(base() + "?size=101")).andExpect(status().isBadRequest());
        perform(get(base() + "?status=UNKNOWN")).andExpect(status().isBadRequest());
    }

    @Test
    void addsCatalogueOfferAndReservesDuplicateEvenAfterDeactivation() throws Exception {
        var campaign = addOffer(draft(), variantId, "1500");
        assertThat(campaign.get("offers").size()).isEqualTo(1);
        assertThat(campaign.get("offers").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("1500");
        assertThat(campaign.get("offers").get(0).get("version").asLong()).isZero();
        perform(post(path(campaign) + "/offers"), addInput(campaign, variantId, "1600"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GROUPING_OFFER_ALREADY_EXISTS"));
        campaign = body(perform(post(offerPath(campaign, 0) + "/deactivate"), offerVersions(campaign, 0)).andExpect(status().isOk()).andReturn());
        assertThat(campaign.get("offers").get(0).get("active").asBoolean()).isFalse();
        perform(post(path(campaign) + "/offers"), addInput(campaign, variantId, "1600")).andExpect(status().isConflict());
        assertThat(count("grouping_offers")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "archived", "foreign", "missing"})
    void rejectsUnusableOrForeignVariants(String reason) throws Exception {
        UUID selected = variantId;
        switch (reason) {
            case "inactive" -> jdbc.update("update product_variants set active = false where id = ?", variantId);
            case "archived" -> jdbc.update("update products set status = 'ARCHIVED' where id = ?", UUID.fromString(product.get("id").asText()));
            case "foreign" -> {
                UUID other = organization(token, "Autre organisation");
                selected = UUID.fromString(product(other, token, "Autre produit").get("variants").get(0).get("id").asText());
            }
            case "missing" -> selected = UUID.randomUUID();
        }
        var campaign = draft();
        perform(post(path(campaign) + "/offers"), addInput(campaign, selected, "1000"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GROUPING_OFFER_NOT_AVAILABLE"));
        assertThat(count("grouping_offers")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1.5", "1000000000000000000"})
    void rejectsInvalidPricesWithoutRounding(String amount) throws Exception {
        var campaign = draft();
        perform(post(path(campaign) + "/offers"), addInput(campaign, variantId, amount))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_GROUPING_OFFER_PRICE"));
        assertThat(count("grouping_offers")).isZero();
    }

    @Test
    void zeroXofPriceAndZeroFixedShippingAreAllowed() throws Exception {
        var terms = terms("FIXED", "AFTER_FINAL_ASSESSMENT", "0");
        var campaign = create(Map.of("title", "Gratuit", "shippingTerms", terms));
        campaign = open(addOffer(campaign, variantId, "0"));
        publicGet(campaign).andExpect(status().isOk()).andExpect(jsonPath("$.offers[0].unitPrice").value(0));
    }

    @Test
    void openingRequiresOffersTransportAndAFutureDeadline() throws Exception {
        var empty = draft();
        perform(post(path(empty) + "/open"), version(empty)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUPING_HAS_NO_ACTIVE_OFFERS"));
        var withoutTerms = addOffer(create(Map.of("title", "Sans transport")), variantId, "1000");
        perform(post(path(withoutTerms) + "/open"), version(withoutTerms)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUPING_CAMPAIGN_NOT_OPENABLE"));
        var expired = create(Map.of("title", "Passé", "closesAt", Instant.now().minusSeconds(60).toString(), "shippingTerms", fixedTerms()));
        expired = addOffer(expired, variantId, "1000");
        perform(post(path(expired) + "/open"), version(expired)).andExpect(status().isConflict());
    }

    @Test
    void openingRevalidatesTheCatalogueAndRollsBackOnFailure() throws Exception {
        var campaign = addOffer(draft(), variantId, "1000");
        jdbc.update("update product_variants set active = false where id = ?", variantId);
        perform(post(path(campaign) + "/open"), version(campaign)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUPING_OFFER_NOT_AVAILABLE"));
        perform(get(path(campaign))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(campaign.get("version").asInt()));
        publicGet(campaign).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PER_KG", "PER_CBM"})
    void estimatedCollectionRequiresTheCorrespondingCatalogueEstimate(String method) throws Exception {
        var campaign = create(Map.of("title", "Estimations", "shippingTerms", terms(method, "ESTIMATED_AT_CHECKOUT", "200.123456789")));
        campaign = addOffer(campaign, variantId, "1000");
        perform(post(path(campaign) + "/open"), version(campaign)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SHIPPING_TERMS"));
        String column = method.equals("PER_KG") ? "estimated_unit_weight_kg" : "estimated_unit_volume_m3";
        jdbc.update("update product_variants set " + column + " = 0.123456789012345678 where id = ?", variantId);
        campaign = open(campaign);
        publicGet(campaign).andExpect(status().isOk()).andExpect(jsonPath("$.orderable").value(true));
        assertThat(jdbc.queryForObject("select shipping_rate from grouping_campaigns", BigDecimal.class)).isEqualByComparingTo("200.123456789");
        UUID withoutEstimate = UUID.fromString(product(organizationId, token, "Sans estimation").get("variants").get(0).get("id").asText());
        perform(post(path(campaign) + "/offers"), addInput(campaign, withoutEstimate, "1000")).andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"method", "collection", "negative", "zero-per-kg", "fixed-fraction", "currency", "rounding", "transport", "origin"})
    void rejectsInvalidShippingTerms(String mutation) throws Exception {
        var terms = new LinkedHashMap<String, Object>(fixedTerms());
        switch (mutation) {
            case "method" -> terms.put("shippingPricingMethod", "UNKNOWN");
            case "collection" -> terms.put("shippingCollectionMode", "AFTER_ACTUAL_WEIGHT");
            case "negative" -> terms.put("shippingRate", -1);
            case "zero-per-kg" -> { terms.put("shippingPricingMethod", "PER_KG"); terms.put("shippingRate", 0); }
            case "fixed-fraction" -> terms.put("shippingRate", new BigDecimal("1.5"));
            case "currency" -> terms.put("shippingCurrency", "EUR");
            case "rounding" -> terms.put("roundingRule", "DOWN");
            case "transport" -> terms.put("transportMode", "TRAIN");
            case "origin" -> terms.put("originCountry", "ZZ");
        }
        perform(post(base()), Map.of("title", "Invalide", "shippingTerms", terms))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SHIPPING_TERMS"));
    }

    @Test
    void freezesAllShippingTermsAfterOpeningButAllowsTitleAndDeadlineEdits() throws Exception {
        var campaign = open(addOffer(draft(), variantId, "1000"));
        List<Map.Entry<String, Object>> mutations = List.of(
                Map.entry("shippingRate", new BigDecimal("1500")), Map.entry("shippingPricingMethod", "PER_ITEM"),
                Map.entry("shippingCollectionMode", "ESTIMATED_AT_CHECKOUT"), Map.entry("transportMode", "SEA"),
                Map.entry("originCountry", "FR"));
        for (var mutation : mutations) {
            var changedTerms = new LinkedHashMap<String, Object>(fixedTerms());
            changedTerms.put(mutation.getKey(), mutation.getValue());
            perform(put(path(campaign)), Map.of("version", campaign.get("version").asLong(), "title", "Changé", "shippingTerms", changedTerms))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIPPING_TERMS_FROZEN"));
        }
        String publicId = campaign.get("publicIdentifier").asText();
        String future = Instant.now().plusSeconds(3600).toString();
        campaign = body(perform(put(path(campaign)), Map.of("version", campaign.get("version").asLong(),
                "title", "Nouveau titre", "description", "Texte public", "closesAt", future))
                .andExpect(status().isOk()).andReturn());
        assertThat(campaign.get("publicIdentifier").asText()).isEqualTo(publicId);
        assertThat(campaign.get("slug").asText()).isEqualTo("nouveau-titre");
        assertThat(campaign.get("shippingTerms").get("shippingRate").decimalValue()).isEqualByComparingTo("1000");
        assertThat(count("grouping_changes")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select actor_id from grouping_changes", UUID.class)).isEqualTo(actorId);
        perform(put(path(campaign)), Map.of("version", campaign.get("version").asLong(), "title", "Titre",
                "closesAt", Instant.now().minusSeconds(20).toString())).andExpect(status().isBadRequest());
    }

    @Test
    void openAndClosedArePublicWithExplicitPrivateFieldExclusion() throws Exception {
        var campaign = open(addOffer(draft(), variantId, "1000"));
        var published = body(publicGet(campaign).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.orderable").value(true)).andReturn());
        Set<String> expected = Set.of("publicIdentifier", "slug", "title", "description", "status",
                "currency", "orderable", "closesAt", "version", "shippingTerms", "offers");
        assertThat(published.propertyNames()).containsExactlyInAnyOrderElementsOf(expected);
        String text = json.writeValueAsString(published);
        assertThat(text).doesNotContain(organizationId.toString(), actorId.toString(), id(campaign),
                "organizationId", "actorId", "cancellationReason", "passwordHash", "members", "payment", "customer");
        assertThat(published.get("offers").get(0).get("catalog").get("productName").asText()).isEqualTo("Produit public");
        assertThat(published.get("offers").get(0).get("version").asLong()).isZero();
        campaign = body(perform(post(path(campaign) + "/close"), version(campaign)).andExpect(status().isOk()).andReturn());
        publicGet(campaign).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED")).andExpect(jsonPath("$.orderable").value(false));
        perform(post(path(campaign) + "/close"), version(campaign)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GROUPING_ALREADY_CLOSED"));
        perform(post(path(campaign) + "/open"), version(campaign)).andExpect(status().isConflict());
    }

    @Test
    void expiredOpenCampaignCanBeExtendedWithoutSchedulerAndExtensionIsAudited() throws Exception {
        var campaign = open(addOffer(draft(), variantId, "1000"));
        jdbc.update("update grouping_campaigns set closes_at = current_timestamp - interval '1 hour' where id = ?", UUID.fromString(id(campaign)));
        publicGet(campaign).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.orderable").value(false));
        campaign = body(perform(put(path(campaign)), Map.of("version", campaign.get("version").asLong(), "title", "Prolongé",
                "closesAt", Instant.now().plusSeconds(7200).toString())).andExpect(status().isOk()).andReturn());
        publicGet(campaign).andExpect(status().isOk()).andExpect(jsonPath("$.orderable").value(true));
        var trace = jdbc.queryForMap("select old_closes_at, new_closes_at, kind from grouping_changes");
        assertThat(trace.get("old_closes_at")).isNotNull();
        assertThat(trace.get("new_closes_at")).isNotNull();
        assertThat(trace.get("kind")).isEqualTo("DEADLINE_CHANGED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "OPEN", "CLOSED"})
    void supportsOnlyDefinedCancellationPathsAndPublicationPolicy(String state) throws Exception {
        var campaign = addOffer(draft(), variantId, "1000");
        if (!state.equals("DRAFT")) { campaign = open(campaign); }
        if (state.equals("CLOSED")) { campaign = body(perform(post(path(campaign) + "/close"), version(campaign)).andExpect(status().isOk()).andReturn()); }
        campaign = body(perform(post(path(campaign) + "/cancel"), Map.of("version", campaign.get("version").asLong(), "reason", "Raison interne sensible"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED")).andReturn());
        if (state.equals("DRAFT")) { publicGet(campaign).andExpect(status().isNotFound()); }
        else { publicGet(campaign).andExpect(status().isOk()).andExpect(jsonPath("$.orderable").value(false))
                .andExpect(jsonPath("$.cancellationReason").doesNotExist()); }
        perform(post(path(campaign) + "/open"), version(campaign)).andExpect(status().isConflict());
        perform(post(path(campaign) + "/cancel"), Map.of("version", campaign.get("version").asLong(), "reason", "Encore")).andExpect(status().isConflict());
    }

    @Test
    void draftCannotBeClosedAndTerminalCampaignsCannotBeEdited() throws Exception {
        var campaign = draft();
        perform(post(path(campaign) + "/close"), version(campaign)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_GROUPING_TRANSITION"));
        campaign = open(addOffer(campaign, variantId, "1000"));
        campaign = body(perform(post(path(campaign) + "/close"), version(campaign)).andExpect(status().isOk()).andReturn());
        perform(put(path(campaign)), Map.of("version", campaign.get("version").asLong(), "title", "Interdit")).andExpect(status().isConflict());
        perform(post(path(campaign) + "/offers"), addInput(campaign, variantId, "1000")).andExpect(status().isConflict());
        perform(delete(path(campaign))).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void addsUpdatesDeactivatesAndReactivatesOffersWhileOpen() throws Exception {
        var campaign = open(addOffer(draft(), variantId, "1000"));
        UUID secondVariant = UUID.fromString(product(organizationId, token, "Second").get("variants").get(0).get("id").asText());
        campaign = addOffer(campaign, secondVariant, "2000");
        campaign = body(perform(put(offerPath(campaign, 0)), updateOfferInput(campaign, 0, "1200"))
                .andExpect(status().isOk()).andReturn());
        assertThat(campaign.get("offers").get(0).get("priceRevision").asLong()).isEqualTo(1);
        assertThat(campaign.get("offers").get(0).get("version").asLong()).isEqualTo(1);
        publicGet(campaign).andExpect(status().isOk()).andExpect(jsonPath("$.offers[0].unitPrice").value(1200));
        var change = jdbc.queryForMap("select old_price, new_price, currency, actor_id, price_revision from grouping_changes");
        assertThat((BigDecimal) change.get("old_price")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) change.get("new_price")).isEqualByComparingTo("1200");
        assertThat(change.get("actor_id")).isEqualTo(actorId);
        assertThat(change.get("currency")).isEqualTo("XOF");
        campaign = body(perform(post(offerPath(campaign, 0) + "/deactivate"), offerVersions(campaign, 0)).andExpect(status().isOk()).andReturn());
        publicGet(campaign).andExpect(status().isOk()).andExpect(jsonPath("$.offers", hasSize(1)));
        perform(post(offerPath(campaign, 1) + "/deactivate"), offerVersions(campaign, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUPING_HAS_NO_ACTIVE_OFFERS"));
        campaign = body(perform(post(offerPath(campaign, 0) + "/activate"), offerVersions(campaign, 0)).andExpect(status().isOk()).andReturn());
        publicGet(campaign).andExpect(status().isOk()).andExpect(jsonPath("$.offers", hasSize(2)));
        assertThat(count("grouping_offers")).isEqualTo(2);
    }

    @Test
    void reactivationRevalidatesCatalogAndPublicReadsHideUnusableProducts() throws Exception {
        var campaign = open(addOffer(draft(), variantId, "1000"));
        UUID secondVariant = UUID.fromString(product(organizationId, token, "Second").get("variants").get(0).get("id").asText());
        campaign = addOffer(campaign, secondVariant, "2000");
        campaign = body(perform(post(offerPath(campaign, 0) + "/deactivate"), offerVersions(campaign, 0)).andExpect(status().isOk()).andReturn());
        jdbc.update("update product_variants set active = false where id = ?", variantId);
        perform(post(offerPath(campaign, 0) + "/activate"), offerVersions(campaign, 0)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUPING_OFFER_NOT_AVAILABLE"));
        jdbc.update("update products set status = 'ARCHIVED'");
        publicGet(campaign).andExpect(status().isOk()).andExpect(jsonPath("$.offers", hasSize(0))).andExpect(jsonPath("$.orderable").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "MANAGER"})
    void authorizedWritersCanUseTheWholeCampaignWorkflow(String role) throws Exception {
        jdbc.update("update organization_members set role = ? where organization_id = ?", role, organizationId);
        var campaign = draft();
        perform(get(base())).andExpect(status().isOk());
        perform(get(path(campaign))).andExpect(status().isOk());
        campaign = body(perform(put(path(campaign)), Map.of("version", 0, "title", "Édité", "shippingTerms", fixedTerms()))
                .andExpect(status().isOk()).andReturn());
        campaign = addOffer(campaign, variantId, "1000");
        campaign = body(perform(post(offerPath(campaign, 0) + "/deactivate"), offerVersions(campaign, 0)).andExpect(status().isOk()).andReturn());
        campaign = body(perform(post(offerPath(campaign, 0) + "/activate"), offerVersions(campaign, 0)).andExpect(status().isOk()).andReturn());
        campaign = open(campaign);
        campaign = body(perform(put(offerPath(campaign, 0)), updateOfferInput(campaign, 0, "1100")).andExpect(status().isOk()).andReturn());
        campaign = body(perform(post(path(campaign) + "/close"), version(campaign)).andExpect(status().isOk()).andReturn());
        perform(post(path(campaign) + "/cancel"), Map.of("version", campaign.get("version").asLong(), "reason", "Fin")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPERATOR", "VIEWER"})
    void readersCannotInvokeAnyCampaignWriteEndpoint(String role) throws Exception {
        var campaign = addOffer(draft(), variantId, "1000");
        jdbc.update("update organization_members set role = ? where organization_id = ?", role, organizationId);
        perform(get(base())).andExpect(status().isOk());
        perform(get(path(campaign))).andExpect(status().isOk());
        for (var request : writeRequests(base(), campaign)) {
            perform(request).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORGANIZATION_ACCESS_DENIED"));
        }
    }

    @Test
    void allPrivateEndpointsRequireJwtAndRevokedOrDisabledUsersCannotAccess() throws Exception {
        var campaign = addOffer(draft(), variantId, "1000");
        mvc.perform(get(base())).andExpect(status().isUnauthorized());
        mvc.perform(get(path(campaign))).andExpect(status().isUnauthorized());
        for (var request : writeRequests(base(), campaign)) { mvc.perform(request).andExpect(status().isUnauthorized()); }
        jdbc.update("update organization_members set status = 'REVOKED', revoked_at = current_timestamp");
        perform(get(path(campaign))).andExpect(status().isForbidden());
        jdbc.update("update organization_members set status = 'ACTIVE', revoked_at = null");
        jdbc.update("update user_accounts set status = 'DISABLED' where id = ?", actorId);
        perform(get(base())).andExpect(status().isUnauthorized());
    }

    @Test
    void foreignCampaignAndOfferIdsCannotCrossOrganizationOrCampaignBoundaries() throws Exception {
        var own = addOffer(draft(), variantId, "1000");
        UUID otherUser = account("+221781234567");
        String otherToken = token(otherUser);
        UUID otherOrg = organization(otherToken, "Autre organisation");
        UUID otherVariant = UUID.fromString(product(otherOrg, otherToken, "Autre").get("variants").get(0).get("id").asText());
        var foreign = body(mvc.perform(post(base(otherOrg)).header("Authorization", "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("title", "Secret", "shippingTerms", fixedTerms()))))
                .andExpect(status().isCreated()).andReturn());
        foreign = body(mvc.perform(post(base(otherOrg) + "/" + id(foreign) + "/offers").header("Authorization", "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(addInput(foreign, otherVariant, "1000"))))
                .andExpect(status().isOk()).andReturn());
        perform(get(base(otherOrg))).andExpect(status().isForbidden());
        perform(get(base(otherOrg) + "/" + id(foreign))).andExpect(status().isForbidden());
        for (var request : writeRequests(base(otherOrg), foreign)) { perform(request).andExpect(status().isForbidden()); }
        perform(get(path(foreign))).andExpect(status().isNotFound());
        var forgedRequests = writeRequests(base(), foreign);
        for (var request : forgedRequests.subList(1, forgedRequests.size())) {
            perform(request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GROUPING_CAMPAIGN_NOT_FOUND"));
        }
        perform(put(path(own) + "/offers/" + foreign.get("offers").get(0).get("id").asText()), updateOfferInput(own, 0, "1100"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GROUPING_OFFER_NOT_FOUND"));
    }

    @Test
    void publicEndpointDerivesOrganizationAndHidesArchivedOrganizations() throws Exception {
        var campaign = open(addOffer(draft(), variantId, "1000"));
        mvc.perform(get(publicPath(campaign) + "?organizationId=" + UUID.randomUUID())).andExpect(status().isOk())
                .andExpect(jsonPath("$.offers", hasSize(1)));
        mvc.perform(get("/api/public/groupings/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/groupings/not-an-id")).andExpect(status().isNotFound());
        mvc.perform(post(publicPath(campaign))).andExpect(status().isUnauthorized());
        jdbc.update("update organizations set status = 'ARCHIVED' where id = ?", organizationId);
        publicGet(campaign).andExpect(status().isNotFound());
        perform(post(base()), Map.of("title", "Interdit")).andExpect(status().isNotFound());
    }

    @Test
    void competingCampaignTransactionsCannotSilentlyOverwrite() throws Exception {
        var campaign = draft();
        UUID campaignId = UUID.fromString(id(campaign));
        var loaded = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<String>>();
            for (String title : List.of("Premier", "Second")) {
                futures.add(executor.submit(() -> {
                    try {
                        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                            var aggregate = campaigns.find(organizationId, campaignId).orElseThrow();
                            loaded.countDown();
                            try { assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue(); }
                            catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                            aggregate.requireVersion(0);
                            aggregate.update(title, null, aggregate.getClosesAt(), aggregate.getShippingTerms(), aggregate.getUpdatedAt());
                            campaigns.save(aggregate);
                        });
                        return "SUCCESS";
                    } catch (ApiException ex) { return ex.code(); }
                }));
            }
            assertThat(List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "GROUPING_CAMPAIGN_VERSION_CONFLICT");
        }
        assertThat(jdbc.queryForObject("select version from grouping_campaigns", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select title from grouping_campaigns", String.class)).isIn("Premier", "Second");
    }

    @Test
    void concurrentDuplicateOfferCreationHasOnlyOneWinner() throws Exception {
        var campaign = draft();
        Callable<Integer> operation = () -> perform(post(path(campaign) + "/offers"), addInput(campaign, variantId, "1000"))
                .andReturn().getResponse().getStatus();
        assertThat(race(operation, operation)).containsExactlyInAnyOrder(200, 409);
        assertThat(count("grouping_offers")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from grouping_campaigns", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentPricesHaveOneRevisionAndOneDurableTrace() throws Exception {
        var campaign = open(addOffer(draft(), variantId, "1000"));
        var results = race(
                () -> perform(put(offerPath(campaign, 0)), updateOfferInput(campaign, 0, "1200")).andReturn().getResponse().getStatus(),
                () -> perform(put(offerPath(campaign, 0)), updateOfferInput(campaign, 0, "1300")).andReturn().getResponse().getStatus());
        assertThat(results).containsExactlyInAnyOrder(200, 409);
        assertThat(count("grouping_changes")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select price_revision from grouping_offers", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select unit_price from grouping_offers", BigDecimal.class))
                .isIn(new BigDecimal("1200"), new BigDecimal("1300"));
        var refreshed = body(perform(get(path(campaign))).andExpect(status().isOk()).andReturn());
        var staleOffer = new LinkedHashMap<String, Object>(updateOfferInput(refreshed, 0, "1400"));
        staleOffer.put("version", 0);
        perform(put(offerPath(refreshed, 0)), staleOffer).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUPING_OFFER_PRICE_CONFLICT"));
        refreshed = body(perform(put(offerPath(refreshed, 0)), updateOfferInput(refreshed, 0, "1000")).andExpect(status().isOk()).andReturn());
        assertThat(refreshed.get("offers").get(0).get("priceRevision").asLong()).isEqualTo(2);
        assertThat(count("grouping_changes")).isEqualTo(2);
        publicGet(refreshed).andExpect(status().isOk()).andExpect(jsonPath("$.offers[0].unitPrice").value(1000))
                .andExpect(jsonPath("$.offers[0].version").value(2));
    }

    @Test
    void simultaneousDeactivationsLeaveOneActiveOffer() throws Exception {
        var campaign = addOffer(draft(), variantId, "1000");
        UUID secondVariant = UUID.fromString(product(organizationId, token, "Second").get("variants").get(0).get("id").asText());
        final var opened = open(addOffer(campaign, secondVariant, "2000"));
        assertThat(race(
                () -> perform(post(offerPath(opened, 0) + "/deactivate"), offerVersions(opened, 0)).andReturn().getResponse().getStatus(),
                () -> perform(post(offerPath(opened, 1) + "/deactivate"), offerVersions(opened, 1)).andReturn().getResponse().getStatus()))
                .containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("select count(*) from grouping_offers where active", Long.class)).isEqualTo(1);
        publicGet(opened).andExpect(status().isOk()).andExpect(jsonPath("$.offers", hasSize(1))).andExpect(jsonPath("$.orderable").value(true));
    }

    @Test
    void openingAndChangingShippingTermsCannotBothCommitAgainstTheSameVersion() throws Exception {
        var campaign = addOffer(draft(), variantId, "1000");
        var changed = terms("FIXED", "AFTER_FINAL_ASSESSMENT", "1500");
        assertThat(race(
                () -> perform(post(path(campaign) + "/open"), version(campaign)).andReturn().getResponse().getStatus(),
                () -> perform(put(path(campaign)), Map.of("version", campaign.get("version").asLong(), "title", "Nouveau", "shippingTerms", changed))
                        .andReturn().getResponse().getStatus())).containsExactlyInAnyOrder(200, 409);
        var result = body(perform(get(path(campaign))).andExpect(status().isOk()).andReturn());
        assertThat(result.get("shippingTerms").get("shippingRate").decimalValue())
                .isEqualByComparingTo(result.get("status").asText().equals("OPEN") ? "1000" : "1500");
    }

    @Test
    void failureToWriteThePriceHistoryRollsBackPriceAndBothVersions() throws Exception {
        var campaign = open(addOffer(draft(), variantId, "1000"));
        jdbc.execute("alter table grouping_changes add constraint test_reject_grouping_history check (false)");
        try {
            perform(put(offerPath(campaign, 0)), updateOfferInput(campaign, 0, "2000")).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(jsonPath("$.trace").doesNotExist());
            assertThat(jdbc.queryForObject("select unit_price from grouping_offers", BigDecimal.class)).isEqualByComparingTo("1000");
            assertThat(jdbc.queryForObject("select version from grouping_offers", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select version from grouping_campaigns", Long.class)).isEqualTo(campaign.get("version").asLong());
            assertThat(count("grouping_changes")).isZero();
        } finally { jdbc.execute("alter table grouping_changes drop constraint test_reject_grouping_history"); }
    }

    @Test
    void postgresEnforcesReferencesMoneyPublicationAndUniqueness() throws Exception {
        var campaign = addOffer(draft(), variantId, "1000");
        UUID campaignId = UUID.fromString(id(campaign));
        UUID otherOrg = organization(token, "Autre");
        UUID otherVariant = UUID.fromString(product(otherOrg, token, "Autre").get("variants").get(0).get("id").asText());
        assertThatThrownBy(() -> jdbc.update("update grouping_offers set product_variant_id = ?", otherVariant)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update grouping_offers set organization_id = ?", otherOrg)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update grouping_campaigns set organization_id = ?", UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        for (String sql : List.of(
                "update grouping_offers set unit_price = -1",
                "update grouping_offers set unit_price = 1.5",
                "update grouping_offers set unit_price = 'NaN'",
                "update grouping_offers set currency = 'EUR'",
                "update grouping_campaigns set currency = 'EUR'",
                "update grouping_campaigns set shipping_rate = -1",
                "update grouping_campaigns set shipping_rate = 'Infinity'",
                "update grouping_campaigns set shipping_currency = 'EUR'",
                "update grouping_campaigns set rounding_rule = 'DOWN'",
                "update grouping_campaigns set shipping_rate = null",
                "update grouping_campaigns set status = 'OPEN'",
                "update grouping_campaigns set status = 'CANCELLED'",
                "delete from grouping_campaigns",
                "delete from product_variants")) {
            assertThatThrownBy(() -> jdbc.update(sql)).as(sql).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("insert into grouping_offers (id, organization_id, campaign_id, product_variant_id, unit_price, currency, active, created_at, updated_at) values (?, ?, ?, ?, 1000, 'XOF', true, current_timestamp, current_timestamp)",
                UUID.randomUUID(), organizationId, campaignId, variantId)).isInstanceOf(DataIntegrityViolationException.class);
        var other = draft();
        assertThatThrownBy(() -> jdbc.update("update grouping_campaigns set public_identifier = ? where id = ?",
                UUID.fromString(campaign.get("publicIdentifier").asText()), UUID.fromString(id(other)))).isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<MockHttpServletRequestBuilder> writeRequests(String base, JsonNode campaign) throws Exception {
        String path = base + "/" + id(campaign);
        String offer = path + "/offers/" + campaign.get("offers").get(0).get("id").asText();
        return List.of(
                withBody(post(base), Map.of("title", "Création")),
                withBody(put(path), Map.of("version", campaign.get("version").asLong(), "title", "Modification")),
                withBody(post(path + "/open"), version(campaign)),
                withBody(post(path + "/close"), version(campaign)),
                withBody(post(path + "/cancel"), Map.of("version", campaign.get("version").asLong(), "reason", "Annulation")),
                withBody(post(path + "/offers"), addInput(campaign, variantId, "1000")),
                withBody(put(offer), updateOfferInput(campaign, 0, "1200")),
                withBody(post(offer + "/deactivate"), offerVersions(campaign, 0)),
                withBody(post(offer + "/activate"), offerVersions(campaign, 0)));
    }

    private List<Integer> race(Callable<Integer> first, Callable<Integer> second) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return first.call(); });
            var b = executor.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }
    private Map<String, Object> fixedTerms() { return terms("FIXED", "AFTER_FINAL_ASSESSMENT", "1000"); }
    private Map<String, Object> terms(String method, String collection, String rate) {
        return Map.of("originCountry", "CN", "transportMode", "AIR", "shippingPricingMethod", method,
                "shippingCollectionMode", collection, "shippingRate", new BigDecimal(rate));
    }
    private JsonNode draft() throws Exception { return create(Map.of("title", "Groupage public", "shippingTerms", fixedTerms())); }
    private JsonNode create(Object body) throws Exception {
        return body(perform(post(base()), body).andExpect(status().isCreated()).andReturn());
    }
    private JsonNode addOffer(JsonNode campaign, UUID variant, String price) throws Exception {
        return body(perform(post(path(campaign) + "/offers"), addInput(campaign, variant, price)).andExpect(status().isOk()).andReturn());
    }
    private JsonNode open(JsonNode campaign) throws Exception {
        return body(perform(post(path(campaign) + "/open"), version(campaign)).andExpect(status().isOk()).andReturn());
    }
    private Map<String, Object> addInput(JsonNode campaign, UUID variant, String amount) {
        return Map.of("campaignVersion", campaign.get("version").asLong(), "productVariantId", variant.toString(),
                "unitPrice", new BigDecimal(amount), "commercialLabel", "Offre publique");
    }
    private Map<String, Object> updateOfferInput(JsonNode campaign, int index, String amount) {
        var result = new LinkedHashMap<String, Object>(offerVersions(campaign, index));
        result.put("unitPrice", new BigDecimal(amount)); result.put("commercialLabel", "Offre modifiée");
        return result;
    }
    private Map<String, Object> offerVersions(JsonNode campaign, int index) {
        return Map.of("campaignVersion", campaign.get("version").asLong(), "version", campaign.get("offers").get(index).get("version").asLong());
    }
    private Map<String, Object> version(JsonNode campaign) { return Map.of("version", campaign.get("version").asLong()); }
    private String id(JsonNode campaign) { return campaign.get("id").asText(); }
    private String base() { return base(organizationId); }
    private String base(UUID org) { return "/api/organizations/" + org + "/grouping-campaigns"; }
    private String path(JsonNode campaign) { return base() + "/" + id(campaign); }
    private String offerPath(JsonNode campaign, int index) { return path(campaign) + "/offers/" + campaign.get("offers").get(index).get("id").asText(); }
    private String publicPath(JsonNode campaign) { return "/api/public/groupings/" + campaign.get("publicIdentifier").asText(); }
    private ResultActions publicGet(JsonNode campaign) throws Exception { return mvc.perform(get(publicPath(campaign))); }
    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }
    private ResultActions perform(MockHttpServletRequestBuilder request, Object body) throws Exception { return perform(withBody(request, body)); }
    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request.header("Authorization", "Bearer " + token)); }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
    private UUID account(String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into user_accounts (id, phone_number, password_hash, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', current_timestamp, current_timestamp)",
                id, phone, fixtureHash);
        return id;
    }
    private String token(UUID id) {
        var claims = JwtClaimsSet.builder().issuer("https://groupia.test").audience(List.of("groupia-api-test"))
                .subject(id.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    private UUID organization(String token, String name) throws Exception {
        return UUID.fromString(body(mvc.perform(withBody(post("/api/organizations"), Map.of("name", name))
                .header("Authorization", "Bearer " + token)).andExpect(status().isCreated()).andReturn()).get("id").asText());
    }
    private JsonNode product(UUID org, String token, String name) throws Exception {
        return body(mvc.perform(withBody(post("/api/organizations/" + org + "/products"), Map.of("name", name))
                .header("Authorization", "Bearer " + token)).andExpect(status().isCreated()).andReturn());
    }
}
