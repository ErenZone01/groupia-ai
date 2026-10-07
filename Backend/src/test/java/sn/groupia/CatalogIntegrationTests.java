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
import sn.groupia.catalog.domain.repository.Products;
import sn.groupia.shared.api.ApiException;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.hasSize;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class CatalogIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    @Autowired Products products;
    @Autowired PlatformTransactionManager transactions;
    private static String fixtureHash;
    private UUID userId;
    private UUID organizationId;
    private String token;

    @BeforeEach
    void setup() throws Exception {
        jdbc.execute("TRUNCATE TABLE order_fulfillment_events, shipping_calculations, shipping_measurements, shipping_assessments, customer_access_sessions, otp_challenges, order_access_links, customer_access_limits, shipment_facts, shipments, supplier_order_anomalies, supplier_orders, payment_webhook_events, payments, payment_requests, order_items, orders, customers, grouping_changes, grouping_offers, grouping_campaigns, product_variants, products, organization_members, organizations, user_accounts, event_publication CASCADE");
        if (fixtureHash == null) { fixtureHash = passwords.encode("Fixture catalogue 2026!"); }
        userId = account("+221771234567");
        token = token(userId);
        organizationId = organization(token, "Catalogue A");
    }

    @AfterEach
    void awaitModuleEvents() {
        await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "select count(*) from event_publication where status in ('PUBLISHED','PROCESSING','RESUBMITTED')",
                        Long.class)).isZero());
    }

    @Test
    void simpleProductGetsServerDefaultVariantAndNoPrice() throws Exception {
        var product = create(Map.of("name", "  Sac simple  ", "description", "  Description  "));
        assertThat(product.get("name").asText()).isEqualTo("Sac simple");
        assertThat(product.get("description").asText()).isEqualTo("Description");
        assertThat(product.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(product.get("version").asLong()).isZero();
        assertThat(product.get("organizationId").asText()).isEqualTo(organizationId.toString());
        assertThat(product.get("variants").size()).isEqualTo(1);
        var variant = product.get("variants").get(0);
        assertThat(variant.get("canonicalVariantKey").asText()).isEqualTo("DEFAULT");
        assertThat(variant.get("attributes").size()).isZero();
        assertThat(variant.get("saleUnit").asText()).isEqualTo("UNIT");
        assertThat(variant.get("active").asBoolean()).isTrue();
        assertThat(variant.get("sku").isNull()).isTrue();
        assertThat(product.has("price")).isFalse();
        assertThat(variant.has("price")).isFalse();
        perform(get(base() + "/" + id(product))).andExpect(status().isOk())
                .andExpect(jsonPath("$.variants", hasSize(1)));
    }

    @Test
    void explicitVariantsPersistJsonbAndExactDecimalEstimates() throws Exception {
        var black = variant(Map.of("shoeSize", " 42 ", "Color", " BLACK "));
        black.put("saleUnit", "PAIR");
        black.put("estimatedUnitWeightKg", new BigDecimal("0.123456789012345678"));
        black.put("estimatedUnitVolumeM3", new BigDecimal("0.000001234567890123"));
        var product = create(Map.of("name", "Chaussures", "variants", List.of(black, variant(Map.of("color", "white")))));
        assertThat(product.get("variants").size()).isEqualTo(2);
        var stored = jdbc.queryForMap("select attributes::text as attributes, pg_typeof(attributes)::text as type, estimated_unit_weight_kg as weight, estimated_unit_volume_m3 as volume from product_variants where sale_unit = 'PAIR'");
        assertThat(stored.get("type")).isEqualTo("jsonb");
        assertThat(json.readTree((String) stored.get("attributes")).get("shoesize").asText()).isEqualTo("42");
        assertThat(json.readTree((String) stored.get("attributes")).get("color").asText()).isEqualTo("black");
        assertThat((BigDecimal) stored.get("weight")).isEqualByComparingTo("0.123456789012345678");
        assertThat((BigDecimal) stored.get("volume")).isEqualByComparingTo("0.000001234567890123");
    }

    @Test
    void canonicalKeyIgnoresAttributeOrderCaseWhitespaceAndUnicodeCompatibility() throws Exception {
        var first = new LinkedHashMap<String, Object>();
        first.put("size", "M"); first.put("color", "black");
        var second = new LinkedHashMap<String, Object>();
        second.put(" COLOR ", "  BLACK "); second.put("SIZE", "Ｍ");
        var a = create(Map.of("name", "A", "variants", List.of(variant(first))));
        var b = create(Map.of("name", "B", "variants", List.of(variant(second))));
        assertThat(a.get("variants").get(0).get("canonicalVariantKey").asText())
                .isEqualTo(b.get("variants").get(0).get("canonicalVariantKey").asText()).startsWith("v1:");
        perform(post(base() + "/" + id(a) + "/variants"), change(a, variant(second)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_VARIANT"));
    }

    @Test
    void duplicateInitialVariantsRollbackTheWholeProduct() throws Exception {
        perform(post(base()), Map.of("name", "Duplicate", "variants", List.of(variant(Map.of("size", "M")), variant(Map.of("size", "m")))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_VARIANT"));
        assertThat(count("products")).isZero();
        assertThat(count("product_variants")).isZero();
    }

    @Test
    void skuNormalizationIsUniqueWithinOrganizationIncludingInactiveVariants() throws Exception {
        var data = variant(Map.of()); data.put("sku", " sku-001 ");
        var first = create(Map.of("name", "A", "variants", List.of(data)));
        assertThat(first.get("variants").get(0).get("sku").asText()).isEqualTo("SKU-001");
        data.put("sku", "SKU-001");
        perform(post(base()), Map.of("name", "B", "variants", List.of(data))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_ALREADY_EXISTS"));
        assertThat(count("products")).isEqualTo(1);
        var withSecond = body(perform(post(base() + "/" + id(first) + "/variants"), change(first, variant(Map.of("size", "L"))))
                .andExpect(status().isOk()).andReturn());
        perform(post(base() + "/" + id(first) + "/variants/" + variantId(first, 0) + "/deactivate"), version(withSecond))
                .andExpect(status().isOk());
        perform(post(base()), Map.of("name", "C", "variants", List.of(data))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_ALREADY_EXISTS"));
    }

    @Test
    void sameSkuIsAllowedInDifferentOrganizationsAndNullSkusRepeat() throws Exception {
        var data = variant(Map.of()); data.put("sku", "same");
        create(Map.of("name", "A", "variants", List.of(data)));
        UUID another = organization(token, "Catalogue B");
        perform(post(base(another)), Map.of("name", "B", "variants", List.of(data))).andExpect(status().isCreated());
        create(Map.of("name", "Sans SKU 1")); create(Map.of("name", "Sans SKU 2"));
        assertThat(count("products")).isEqualTo(4);
    }

    @Test
    void editsAndVariantChangesIncrementRootVersionAndKeepHistory() throws Exception {
        var product = create(Map.of("name", "Original"));
        product = body(perform(put(base() + "/" + id(product)),
                Map.of("version", 0, "name", "Modifié", "description", "Texte")).andExpect(status().isOk()).andReturn());
        assertThat(product.get("version").asLong()).isEqualTo(1);
        product = body(perform(post(base() + "/" + id(product) + "/variants"), change(product, variant(Map.of("size", "L"))))
                .andExpect(status().isOk()).andReturn());
        assertThat(product.get("version").asLong()).isEqualTo(2);
        String variantId = product.get("variants").get(1).get("id").asText();
        var updated = variant(Map.of("size", "XL")); updated.put("saleUnit", "PACK"); updated.put("sku", " xl-1 ");
        product = body(perform(put(base() + "/" + id(product) + "/variants/" + variantId), change(product, updated))
                .andExpect(status().isOk()).andReturn());
        assertThat(product.get("version").asLong()).isEqualTo(3);
        assertThat(product.get("variants").get(1).get("attributes").get("size").asText()).isEqualTo("xl");
        product = body(perform(post(base() + "/" + id(product) + "/variants/" + variantId + "/deactivate"), version(product))
                .andExpect(status().isOk()).andReturn());
        assertThat(product.get("version").asLong()).isEqualTo(4);
        assertThat(product.get("variants").get(1).get("active").asBoolean()).isFalse();
        assertThat(count("product_variants")).isEqualTo(2);
    }

    @Test
    void lastActiveVariantCannotBeDeactivated() throws Exception {
        var product = create(Map.of("name", "Unique"));
        perform(post(base() + "/" + id(product) + "/variants/" + variantId(product, 0) + "/deactivate"), version(product))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_PRODUCT_STATE"));
        assertThat(jdbc.queryForObject("select count(*) from product_variants where active", Long.class)).isEqualTo(1);
    }

    @Test
    void archivedProductsRemainReadableAndRejectEveryEdit() throws Exception {
        var product = create(Map.of("name", "Historique"));
        product = body(perform(post(base() + "/" + id(product) + "/archive"), version(product))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED")).andReturn());
        perform(get(base() + "/" + id(product))).andExpect(status().isOk()).andExpect(jsonPath("$.variants", hasSize(1)));
        perform(post(base() + "/" + id(product) + "/variants"), change(product, variant(Map.of("color", "red"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
        perform(put(base() + "/" + id(product)), Map.of("version", 1, "name", "Impossible"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
        perform(put(base() + "/" + id(product) + "/variants/" + variantId(product, 0)), change(product, variant(Map.of())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
        perform(post(base() + "/" + id(product) + "/variants/" + variantId(product, 0) + "/deactivate"), version(product))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
        perform(delete(base() + "/" + id(product))).andExpect(status().isMethodNotAllowed());
        assertThat(count("products")).isEqualTo(1);
        assertThat(count("product_variants")).isEqualTo(1);
    }

    @Test
    void listingIsPagedScopedAndDoesNotLoadVariantPayloads() throws Exception {
        var a = create(Map.of("name", "A"));
        create(Map.of("name", "B")); create(Map.of("name", "C"));
        perform(post(base() + "/" + id(a) + "/archive"), version(a)).andExpect(status().isOk());
        UUID another = organization(token, "Autre");
        perform(post(base(another)), Map.of("name", "Étranger")).andExpect(status().isCreated());
        perform(get(base() + "?page=0&size=2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2))).andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.items[0].variants").doesNotExist());
        perform(get(base() + "?page=1&size=2")).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)));
        perform(get(base() + "?status=ARCHIVED")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        perform(get(base() + "?status=ACTIVE")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        perform(get(base() + "?size=101")).andExpect(status().isBadRequest());
        perform(get(base() + "?page=-1")).andExpect(status().isBadRequest());
        perform(get(base() + "?status=UNKNOWN")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "MANAGER"})
    void writersCanExecuteTheWholeCatalogueWorkflow(String role) throws Exception {
        jdbc.update("update organization_members set role = ? where organization_id = ?", role, organizationId);
        var product = create(Map.of("name", "Autorisé"));
        perform(get(base())).andExpect(status().isOk());
        perform(get(base() + "/" + id(product))).andExpect(status().isOk());
        product = body(perform(put(base() + "/" + id(product)), Map.of("version", 0, "name", "Renommé"))
                .andExpect(status().isOk()).andReturn());
        product = body(perform(post(base() + "/" + id(product) + "/variants"), change(product, variant(Map.of("size", "M"))))
                .andExpect(status().isOk()).andReturn());
        String newVariantId = variantId(product, 1);
        product = body(perform(put(base() + "/" + id(product) + "/variants/" + newVariantId),
                change(product, variant(Map.of("size", "L")))).andExpect(status().isOk()).andReturn());
        product = body(perform(post(base() + "/" + id(product) + "/variants/" + newVariantId + "/deactivate"), version(product))
                .andExpect(status().isOk()).andReturn());
        perform(post(base() + "/" + id(product) + "/archive"), version(product)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPERATOR", "VIEWER"})
    void readersCanReadButCannotUseAnyWriteEndpoint(String role) throws Exception {
        var product = create(Map.of("name", "Lecture"));
        jdbc.update("update organization_members set role = ? where organization_id = ?", role, organizationId);
        perform(get(base())).andExpect(status().isOk());
        perform(get(base() + "/" + id(product))).andExpect(status().isOk());
        for (var request : writeRequests(base(), product)) {
            perform(request).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORGANIZATION_ACCESS_DENIED"));
        }
    }

    @Test
    void foreignOrganizationAndForgedParentIdsCannotBypassScoping() throws Exception {
        var own = create(Map.of("name", "Privé"));
        UUID foreignUser = account("+221781234567");
        String foreignToken = token(foreignUser);
        UUID foreignOrg = organization(foreignToken, "Étranger");
        var foreign = body(mvc.perform(post(base(foreignOrg)).header("Authorization", "Bearer " + foreignToken)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", "Secret"))))
                .andExpect(status().isCreated()).andReturn());
        perform(get(base(foreignOrg))).andExpect(status().isForbidden());
        perform(get(base(foreignOrg) + "/" + id(foreign))).andExpect(status().isForbidden());
        for (var request : writeRequests(base(foreignOrg), foreign)) {
            perform(request).andExpect(status().isForbidden());
        }
        // Authorized organization with another organization's product UUID must still fail.
        perform(get(base() + "/" + id(foreign))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        var foreignWrites = writeRequests(base(), foreign);
        for (var request : foreignWrites.subList(1, foreignWrites.size())) {
            perform(request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        }
        perform(put(base() + "/" + id(own) + "/variants/" + variantId(foreign, 0)), change(own, variant(Map.of())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("VARIANT_NOT_FOUND"));
    }

    @Test
    void revokedDisabledAndArchivedOrganizationAccessIsDenied() throws Exception {
        var product = create(Map.of("name", "Privé"));
        jdbc.update("update organization_members set status = 'REVOKED', revoked_at = current_timestamp");
        perform(get(base() + "/" + id(product))).andExpect(status().isForbidden());
        jdbc.update("update organization_members set status = 'ACTIVE', revoked_at = null");
        jdbc.update("update user_accounts set status = 'DISABLED' where id = ?", userId);
        perform(get(base())).andExpect(status().isUnauthorized());
        jdbc.update("update user_accounts set status = 'ACTIVE' where id = ?", userId);
        jdbc.update("update organizations set status = 'ARCHIVED' where id = ?", organizationId);
        perform(get(base())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_FOUND"));
        perform(post(base()), Map.of("name", "Interdit")).andExpect(status().isNotFound());
    }

    @Test
    void allEndpointsRejectMissingAuthentication() throws Exception {
        var product = create(Map.of("name", "Privé"));
        mvc.perform(get(base())).andExpect(status().isUnauthorized());
        mvc.perform(get(base() + "/" + id(product))).andExpect(status().isUnauthorized());
        for (var request : writeRequests(base(), product)) { mvc.perform(request).andExpect(status().isUnauthorized()); }
    }

    @Test
    void staleVersionsCannotOverwriteProductOrVariantChanges() throws Exception {
        var product = create(Map.of("name", "Avant"));
        perform(put(base() + "/" + id(product)), Map.of("name", "Après", "version", 0)).andExpect(status().isOk());
        for (var request : writeRequests(base(), product).subList(1, 6)) {
            perform(request).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_VERSION_CONFLICT"));
        }
        perform(get(base() + "/" + id(product))).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Après")).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void simultaneousDeactivationsCannotRemoveBothRemainingVariants() throws Exception {
        var product = create(Map.of("name", "Concurrent", "variants",
                List.of(variant(Map.of("size", "M")), variant(Map.of("size", "L")))));
        UUID productId = UUID.fromString(id(product));
        var bothLoaded = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 2; i++) {
                UUID variantId = UUID.fromString(variantId(product, i));
                futures.add(executor.submit(() -> {
                    try {
                        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                            var aggregate = products.find(organizationId, productId).orElseThrow();
                            bothLoaded.countDown();
                            try { assertThat(bothLoaded.await(10, TimeUnit.SECONDS)).isTrue(); }
                            catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                            aggregate.requireVersion(0);
                            // Same clock tick as creation also has to dirty/version the aggregate.
                            aggregate.deactivateVariant(variantId, aggregate.getUpdatedAt());
                            products.save(aggregate);
                        });
                        return "SUCCESS";
                    } catch (ApiException ex) { return ex.code(); }
                }));
            }
            assertThat(List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "PRODUCT_VERSION_CONFLICT");
        }
        assertThat(jdbc.queryForObject("select count(*) from product_variants where active", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from products", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentSkuCreationLeavesOneProductAndOneVariant() throws Exception {
        var start = new CountDownLatch(1);
        var data = variant(Map.of()); data.put("sku", "RACE");
        var payload = Map.of("name", "Concurrent SKU", "variants", List.of(data));
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> operation = () -> {
                start.await();
                return perform(post(base()), payload).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(operation);
            var second = executor.submit(operation);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(count("products")).isEqualTo(1);
        assertThat(count("product_variants")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"number", "nested", "array", "null", "blank", "key", "collision", "many"})
    void invalidAttributeContractsAreRejected(String kind) throws Exception {
        var attributes = new LinkedHashMap<String, Object>();
        switch (kind) {
            case "number" -> attributes.put("size", 42);
            case "nested" -> attributes.put("size", Map.of("value", "M"));
            case "array" -> attributes.put("size", List.of("M"));
            case "null" -> attributes.put("size", null);
            case "blank" -> attributes.put("size", " ");
            case "key" -> attributes.put("shoe-size", "42");
            case "collision" -> { attributes.put("Color", "red"); attributes.put("color", "blue"); }
            case "many" -> { for (int i = 0; i < 17; i++) { attributes.put("key" + i, "value"); } }
        }
        perform(post(base()), Map.of("name", "Invalide", "variants", List.of(variant(attributes))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VARIANT_ATTRIBUTES"));
        assertThat(count("products")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.0000000000000000001", "1000000000000000000"})
    void invalidWeightsAndVolumesAreRejectedWithoutRounding(String value) throws Exception {
        for (String field : List.of("estimatedUnitWeightKg", "estimatedUnitVolumeM3")) {
            var data = variant(Map.of()); data.put(field, new BigDecimal(value));
            perform(post(base()), Map.of("name", "Invalide", "variants", List.of(data)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VARIANT_ESTIMATE"));
        }
    }

    @Test
    void serverFieldsPricesInvalidUnitsAndMissingVersionsAreRejected() throws Exception {
        for (String field : List.of("organizationId", "status", "price", "id")) {
            perform(post(base()), Map.of("name", "Forgery", field, "forged")).andExpect(status().isBadRequest());
        }
        var data = variant(Map.of()); data.put("canonicalVariantKey", "forged");
        perform(post(base()), Map.of("name", "Forgery", "variants", List.of(data))).andExpect(status().isBadRequest());
        data = variant(Map.of()); data.put("saleUnit", "KG");
        perform(post(base()), Map.of("name", "Unité", "variants", List.of(data))).andExpect(status().isBadRequest());
        data = variant(Map.of()); data.put("sku", " a b ");
        perform(post(base()), Map.of("name", "SKU", "variants", List.of(data))).andExpect(status().isBadRequest());
        perform(post(base()), Map.of("name", "  ")).andExpect(status().isBadRequest());
        var product = create(Map.of("name", "Valide"));
        perform(put(base() + "/" + id(product)), Map.of("name", "Sans version")).andExpect(status().isBadRequest());
    }

    @Test
    void postgresEnforcesTenantForeignKeysUniquenessAndValueConstraints() throws Exception {
        var product = create(Map.of("name", "Contraintes"));
        UUID productId = UUID.fromString(id(product));
        UUID variantId = UUID.fromString(variantId(product, 0));
        UUID anotherOrg = organization(token, "Autre");
        assertThatThrownBy(() -> jdbc.update("update product_variants set organization_id = ? where id = ?", anotherOrg, variantId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update products set organization_id = ? where id = ?", UUID.randomUUID(), productId))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String sql : List.of(
                "update product_variants set estimated_unit_weight_kg = 0",
                "update product_variants set estimated_unit_volume_m3 = -1",
                "update product_variants set estimated_unit_weight_kg = 'NaN'",
                "update product_variants set estimated_unit_volume_m3 = 'Infinity'",
                "update product_variants set sale_unit = 'KG'",
                "update product_variants set attributes = '[]'::jsonb",
                "update product_variants set attributes = '{\"size\":42}'::jsonb",
                "update product_variants set canonical_variant_key = 'forged'",
                "update product_variants set sku = 'lowercase'",
                "update products set status = 'UNKNOWN'",
                "update products set name = '   '",
                "delete from products",
                "delete from organizations")) {
            assertThatThrownBy(() -> jdbc.update(sql)).as(sql).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("insert into product_variants (id, organization_id, product_id, attributes, canonical_variant_key, sale_unit, active, created_at, updated_at) values (?, ?, ?, '{}'::jsonb, 'DEFAULT', 'UNIT', true, current_timestamp, current_timestamp)",
                UUID.randomUUID(), organizationId, productId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("products")).isEqualTo(1);
    }

    @Test
    void nullCharacterInDescriptionIsAValidationErrorNotADatabaseFailure() throws Exception {
        perform(post(base()), Map.of("name", "Description", "description", "Texte" + (char) 0))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PRODUCT_INFORMATION"));
        assertThat(count("products")).isZero();
    }
    private List<MockHttpServletRequestBuilder> writeRequests(String base, JsonNode product) throws Exception {
        String path = base + "/" + id(product);
        return List.of(
                post(base).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", "Création"))),
                put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", "Modification", "version", product.get("version").asLong()))),
                post(path + "/archive").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(version(product))),
                post(path + "/variants").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(change(product, variant(Map.of("color", "red"))))),
                put(path + "/variants/" + variantId(product, 0)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(change(product, variant(Map.of())))),
                post(path + "/variants/" + variantId(product, 0) + "/deactivate").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(version(product))));
    }

    private UUID account(String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into user_accounts (id, phone_number, password_hash, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', current_timestamp, current_timestamp)",
                id, phone, fixtureHash);
        return id;
    }
    private String token(UUID userId) {
        var claims = JwtClaimsSet.builder().issuer("https://groupia.test").audience(List.of("groupia-api-test"))
                .subject(userId.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    private UUID organization(String token, String name) throws Exception {
        return UUID.fromString(body(mvc.perform(post("/api/organizations").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());
    }
    private JsonNode create(Object request) throws Exception {
        return body(perform(post(base()), request).andExpect(status().isCreated()).andReturn());
    }
    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token));
    }
    private ResultActions perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return perform(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    private Map<String, Object> variant(Map<String, ?> attributes) {
        var result = new LinkedHashMap<String, Object>();
        result.put("attributes", attributes);
        return result;
    }
    private Map<String, Object> change(JsonNode product, Map<String, Object> variant) {
        return Map.of("version", product.get("version").asLong(), "variant", variant);
    }
    private Map<String, Object> version(JsonNode product) { return Map.of("version", product.get("version").asLong()); }
    private String id(JsonNode product) { return product.get("id").asText(); }
    private String variantId(JsonNode product, int index) { return product.get("variants").get(index).get("id").asText(); }
    private String base() { return base(organizationId); }
    private String base(UUID org) { return "/api/organizations/" + org + "/products"; }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
}
