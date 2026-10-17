package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.event.*;
import org.springframework.test.web.servlet.*;
import sn.groupia.identity.api.event.UserAccountCreated;
import sn.groupia.organization.api.*;
import sn.groupia.organization.api.event.OrganizationCreated;
import sn.groupia.shared.api.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class IdentityOrganizationIntegrationTests {
    private static final String PHONE = "+221771234567";
    private static final String SECOND_PHONE = "+221781234567";
    private static final String PASSWORD = "Une phrase solide 2026!";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtDecoder decoder;
    @Autowired JwtEncoder encoder;
    @Autowired ApplicationEvents events;
    @Autowired OrganizationAccess organizationAccess;

    @BeforeEach
    void resetDatabase() {
        SecurityContextHolder.clearContext();
        TestEventDrain.awaitIdle(jdbc);
        jdbc.execute(
                "TRUNCATE TABLE outgoing_provider_operations,order_fulfillment_events, shipping_calculations, shipping_measurements, shipping_assessments, customer_access_sessions, otp_challenges, order_access_links, customer_access_limits, shipment_facts, shipments, supplier_order_anomalies, supplier_orders, payment_webhook_events, payments, payment_requests, order_items, orders, customers, grouping_changes, grouping_offers, grouping_campaigns, product_variants, products, organization_members, organizations, user_accounts, event_publication CASCADE");
    }

    @AfterEach
    void clearAuthenticationAndAwaitModuleEvents() {
        SecurityContextHolder.clearContext();
        await().atMost(java.time.Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select count(*) from event_publication where status in ('PUBLISHED','PROCESSING','RESUBMITTED')",
                                                        Long.class))
                                        .isZero());
    }

    @Test
    void registrationNormalizesPhoneHashesPasswordAndPublishesSafeEvent() throws Exception {
        var result =
                register("77 123 45 67", PASSWORD, "organisateur@example.test")
                        .andExpect(status().isAccepted())
                        .andExpect(content().string(""))
                        .andReturn();
        var id =
                jdbc.queryForObject(
                        "select id::text from user_accounts where phone_number=?",
                        String.class,
                        PHONE);
        UUID.fromString(id);
        String hash =
                jdbc.queryForObject(
                        "select password_hash from user_accounts where id = ?",
                        String.class,
                        UUID.fromString(id));
        assertThat(hash).startsWith("$2a$12$").isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(PASSWORD, hash);
        assertThat(
                        jdbc.queryForObject(
                                "select version from user_accounts where id = ?",
                                Long.class,
                                UUID.fromString(id)))
                .isZero();
        assertThat(events.stream(UserAccountCreated.class).count()).isEqualTo(1);
    }

    @Test
    void duplicateNormalizedPhoneDoesNotRevealAccountExistence() throws Exception {
        register(PHONE, PASSWORD, null).andExpect(status().isAccepted());
        register("00221 77 123 45 67", PASSWORD, null)
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
        assertThat(count("user_accounts")).isEqualTo(1);
        assertThat(events.stream(UserAccountCreated.class).count()).isEqualTo(1);
    }

    @Test
    void concurrentRegistrationsCannotCreateTwoAccounts() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> operation =
                    () -> {
                        start.await();
                        return register(PHONE, PASSWORD, null)
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            var first = executor.submit(operation);
            var second = executor.submit(operation);
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactly(202, 202);
        }
        assertThat(count("user_accounts")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "invalid phone", "+221771234567 ext 12", "+999123456789", ""})
    void invalidPhoneIsRejected(String phone) throws Exception {
        register(phone, PASSWORD, null).andExpect(status().isBadRequest());
        assertThat(count("user_accounts")).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "short",
                "                ",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "ééééééééééééééééééééééééééééééééééééé"
            })
    void invalidPasswordsIncludingBcryptByteOverflowAreRejected(String password) throws Exception {
        var result = register(PHONE, password, null).andExpect(status().isBadRequest()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(password);
        assertThat(count("user_accounts")).isZero();
    }

    @Test
    void invalidEmailIsRejected() throws Exception {
        register(PHONE, PASSWORD, "not-an-email")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(count("user_accounts")).isZero();
    }

    @Test
    void loginReturnsVerifiedShortLivedJwtWithoutSensitiveClaims() throws Exception {
        UUID accountId = registeredId(PHONE);
        var response =
                login("77 123 45 67", PASSWORD)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.tokenType").value("Bearer"))
                        .andExpect(jsonPath("$.expiresIn").value(900))
                        .andExpect(header().string("Cache-Control", containsString("no-store")))
                        .andReturn();
        var token = decoder.decode(body(response).get("accessToken").asText());
        assertThat(token.getSubject()).isEqualTo(accountId.toString());
        assertThat(token.getIssuer().toString()).isEqualTo("https://groupia.test");
        assertThat(token.getAudience()).containsExactly("groupia-api-test");
        assertThat(token.getClaims())
                .doesNotContainKeys(
                        "phoneNumber", "email", "passwordHash", "role", "organizationId");
        assertThat(token.getExpiresAt()).isAfter(token.getIssuedAt());
    }

    @Test
    void unknownUserAndWrongPasswordReturnSameSafeError() throws Exception {
        registeredId(PHONE);
        var wrong =
                login(PHONE, "incorrect password")
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                        .andReturn();
        var unknown =
                login(SECOND_PHONE, PASSWORD)
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                        .andReturn();
        assertThat(body(wrong).get("detail")).isEqualTo(body(unknown).get("detail"));
    }

    @Test
    void allOrganizationEndpointsRequireABearerToken() throws Exception {
        mvc.perform(get("/api/organizations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/api/organizations/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/organizations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void organizationCreationCreatesOwnerAndDefaultsAtomically() throws Exception {
        UUID user = registeredId(PHONE);
        String token = token(PHONE);
        var response =
                createOrganization(token, "  Groupia Dakar  ")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.name").value("Groupia Dakar"))
                        .andExpect(jsonPath("$.defaultCurrency").value("XOF"))
                        .andExpect(jsonPath("$.countryCode").value("SN"))
                        .andExpect(jsonPath("$.timezone").value("Africa/Dakar"))
                        .andExpect(jsonPath("$.status").value("ACTIVE"))
                        .andReturn();
        UUID organization = UUID.fromString(body(response).get("id").asText());
        assertThat(response.getResponse().getHeader("Location"))
                .isEqualTo("/api/organizations/" + organization);
        var member =
                jdbc.queryForMap(
                        "select * from organization_members where organization_id = ?",
                        organization);
        assertThat(member.get("user_account_id")).isEqualTo(user);
        assertThat(member.get("role")).isEqualTo("OWNER");
        assertThat(member.get("status")).isEqualTo("ACTIVE");
        assertThat(member.get("revoked_at")).isNull();
        assertThat(events.stream(OrganizationCreated.class).count()).isEqualTo(1);
    }

    @Test
    void ownerInsertFailureRollsBackTheOrganizationInPostgres() throws Exception {
        registeredId(PHONE);
        String token = token(PHONE);
        jdbc.execute(
                "alter table organization_members add constraint test_reject_owner check (role <> 'OWNER')");
        try {
            createOrganization(token, "Must roll back")
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.trace").doesNotExist())
                    .andExpect(content().string(not(containsString("test_reject_owner"))));
            assertThat(count("organizations")).isZero();
            assertThat(count("organization_members")).isZero();
            assertThat(events.stream(OrganizationCreated.class)).isEmpty();
        } finally {
            jdbc.execute("alter table organization_members drop constraint test_reject_owner");
        }
    }

    @Test
    void listAndReadAreScopedToCurrentMembership() throws Exception {
        registeredId(PHONE);
        registeredId(SECOND_PHONE);
        String alice = token(PHONE);
        String bob = token(SECOND_PHONE);
        UUID aliceOrg = organizationId(alice, "Alice");
        UUID bobOrg = organizationId(bob, "Bob");
        mvc.perform(get("/api/organizations").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(aliceOrg.toString()));
        mvc.perform(get("/api/organizations/" + aliceOrg).header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceOrg.toString()));
        mvc.perform(get("/api/organizations/" + bobOrg).header("Authorization", bearer(alice)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_ACCESS_DENIED"));
        mvc.perform(
                        get("/api/organizations/" + UUID.randomUUID())
                                .header("Authorization", bearer(alice)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_ACCESS_DENIED"));
    }

    @Test
    void revokedMembershipIsExcludedAndDeniedEvenWithExistingJwt() throws Exception {
        UUID user = registeredId(PHONE);
        String token = token(PHONE);
        UUID org = organizationId(token, "Revoked");
        // The application and PostgreSQL container clocks can differ slightly.
        jdbc.update(
                "update organization_members set status = 'REVOKED', revoked_at = greatest(current_timestamp, joined_at) where user_account_id = ?",
                user);
        mvc.perform(get("/api/organizations").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/organizations/" + org).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "OPERATOR", "VIEWER"})
    void membershipRoleUsesExplicitPermissions(String role) throws Exception {
        registeredId(PHONE);
        String token = token(PHONE);
        UUID org = organizationId(token, "Permissions");
        jdbc.update(
                "update organization_members set role = ? where organization_id = ?", role, org);
        mvc.perform(get("/api/organizations/" + org).header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                decoder.decode(token),
                                List.of(),
                                decoder.decode(token).getSubject()));
        assertThatThrownBy(
                        () ->
                                organizationAccess.requirePermission(
                                        org, OrganizationPermission.MANAGE_MEMBERS))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> {
                            assertThat(ex.status()).isEqualTo(403);
                            assertThat(ex.code()).isEqualTo("ORGANIZATION_ACCESS_DENIED");
                        });
    }

    @Test
    void disabledAccountCannotLoginOrUsePreviouslyIssuedJwt() throws Exception {
        UUID user = registeredId(PHONE);
        String token = token(PHONE);
        jdbc.update("update user_accounts set status = 'DISABLED' where id = ?", user);
        login(PHONE, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(get("/api/organizations").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
        createOrganization(token, "Denied").andExpect(status().isUnauthorized());
        assertThat(count("organizations")).isZero();
    }

    @Test
    void archivedOrganizationIsNotListedOrReturned() throws Exception {
        registeredId(PHONE);
        String token = token(PHONE);
        UUID org = organizationId(token, "Archived");
        jdbc.update("update organizations set status = 'ARCHIVED' where id = ?", org);
        mvc.perform(get("/api/organizations").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/organizations/" + org).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_FOUND"));
    }

    @Test
    void membershipUniquenessAndForeignKeysAreEnforcedByPostgres() throws Exception {
        UUID user = registeredId(PHONE);
        UUID org = organizationId(token(PHONE), "Constraints");
        String insert =
                "insert into organization_members (id, organization_id, user_account_id, role, status, joined_at) values (?, ?, ?, 'OWNER', 'ACTIVE', current_timestamp)";
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), org, user))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), org, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from user_accounts where id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("organization_members")).isEqualTo(1);
    }

    @Test
    void organizationInputIsValidatedAndCannotOverrideOwner() throws Exception {
        registeredId(PHONE);
        String token = token(PHONE);
        createOrganization(token, "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(
                        post("/api/organizations")
                                .header("Authorization", bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "name",
                                                        "Forged",
                                                        "userAccountId",
                                                        UUID.randomUUID().toString(),
                                                        "role",
                                                        "OWNER"))))
                .andExpect(status().isBadRequest());
        assertThat(count("organizations")).isZero();
    }

    @Test
    void malformedJsonUuidAndPaginationReturnStableErrors() throws Exception {
        mvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        registeredId(PHONE);
        String token = token(PHONE);
        mvc.perform(get("/api/organizations/not-a-uuid").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/organizations?page=-1").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/organizations?size=101").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "issuer", "audience", "missing-expiry", "subject"})
    void invalidJwtClaimsAreRejected(String mutation) throws Exception {
        UUID user = registeredId(PHONE);
        var claims =
                JwtClaimsSet.builder()
                        .issuer(
                                mutation.equals("issuer")
                                        ? "https://untrusted.test"
                                        : "https://groupia.test")
                        .audience(
                                List.of(
                                        mutation.equals("audience")
                                                ? "other-api"
                                                : "groupia-api-test"))
                        .subject(mutation.equals("subject") ? "invalid-subject" : user.toString())
                        .issuedAt(Instant.now().minusSeconds(3600));
        if (!mutation.equals("missing-expiry")) {
            claims.expiresAt(
                    mutation.equals("expired")
                            ? Instant.now().minusSeconds(120)
                            : Instant.now().plusSeconds(600));
        }
        String jwt =
                encoder.encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                        .getTokenValue();
        mvc.perform(get("/api/organizations").header("Authorization", bearer(jwt)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void forgedSignatureAndUnknownAccountAreRejected() throws Exception {
        registeredId(PHONE);
        String valid = token(PHONE);
        String[] parts = valid.split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        mvc.perform(
                        get("/api/organizations")
                                .header("Authorization", bearer(String.join(".", parts))))
                .andExpect(status().isUnauthorized());
        var claims =
                JwtClaimsSet.builder()
                        .issuer("https://groupia.test")
                        .audience(List.of("groupia-api-test"))
                        .subject(UUID.randomUUID().toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .build();
        String unknown =
                encoder.encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                        .getTokenValue();
        mvc.perform(get("/api/organizations").header("Authorization", bearer(unknown)))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions register(String phone, String password, String email) throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("phoneNumber", phone);
        request.put("password", password);
        if (email != null) {
            request.put("email", email);
        }
        return mvc.perform(
                post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)));
    }

    private ResultActions login(String phone, String password) throws Exception {
        return mvc.perform(
                post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                json.writeValueAsString(
                                        Map.of("phoneNumber", phone, "password", password))));
    }

    private UUID registeredId(String phone) throws Exception {
        register(phone, PASSWORD, null).andExpect(status().isAccepted());
        return jdbc.queryForObject(
                "select id from user_accounts where phone_number=?", UUID.class, phone);
    }

    private String token(String phone) throws Exception {
        return body(login(phone, PASSWORD).andExpect(status().isOk()).andReturn())
                .get("accessToken")
                .asText();
    }

    private ResultActions createOrganization(String token, String name) throws Exception {
        return mvc.perform(
                post("/api/organizations")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name))));
    }

    private UUID organizationId(String token, String name) throws Exception {
        return UUID.fromString(
                body(createOrganization(token, name).andExpect(status().isCreated()).andReturn())
                        .get("id")
                        .asText());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
