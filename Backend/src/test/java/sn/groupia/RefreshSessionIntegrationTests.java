package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sn.groupia.identity.application.service.*;
import sn.groupia.identity.infrastructure.persistence.JdbcAuthSessions;
import sn.groupia.identity.infrastructure.security.*;
import tools.jackson.databind.ObjectMapper;

@org.junit.jupiter.api.extension.ExtendWith(
        org.springframework.boot.test.system.OutputCaptureExtension.class)
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PaymentTestConfiguration.class})
class RefreshSessionIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired RefreshSessions sessions;
    @Autowired RefreshSecrets secrets;
    @Autowired SessionSettings settings;
    @Autowired AccessTokens tokens;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PaymentTestConfiguration.MutablePaymentClock clock;
    UUID user;
    static String passwordHash;
    static final String PASSWORD = "Only synthetic session test password!",
            COOKIE = "__Secure-groupia_refresh";

    @BeforeEach
    void setup() {
        clock.reset();
        TestEventDrain.awaitIdle(jdbc);
        jdbc.execute(
                "truncate auth_sessions,user_accounts,organizations,event_publication cascade");
        if (passwordHash == null) passwordHash = encoder.encode(PASSWORD);
        user = UUID.randomUUID();
        jdbc.update(
                "insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at,version) values (?,'+221771234567',?,'ACTIVE',now(),now(),0)",
                user,
                passwordHash);
    }

    MockHttpServletRequestBuilder auth(String action) {
        return post("/api/auth/" + action)
                .contentType("application/json")
                .header("Origin", "https://groupia.test")
                .header("X-Groupia-Auth", "1");
    }

    String credentials(String password) {
        return json.writeValueAsString(
                Map.of("phoneNumber", "+221771234567", "password", password));
    }

    MvcResult login() throws Exception {
        return mvc.perform(auth("login").content(credentials(PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
    }

    String raw(MvcResult r) {
        return r.getResponse().getCookie(COOKIE).getValue();
    }

    ResultActions refresh(String raw) throws Exception {
        return mvc.perform(auth("refresh").cookie(new Cookie(COOKIE, raw)));
    }

    long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    @Test
    void loginHasOnlyHashedRefreshAndSafeJwtAndCookie(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var r = login();
        String raw = raw(r);
        var body = json.readTree(r.getResponse().getContentAsString());
        var jwt = decoder.decode(body.path("accessToken").asText());
        assertThat(raw).hasSize(43);
        assertThat(output.getAll())
                .doesNotContain(
                        raw,
                        body.path("accessToken").asText(),
                        settings.refreshPepperBase64(),
                        PASSWORD);
        assertThat(count("auth_sessions")).isEqualTo(1);
        assertThat(count("auth_refresh_tokens")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select token_hash from auth_refresh_tokens", byte[].class))
                .isEqualTo(secrets.tokenHash(raw));
        assertThat(
                        jdbc.queryForObject(
                                "select row_to_json(t)::text from auth_refresh_tokens t",
                                String.class))
                .doesNotContain(raw);
        assertThat(r.getResponse().getContentAsString())
                .doesNotContain(raw, "refreshToken", PASSWORD, "+221");
        assertThat(r.getResponse().getHeader("Set-Cookie"))
                .contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/api/auth")
                .doesNotContain("Domain=");
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jwt.getClaims().keySet())
                .containsExactlyInAnyOrder(
                        "iss", "aud", "sub", "iat", "exp", "jti", "sid", "auth_time");
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(body.path("sessionId").asText());
    }

    @Test
    void wrongCredentialsNeverCreateSession() throws Exception {
        mvc.perform(auth("login").content(credentials("wrong password")))
                .andExpect(status().isUnauthorized());
        jdbc.update("update user_accounts set status='DISABLED' where id=?", user);
        mvc.perform(auth("login").content(credentials(PASSWORD)))
                .andExpect(status().isUnauthorized());
        assertThat(count("auth_sessions")).isZero();
    }

    @Test
    void rotationPreservesSessionAndAuthTime() throws Exception {
        var a = login();
        var before =
                decoder.decode(
                        json.readTree(a.getResponse().getContentAsString())
                                .path("accessToken")
                                .asText());
        var absolute =
                jdbc.queryForObject(
                        "select absolute_expires_at from auth_sessions", java.sql.Timestamp.class);
        clock.advance(Duration.ofSeconds(2));
        var b = refresh(raw(a)).andExpect(status().isOk()).andReturn();
        var after =
                decoder.decode(
                        json.readTree(b.getResponse().getContentAsString())
                                .path("accessToken")
                                .asText());
        assertThat(raw(b)).isNotEqualTo(raw(a));
        assertThat(after.getId()).isNotEqualTo(before.getId());
        assertThat(after.getIssuedAt()).isAfter(before.getIssuedAt());
        assertThat(after.getClaimAsString("sid")).isEqualTo(before.getClaimAsString("sid"));
        assertThat((Object) after.getClaim("auth_time")).isEqualTo(before.getClaim("auth_time"));
        assertThat(
                        jdbc.queryForObject(
                                "select absolute_expires_at from auth_sessions",
                                java.sql.Timestamp.class))
                .isEqualTo(absolute);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_refresh_tokens where consumed_at is null",
                                Long.class))
                .isEqualTo(1);
        refresh(raw(a))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_CONCURRENT"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void replayRevocationActuallyCommits() throws Exception {
        String old = raw(login());
        String next = raw(refresh(old).andExpect(status().isOk()).andReturn());
        clock.advance(Duration.ofSeconds(16));
        refresh(old)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_REUSED"));
        assertThat(jdbc.queryForObject("select revocation_reason from auth_sessions", String.class))
                .isEqualTo("REUSED");
        refresh(next).andExpect(status().isUnauthorized());
    }

    @Test
    void graceBoundaryDoesNotRevoke() throws Exception {
        String old = raw(login());
        refresh(old).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(15));
        refresh(old).andExpect(status().isConflict());
        assertThat(
                        jdbc.queryForObject(
                                "select revoked_at from auth_sessions", java.sql.Timestamp.class))
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad", "", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidTokenDoesNotAllocate(String value) throws Exception {
        refresh(value).andExpect(status().isUnauthorized());
        assertThat(count("auth_sessions")).isZero();
        assertThat(count("auth_rate_limits")).isZero();
    }

    @Test
    void absentAndDuplicateCookiesRejected() throws Exception {
        mvc.perform(auth("refresh")).andExpect(status().isUnauthorized());
        var raw = raw(login());
        mvc.perform(auth("refresh").cookie(new Cookie(COOKIE, raw), new Cookie(COOKIE, raw)))
                .andExpect(status().isUnauthorized());
        assertThat(count("auth_refresh_tokens")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 31})
    void expirations(int days) throws Exception {
        String raw = raw(login());
        clock.advance(Duration.ofDays(days));
        refresh(raw).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select revocation_reason from auth_sessions", String.class))
                .isEqualTo("EXPIRED");
    }

    @Test
    void disabledAccountRevocationCommits() throws Exception {
        String raw = raw(login());
        jdbc.update("update user_accounts set status='DISABLED' where id=?", user);
        refresh(raw).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select revocation_reason from auth_sessions", String.class))
                .isEqualTo("ACCOUNT_DISABLED");
    }

    @Test
    void absoluteLifetimeCapsAccessAndCookie() throws Exception {
        String raw = raw(login());
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofDays(5));
            raw = raw(refresh(raw).andExpect(status().isOk()).andReturn());
        }
        clock.advance(Duration.ofDays(5).minusSeconds(30));
        var r = refresh(raw).andExpect(status().isOk()).andReturn();
        var body = json.readTree(r.getResponse().getContentAsString());
        assertThat(body.path("expiresIn").asLong()).isBetween(29L, 30L);
        assertThat(r.getResponse().getCookie(COOKIE).getMaxAge()).isBetween(29, 30);
        var claims =
                com.nimbusds.jwt.SignedJWT.parse(body.path("accessToken").asText())
                        .getJWTClaimsSet();
        assertThat(claims.getExpirationTime().toInstant())
                .isBeforeOrEqualTo(
                        jdbc.queryForObject(
                                        "select absolute_expires_at from auth_sessions",
                                        java.sql.Timestamp.class)
                                .toInstant());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void logoutCurrentOrPredecessor(boolean old) throws Exception {
        String a = raw(login());
        String b = raw(refresh(a).andExpect(status().isOk()).andReturn());
        for (int i = 0; i < 2; i++)
            mvc.perform(auth("logout").cookie(new Cookie(COOKIE, old ? a : b)))
                    .andExpect(status().isNoContent())
                    .andExpect(
                            header().string(
                                            "Set-Cookie",
                                            org.hamcrest.Matchers.containsString("Max-Age=0")));
        refresh(b).andExpect(status().isUnauthorized());
    }

    @Test
    void absentLogoutIsIdempotent() throws Exception {
        mvc.perform(auth("logout")).andExpect(status().isNoContent());
        mvc.perform(auth("logout").cookie(new Cookie(COOKIE, secrets.generate())))
                .andExpect(status().isNoContent());
        assertThat(count("auth_sessions")).isZero();
    }

    @Test
    void quotaStillAllowsLogout() throws Exception {
        String raw = raw(login());
        for (int i = 0; i < 30; i++) raw = raw(refresh(raw).andExpect(status().isOk()).andReturn());
        refresh(raw)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mvc.perform(auth("logout").cookie(new Cookie(COOKIE, raw)))
                .andExpect(status().isNoContent());
        assertThat(count("auth_rate_limits")).isZero();
    }

    @Test
    void independentInstancesRotateOnce() throws Exception {
        String raw = raw(login());
        var other =
                new RefreshSessions(
                        new JdbcAuthSessions(new JdbcTemplate(jdbc.getDataSource())),
                        secrets,
                        settings,
                        tokens,
                        clock);
        var tx = new TransactionTemplate(transactions);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                gate.await();
                                return sessions.refresh(raw);
                            });
            var b =
                    pool.submit(
                            () -> {
                                gate.await();
                                return tx.execute(s -> other.refresh(raw));
                            });
            gate.countDown();
            assertThat(List.of(a.get().status(), b.get().status()))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(count("auth_refresh_tokens")).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select revoked_at from auth_sessions", java.sql.Timestamp.class))
                .isNull();
    }

    @Test
    void concurrentLogoutRevokesFamily() throws Exception {
        String raw = raw(login());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> sessions.refresh(raw));
            var b = pool.submit(() -> sessions.logout(raw));
            a.get();
            b.get();
        }
        assertThat(jdbc.queryForObject("select revocation_reason from auth_sessions", String.class))
                .isEqualTo("LOGOUT");
    }

    @Test
    void dbRejectsDuplicateActiveAndLifetimeRewrite() throws Exception {
        var r = login();
        UUID sid =
                UUID.fromString(
                        json.readTree(r.getResponse().getContentAsString())
                                .path("sessionId")
                                .asText());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "insert into auth_refresh_tokens(session_id,generation,token_hash,issued_at) values (?,2,?,now())",
                                        sid,
                                        secrets.tokenHash(secrets.generate())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update auth_sessions set absolute_expires_at=absolute_expires_at+interval '1 day'"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void persistenceFailureDoesNotSendCookieOrConsumeToken() throws Exception {
        String raw = raw(login());
        jdbc.execute(
                "alter table auth_refresh_tokens add constraint test_rotation_failure check(generation=1) not valid");
        try {
            refresh(raw)
                    .andExpect(status().isInternalServerError())
                    .andExpect(header().doesNotExist("Set-Cookie"));
            assertThat(
                            jdbc.queryForObject(
                                    "select consumed_at from auth_refresh_tokens",
                                    java.sql.Timestamp.class))
                    .isNull();
        } finally {
            jdbc.execute("alter table auth_refresh_tokens drop constraint test_rotation_failure");
        }
        refresh(raw).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing-origin",
                "null-origin",
                "other-origin",
                "missing-header",
                "form",
                "cross-site"
            })
    void rejectsInvalidBrowserContract(String defect) throws Exception {
        var req = auth("refresh");
        switch (defect) {
            case "missing-origin" ->
                    req.with(
                            r -> {
                                r.removeHeader("Origin");
                                return r;
                            });
            case "null-origin" ->
                    req.with(
                            r -> {
                                r.removeHeader("Origin");
                                r.addHeader("Origin", "null");
                                return r;
                            });
            case "other-origin" ->
                    req.with(
                            r -> {
                                r.removeHeader("Origin");
                                r.addHeader("Origin", "https://evil.test");
                                return r;
                            });
            case "missing-header" ->
                    req.with(
                            r -> {
                                r.removeHeader("X-Groupia-Auth");
                                return r;
                            });
            case "form" -> req.contentType("application/x-www-form-urlencoded");
            case "cross-site" -> req.header("Sec-Fetch-Site", "cross-site");
        }
        mvc.perform(req).andExpect(status().is4xxClientError());
        assertThat(count("auth_sessions")).isZero();
    }

    @Test
    void cookieDoesNotAuthorizeBusinessApi() throws Exception {
        mvc.perform(get("/api/organizations").cookie(new Cookie(COOKIE, raw(login()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshIsCookieOnly() throws Exception {
        String raw = raw(login());
        mvc.perform(auth("refresh").header("Authorization", "Bearer " + raw))
                .andExpect(status().is4xxClientError());
        mvc.perform(auth("refresh").queryParam("token", raw)).andExpect(status().isBadRequest());
        mvc.perform(auth("refresh").content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void loginReplacesOnlyItsBrowser() throws Exception {
        var a = login();
        var b = login();
        mvc.perform(auth("login").cookie(new Cookie(COOKIE, raw(a))).content(credentials(PASSWORD)))
                .andExpect(status().isOk());
        refresh(raw(a)).andExpect(status().isUnauthorized());
        refresh(raw(b)).andExpect(status().isOk());
    }

    @Test
    void purgeLeavesUnexpiredFamilies() throws Exception {
        login();
        sessions.purge();
        assertThat(count("auth_sessions")).isEqualTo(1);
        clock.advance(Duration.ofDays(31));
        sessions.purge();
        assertThat(count("auth_sessions")).isZero();
        assertThat(count("auth_refresh_tokens")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"register", "login", "refresh", "logout"})
    void protectsEveryAuthMutationFromMissingAndDuplicateOrigins(String action) throws Exception {
        mvc.perform(
                        post("/api/auth/" + action)
                                .contentType("application/json")
                                .header("X-Groupia-Auth", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(auth(action).header("Origin", "https://groupia.test"))
                .andExpect(status().isForbidden());
        mvc.perform(auth(action).header("X-Groupia-Auth", "1")).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/" + action)).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void replayStillRevokesAfterQuotaExhaustion() throws Exception {
        String initial = raw(login()), current = initial;
        for (int i = 0; i < 30; i++)
            current = raw(refresh(current).andExpect(status().isOk()).andReturn());
        clock.advance(Duration.ofSeconds(16));
        refresh(initial)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_REUSED"));
        refresh(current).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select revocation_reason from auth_sessions", String.class))
                .isEqualTo("REUSED");
    }

    @Test
    void partialUniqueIndexRejectsConcurrentActiveTokensEvenWithoutServiceLock() throws Exception {
        login();
        UUID sid = jdbc.queryForObject("select id from auth_sessions", UUID.class);
        jdbc.update("update auth_refresh_tokens set consumed_at=issued_at where session_id=?", sid);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.List<Future<Boolean>> results = new ArrayList<>();
            for (long generation : List.of(2L, 3L))
                results.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        jdbc.update(
                                                "insert into auth_refresh_tokens(session_id,generation,token_hash,issued_at) values (?,?,?,now())",
                                                sid,
                                                generation,
                                                secrets.tokenHash(secrets.generate()));
                                        return true;
                                    } catch (
                                            org.springframework.dao.DataIntegrityViolationException
                                                    expected) {
                                        return false;
                                    }
                                }));
            gate.countDown();
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_refresh_tokens where consumed_at is null",
                                Integer.class))
                .isEqualTo(1);
    }
}
