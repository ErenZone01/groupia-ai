package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DirectStorefrontIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;

    private String bearer;
    private UUID organizationId;

    @BeforeEach
    void setup() throws Exception {
        TestEventDrain.awaitIdle(jdbc);
        jdbc.execute(
                "TRUNCATE TABLE outgoing_provider_operations,order_fulfillment_events,"
                        + " shipping_calculations, shipping_measurements, shipping_assessments,"
                        + " customer_access_sessions, otp_challenges, order_access_links,"
                        + " customer_access_limits, shipment_facts, shipments,"
                        + " supplier_order_anomalies, supplier_orders, payment_webhook_events,"
                        + " payments, payment_requests, order_items, orders, customers,"
                        + " grouping_changes, grouping_offers, grouping_campaigns,"
                        + " product_variants, products, organization_members,"
                        + " organizations, user_accounts, event_publication CASCADE");
        bearer = token(account("+221771234567"));
        organizationId = organization(bearer, "Boutique Awa");
    }

    @Test
    void publicationProvidesStableAnonymousLinkWithoutCreatingAGrouping() throws Exception {
        var product = product(organizationId, bearer, "Sac en cuir");
        var variantId = product.at("/variants/0/id").asText();

        var draftStore =
                body(
                        authorized(
                                        put(path())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "displayName",
                                                                        "Boutique Awa"))))
                                .andExpect(status().isOk())
                                .andReturn());
        String link = draftStore.path("publicIdentifier").asText();
        assertThat(link).isNotBlank();

        var draft =
                body(
                        authorized(
                                        post(path() + "/offers")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "variantId",
                                                                        variantId,
                                                                        "priceXof",
                                                                        8500))))
                                .andExpect(status().isOk())
                                .andReturn());
        String offerId = draft.at("/offers/0/id").asText();
        assertThat(draft.at("/offers/0/published").asBoolean()).isFalse();

        mvc.perform(get(publicPath(link))).andExpect(status().isNotFound());
        var published =
                body(
                        authorized(
                                        put(path() + "/offers/" + offerId)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "version",
                                                                        0,
                                                                        "priceXof",
                                                                        8500,
                                                                        "published",
                                                                        true))))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(published.at("/offers/0/version").asLong()).isEqualTo(1);

        mvc.perform(get(publicPath(link)))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Cache-Control",
                                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.offers[0].productName").value("Sac en cuir"))
                .andExpect(jsonPath("$.offers[0].priceXof").value(8500))
                .andExpect(jsonPath("$.offers[0].published").value(true));

        // Published storefront does not create an implicit campaign or order.
        assertThat(jdbc.queryForObject("select count(*) from grouping_campaigns", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isZero();

        var renamed =
                body(
                        authorized(
                                        put(path())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "displayName",
                                                                        "Boutique renommée"))))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(renamed.path("publicIdentifier").asText()).isEqualTo(link);
        mvc.perform(get(publicPath(link)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Boutique renommée"));

        authorized(
                        put(path() + "/offers/" + offerId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        1,
                                                        "priceXof",
                                                        8500,
                                                        "published",
                                                        false))))
                .andExpect(status().isOk());
        mvc.perform(get(publicPath(link))).andExpect(status().isNotFound());
    }

    @Test
    void preventsCrossTenantOrAnonymousMutationsAndDuplicateOffers() throws Exception {
        var product = product(organizationId, bearer, "Sac");
        String variant = product.at("/variants/0/id").asText();

        // No merchant endpoint is anonymous.
        mvc.perform(
                        put(path())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("displayName", "Vol"))))
                .andExpect(status().isUnauthorized());
        var second = token(account("+221781234567"));
        UUID other = organization(second, "Autre boutique");
        mvc.perform(get(path()).header("Authorization", "Bearer " + second))
                .andExpect(status().isForbidden());

        authorized(
                        put(path())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("displayName", "Boutique Awa"))))
                .andExpect(status().isOk());
        authorized(
                        post(path() + "/offers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("variantId", variant, "priceXof", 1000))))
                .andExpect(status().isOk());
        authorized(
                        post(path() + "/offers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("variantId", variant, "priceXof", 1000))))
                .andExpect(status().isConflict());

        // Tenant B cannot publish tenant A's variant, even if the UUID is known.
        mvc.perform(
                        put("/api/organizations/" + other + "/direct-store")
                                .header("Authorization", "Bearer " + second)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("displayName", "Boutique B"))))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/organizations/" + other + "/direct-store/offers")
                                .header("Authorization", "Bearer " + second)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("variantId", variant, "priceXof", 1000))))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from direct_offers", Long.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsPriceConflictAndArchivedProducts() throws Exception {
        var product = product(organizationId, bearer, "Robe");
        String variant = product.at("/variants/0/id").asText();
        authorized(
                        put(path())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("displayName", "Boutique Awa"))))
                .andExpect(status().isOk());
        var store =
                body(
                        authorized(
                                        post(path() + "/offers")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "variantId",
                                                                        variant,
                                                                        "priceXof",
                                                                        4500))))
                                .andExpect(status().isOk())
                                .andReturn());
        String offerId = store.at("/offers/0/id").asText();
        var id = store.path("publicIdentifier").asText();
        authorized(
                        put(path() + "/offers/" + offerId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        0,
                                                        "priceXof",
                                                        4500,
                                                        "published",
                                                        true))))
                .andExpect(status().isOk());
        authorized(
                        put(path() + "/offers/" + offerId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        0,
                                                        "priceXof",
                                                        3000,
                                                        "published",
                                                        true))))
                .andExpect(status().isConflict());
        authorized(
                        put(path() + "/offers/" + offerId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        1,
                                                        "priceXof",
                                                        0,
                                                        "published",
                                                        true))))
                .andExpect(status().isBadRequest());

        authorized(
                        post("/api/organizations/"
                                        + organizationId
                                        + "/products/"
                                        + product.path("id").asText()
                                        + "/archive")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        product.path("version").asLong()))))
                .andExpect(status().isOk());
        mvc.perform(get(publicPath(id))).andExpect(status().isNotFound());
        authorized(
                        put(path() + "/offers/" + offerId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        1,
                                                        "priceXof",
                                                        4500,
                                                        "published",
                                                        true))))
                .andExpect(status().isConflict());
    }

    private JsonNode product(UUID organization, String authorization, String name)
            throws Exception {
        return body(
                mvc.perform(
                                post("/api/organizations/" + organization + "/products")
                                        .header("Authorization", "Bearer " + authorization)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(Map.of("name", name))))
                        .andExpect(status().isCreated())
                        .andReturn());
    }

    private String path() {
        return "/api/organizations/" + organizationId + "/direct-store";
    }

    private String publicPath(String id) {
        return "/api/public/direct-stores/" + id;
    }

    private ResultActions authorized(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + bearer));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private UUID account(String phone) {
        var id = UUID.randomUUID();
        jdbc.update(
                "insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at)"
                        + " values (?,?,?,'ACTIVE',current_timestamp,current_timestamp)",
                id,
                phone,
                passwords.encode("Long fixture password 2026"));
        return id;
    }

    private String token(UUID userId) {
        var claims =
                JwtClaimsSet.builder()
                        .claim("sid", UUID.randomUUID().toString())
                        .issuer("https://groupia.test")
                        .audience(List.of("groupia-api-test"))
                        .subject(userId.toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private UUID organization(String jwt, String name) throws Exception {
        return UUID.fromString(
                body(mvc.perform(
                                        post("/api/organizations")
                                                .header("Authorization", "Bearer " + jwt)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of("name", name))))
                                .andExpect(status().isCreated())
                                .andReturn())
                        .get("id")
                        .asText());
    }
}
