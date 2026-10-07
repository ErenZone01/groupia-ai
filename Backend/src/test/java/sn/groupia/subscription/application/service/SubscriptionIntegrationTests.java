package sn.groupia.subscription.application.service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import sn.groupia.organization.api.event.OrganizationCreated;
import sn.groupia.shared.api.ApiException;
import sn.groupia.subscription.api.SubscriptionAccessReason;
import sn.groupia.subscription.api.SubscriptionStatus;
import sn.groupia.subscription.domain.repository.SubscriptionStore;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "groupia.subscription.enforcement-enabled=true",
        "groupia.subscription.bictorys-enabled=true",
        "groupia.subscription.monthly-xof=1234",
        "groupia.subscription.bictorys-plan-id=test-plan"
})
@AutoConfigureMockMvc
@Import({SubscriptionIntegrationTests.DatabaseConfiguration.class, SubscriptionIntegrationTests.ProviderConfiguration.class})
class SubscriptionIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired SubscriptionProvisioning provisioning;
    @Autowired SubscriptionEntitlementService entitlements;
    @Autowired SubscriptionPaymentRecorder payments;
    @Autowired SubscriptionStore subscriptions;
    @Autowired SubscriptionCreatedListener listener;
    @Autowired FakeBillingProvider provider;

    private UUID organizationId;
    private UUID ownerId;
    private String ownerToken;

    @BeforeEach
    void setup() {
        Instant now = clock.instant();
        organizationId = UUID.randomUUID();
        ownerId = account(now);
        jdbc.update("insert into organizations(id,name,status,created_at,updated_at) values (?,?,'ACTIVE',?,?)",
                organizationId, "Boutique abonnement", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        jdbc.update("""
                insert into organization_members(id,organization_id,user_account_id,role,status,joined_at)
                values (?,?,?,'OWNER','ACTIVE',?)
                """, UUID.randomUUID(), organizationId, ownerId, java.sql.Timestamp.from(now));
        ownerToken = token(ownerId);
        provider.reset();
    }

    @Test
    void provisionsOneCalendarMonthFromOrganizationCreation() {
        var zone = ZoneId.of("Africa/Dakar");
        var migrationCutover = subscriptions.migrationAppliedAt().orElseThrow().atZone(zone);
        var creation = migrationCutover.withMonth(1).withDayOfMonth(31).withHour(14)
                .withMinute(0).withSecond(0).withNano(0);
        if (!creation.isAfter(migrationCutover)) creation = creation.plusYears(1);
        Instant created = creation.toInstant();
        var organization = createOrganization(created);
        var access = entitlements.access(organization);
        assertThat(access.trialStartedAt()).isEqualTo(created);
        assertThat(access.trialEndsAt()).isEqualTo(creation.plusMonths(1).toInstant());
        assertThat(access.trialProvenance()).isEqualTo("ORGANIZATION_CREATED");
        assertThat(access.canStartNewBusiness()).isTrue();
        assertThat(access.reason()).isEqualTo(SubscriptionAccessReason.TRIAL_ACTIVE);
    }

    @Test
    void givesMissingLegacyOrganizationsATrialFromMigrationCutover() {
        var legacy = createOrganization(Instant.parse("2020-01-01T00:00:00Z"));
        var cutoff = subscriptions.migrationAppliedAt().orElseThrow();
        var access = entitlements.access(legacy);
        assertThat(access.trialStartedAt()).isEqualTo(cutoff);
        assertThat(access.trialEndsAt()).isEqualTo(cutoff.atZone(java.time.ZoneId.of("Africa/Dakar"))
                .plusMonths(1).toInstant());
        assertThat(access.trialProvenance()).isEqualTo("LEGACY_MIGRATION_TRIAL");
        assertThat(access.canStartNewBusiness()).isTrue();
    }

    @Test
    void eventReplayAndConcurrentEntitlementResolutionCreateOnlyOneSubscription() throws Exception {
        Instant now = clock.instant();
        UUID org = createOrganization(now);
        var event = new OrganizationCreated(UUID.randomUUID(), now, org, ownerId);
        listener.on(event);
        listener.on(event);
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from organization_subscriptions where organization_id=?",
                        Integer.class, org)).isEqualTo(1));

        UUID concurrentOrg = createOrganization(now);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return provisioning.ensure(concurrentOrg); });
            var second = executor.submit(() -> { start.await(); return provisioning.ensure(concurrentOrg); });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).id()).isEqualTo(second.get(20, TimeUnit.SECONDS).id());
        }
        assertThat(jdbc.queryForObject("select count(*) from organization_subscriptions where organization_id=?",
                Integer.class, concurrentOrg)).isEqualTo(1);
    }

    @Test
    void enabledProviderCanOnlyConfigureSubscriptionAndNeverCreatesPaidAccess() throws Exception {
        completeBillingContact();
        mvc.perform(post(subscriptionPath() + "/start").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access.status").value("TRIALING"))
                .andExpect(jsonPath("$.access.canStartNewBusiness").value(true))
                .andExpect(jsonPath("$.access.currentPeriodEndsAt").doesNotExist())
                .andExpect(jsonPath("$.billingProviderSetupPending").value(false));
        assertThat(provider.createCalls()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from subscription_periods where organization_id=?",
                Integer.class, organizationId)).isZero();

        mvc.perform(post(subscriptionPath() + "/start").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access.status").value("TRIALING"));
        assertThat(provider.createCalls()).isEqualTo(1);
    }

    @Test
    void concurrentProviderSetupRequestsUseOneClaimAndOneProviderCall() throws Exception {
        completeBillingContact();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return mvc.perform(post(subscriptionPath() + "/start")
                        .header("Authorization", "Bearer " + ownerToken)).andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                start.await();
                return mvc.perform(post(subscriptionPath() + "/start")
                        .header("Authorization", "Bearer " + ownerToken)).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsOnly(200);
        }
        assertThat(provider.createCalls()).isEqualTo(1);
        assertThat(entitlements.access(organizationId).status()).isEqualTo(SubscriptionStatus.TRIALING);
    }

    @Test
    void verifiedPaidPeriodActivatesAccessAndDuplicateEvidenceDoesNotExtendTwice() {
        Instant old = clock.instant().minusSeconds(60L * 60 * 24 * 40);
        organizationId = createOrganization(old);
        provisioning.ensure(organizationId);
        jdbc.update("""
                update organization_subscriptions
                   set trial_started_at=current_timestamp - interval '2 months',
                       trial_ends_at=current_timestamp - interval '1 month'
                 where organization_id=?
                """, organizationId);
        var evidence = new VerifiedSubscriptionPayment("TEST", "transaction-1", "invoice-1",
                new BigDecimal("1234"), "XOF", clock.instant());

        payments.recordVerifiedPayment(organizationId, evidence);
        var firstEnd = entitlements.access(organizationId).currentPeriodEndsAt();
        assertThat(entitlements.access(organizationId).status()).isEqualTo(SubscriptionStatus.ACTIVE);

        payments.recordVerifiedPayment(organizationId, evidence);
        assertThat(jdbc.queryForObject("select count(*) from subscription_periods where organization_id=?",
                Integer.class, organizationId)).isEqualTo(1);
        assertThat(entitlements.access(organizationId).currentPeriodEndsAt()).isEqualTo(firstEnd);
    }

    @Test
    void concurrentDuplicatePaymentCallbacksAppendOnlyOnePaidPeriod() throws Exception {
        Instant old = clock.instant().minusSeconds(60L * 60 * 24 * 40);
        organizationId = createOrganization(old);
        provisioning.ensure(organizationId);
        var evidence = new VerifiedSubscriptionPayment("TEST", "transaction-race", "invoice-race",
                new BigDecimal("1234"), "XOF", clock.instant());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); payments.recordVerifiedPayment(organizationId, evidence); return true; });
            var second = executor.submit(() -> { start.await(); payments.recordVerifiedPayment(organizationId, evidence); return true; });
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select count(*) from subscription_periods where organization_id=?",
                Integer.class, organizationId)).isEqualTo(1);
    }

    @Test
    void cancellationReplayPreservesTrialAccessAndManagerCannotManageBilling() throws Exception {
        mvc.perform(post(subscriptionPath() + "/cancel").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access.status").value("TRIALING"))
                .andExpect(jsonPath("$.access.canStartNewBusiness").value(true));
        var canceledAt = jdbc.queryForObject(
                "select canceled_at from organization_subscriptions where organization_id=?",
                java.sql.Timestamp.class, organizationId).toInstant();
        mvc.perform(post(subscriptionPath() + "/cancel").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select canceled_at from organization_subscriptions where organization_id=?",
                java.sql.Timestamp.class, organizationId).toInstant()).isEqualTo(canceledAt);

        UUID manager = account(clock.instant());
        jdbc.update("""
                insert into organization_members(id,organization_id,user_account_id,role,status,joined_at)
                values (?,?,?,'MANAGER','ACTIVE',?)
                """, UUID.randomUUID(), organizationId, manager, java.sql.Timestamp.from(clock.instant()));
        String managerToken = token(manager);
        mvc.perform(get(subscriptionPath()).header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
        mvc.perform(post(subscriptionPath() + "/cancel").header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());
        mvc.perform(put(subscriptionPath() + "/billing-contact")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(contactInput())))
                .andExpect(status().isForbidden());
    }

    @Test
    void unavailableProviderLeavesTrialAndEntitlementUsable() throws Exception {
        provider.setAvailable(false);
        mvc.perform(post(subscriptionPath() + "/start").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_BILLING_UNAVAILABLE"));
        assertThat(entitlements.access(organizationId).status()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(entitlements.access(organizationId).canStartNewBusiness()).isTrue();
        assertThat(provider.createCalls()).isZero();
    }

    @Test
    void frontendCannotForgeBillingAuthorityAndPaidPeriodHistoryCannotBeChanged() {
        assertThatThrownBy(() -> payments.recordVerifiedPayment(organizationId,
                new VerifiedSubscriptionPayment("TEST", "bad-amount", "bad-invoice",
                        new BigDecimal("1"), "XOF", clock.instant())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from subscription_periods where organization_id=?",
                Integer.class, organizationId)).isZero();
    }

    private void completeBillingContact() throws Exception {
        mvc.perform(put(subscriptionPath() + "/billing-contact")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(contactInput())))
                .andExpect(status().isOk());
    }

    private Object contactInput() {
        return java.util.Map.of("name", "Propriétaire Boutique", "phoneE164", "+221771234567",
                "email", "owner@example.test");
    }

    private UUID createOrganization(Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organizations(id,name,status,created_at,updated_at) values (?,?,'ACTIVE',?,?)",
                id, "Boutique de test " + id, java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt));
        jdbc.update("""
                insert into organization_members(id,organization_id,user_account_id,role,status,joined_at)
                values (?,?,?,'OWNER','ACTIVE',?)
                """, UUID.randomUUID(), id, ownerId, java.sql.Timestamp.from(createdAt));
        return id;
    }

    private UUID account(Instant now) {
        UUID id = UUID.randomUUID();
        long digits = Math.floorMod(id.getLeastSignificantBits(), 1_000_000_000L);
        jdbc.update("""
                insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at)
                values (?, ?, ?, 'ACTIVE', ?, ?)
                """, id, "+221" + String.format("%09d", digits),
                passwords.encode("Subscription fixture password 2026!"),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        return id;
    }

    private String token(UUID userId) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("https://groupia.test").audience(List.of("groupia-api-test"))
                .subject(userId.toString()).issuedAt(now).expiresAt(now.plusSeconds(600)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private String subscriptionPath() { return "/api/organizations/" + organizationId + "/subscription"; }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean @ServiceConnection PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
        }
        @Bean DynamicPropertyRegistrar subscriptionTestProperties() {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            String encoded = Base64.getEncoder().encodeToString(secret);
            return registry -> {
                registry.add("groupia.security.jwt.secret-base64", () -> encoded);
                registry.add("groupia.security.jwt.issuer", () -> "https://groupia.test");
                registry.add("groupia.security.jwt.audience", () -> "groupia-api-test");
                registry.add("groupia.security.jwt.access-token-ttl", () -> "15m");
                registry.add("groupia.ordering.expiration.enabled", () -> "false");
                registry.add("groupia.events.recovery.enabled", () -> "false");
            };
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean @Primary FakeBillingProvider fakeSubscriptionBillingProvider() { return new FakeBillingProvider(); }
    }

    static class FakeBillingProvider implements SubscriptionBillingProvider {
        private final AtomicBoolean available = new AtomicBoolean(true);
        private final AtomicInteger createCalls = new AtomicInteger();
        @Override public boolean available() { return available.get(); }
        @Override public ProviderSetup createCustomerAndSubscription(ProviderSetupRequest request) {
            createCalls.incrementAndGet();
            try { Thread.sleep(150); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            return new ProviderSetup("TEST", "customer-" + request.idempotencyKey(),
                    "subscription-" + request.idempotencyKey());
        }
        @Override public void cancelSubscription(String customerId, String subscriptionId) {}
        int createCalls() { return createCalls.get(); }
        void setAvailable(boolean value) { available.set(value); }
        void reset() { available.set(true); createCalls.set(0); }
    }
}
