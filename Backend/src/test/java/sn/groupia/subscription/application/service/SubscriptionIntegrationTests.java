package sn.groupia.subscription.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import sn.groupia.organization.api.event.OrganizationCreated;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.api.event.*;
import sn.groupia.payment.application.service.PaymentWebhooks;
import sn.groupia.payment.domain.service.*;
import sn.groupia.subscription.api.SubscriptionAccessReason;
import sn.groupia.subscription.api.SubscriptionStatus;
import sn.groupia.subscription.api.dto.SubscriptionInvoiceView;
import sn.groupia.subscription.domain.repository.SubscriptionStore;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        properties = {
            "groupia.subscription.enforcement-enabled=true",
            "groupia.subscription.bictorys-enabled=true",
            "groupia.subscription.monthly-xof=1234",
            "groupia.subscription.trial-months=2",
            "groupia.subscription.bictorys-plan-id=test-plan"
        })
@AutoConfigureMockMvc
@Import({
    SubscriptionIntegrationTests.DatabaseConfiguration.class,
    SubscriptionIntegrationTests.ProviderConfiguration.class
})
class SubscriptionIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BillingClock clock;
    @Autowired PaymentWebhooks webhooks;
    @Autowired PaymentOperations operations;
    @Autowired SubscriptionProvisioning provisioning;
    @Autowired SubscriptionEntitlementService entitlements;
    @Autowired SubscriptionPaymentRecorder payments;
    @Autowired SubscriptionStore subscriptions;
    @Autowired SubscriptionCreatedListener listener;
    @Autowired ChargeProvider provider;

    private UUID organizationId;
    private UUID ownerId;
    private String ownerToken;

    @BeforeEach
    void setup() {
        clock.reset();
        Instant now = clock.instant();
        organizationId = UUID.randomUUID();
        ownerId = account(now);
        jdbc.update(
                "insert into organizations(id,name,status,created_at,updated_at) values (?,?,'ACTIVE',?,?)",
                organizationId,
                "Boutique abonnement",
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now));
        jdbc.update(
                """
                insert into organization_members(id,organization_id,user_account_id,role,status,joined_at)
                values (?,?,?,'OWNER','ACTIVE',?)
                """,
                UUID.randomUUID(),
                organizationId,
                ownerId,
                java.sql.Timestamp.from(now));
        ownerToken = token(ownerId);
        provider.reset();
    }

    @Test
    void provisionsOneCalendarMonthFromOrganizationCreation() {
        var zone = ZoneId.of("Africa/Dakar");
        var migrationCutover = subscriptions.migrationAppliedAt().orElseThrow().atZone(zone);
        var creation =
                migrationCutover
                        .withMonth(1)
                        .withDayOfMonth(31)
                        .withHour(14)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);
        if (!creation.isAfter(migrationCutover)) creation = creation.plusYears(1);
        Instant created = creation.toInstant();
        var organization = createOrganization(created);
        var access = entitlements.access(organization);
        assertThat(access.trialStartedAt()).isEqualTo(created);
        assertThat(access.trialEndsAt()).isEqualTo(creation.plusMonths(2).toInstant());
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
        assertThat(access.trialEndsAt())
                .isEqualTo(
                        cutoff.atZone(java.time.ZoneId.of("Africa/Dakar"))
                                .plusMonths(2)
                                .toInstant());
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
        await().atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select count(*) from organization_subscriptions where organization_id=?",
                                                        Integer.class,
                                                        org))
                                        .isEqualTo(1));

        UUID concurrentOrg = createOrganization(now);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return provisioning.ensure(concurrentOrg);
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return provisioning.ensure(concurrentOrg);
                            });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).id())
                    .isEqualTo(second.get(20, TimeUnit.SECONDS).id());
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from organization_subscriptions where organization_id=?",
                                Integer.class,
                                concurrentOrg))
                .isEqualTo(1);
    }

    @Test
    void retiredProviderRoutesNeverCreatePaidAccess() throws Exception {
        for (String route : List.of("/start", "/cancel"))
            mvc.perform(
                            post(subscriptionPath() + route)
                                    .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("SUBSCRIPTION_PROVIDER_BILLING_RETIRED"));
        assertThat(provider.calls.get()).isZero();
        assertThat(subscriptions.history(organizationId)).isEmpty();
    }

    @Test
    void concurrentInvoiceCreationUsesOneObligationAndStableKey() throws Exception {
        completeBillingContact();
        String key = UUID.randomUUID().toString();
        var latch = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                latch.await();
                                return create(key);
                            });
            var b =
                    pool.submit(
                            () -> {
                                latch.await();
                                return create(key);
                            });
            latch.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS).id())
                    .isEqualTo(b.get(20, TimeUnit.SECONDS).id());
        }
        var first = create(key);
        clock.advance(Duration.ofDays(100));
        assertThat(create(key).id()).isEqualTo(first.id());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from subscription_invoices where organization_id=?",
                                Integer.class,
                                organizationId))
                .isEqualTo(1);
        assertThat(provider.calls.get()).isZero();
    }

    @Test
    void verifiedPaidPeriodActivatesAccessAndDuplicateEvidenceDoesNotExtendTwice()
            throws Exception {
        expireTrial();
        completeBillingContact();
        var invoice = create(UUID.randomUUID().toString());
        var attempt = pay(invoice);
        var observation = provider.success(attempt.id(), "TX-" + UUID.randomUUID());
        receive(observation);
        receive(observation);
        awaitPaid(invoice);
        assertThat(subscriptions.history(organizationId)).hasSize(1);
        assertThat(entitlements.access(organizationId).status())
                .isEqualTo(SubscriptionStatus.ACTIVE);
        var proof = operations.settlementEvidence(organizationId, invoice.paymentRequestId());
        var event =
                new PaymentObligationSettled(
                        UUID.randomUUID(),
                        proof.confirmedAt(),
                        organizationId,
                        proof.target(),
                        proof.paymentRequestId(),
                        proof.paymentId(),
                        proof.purpose(),
                        proof.amount(),
                        proof.currency());
        for (int n = 0; n < 20; n++) payments.record(event);
        assertThat(subscriptions.history(organizationId)).hasSize(1);
        assertThatThrownBy(
                        () ->
                                payments.record(
                                        new PaymentObligationSettled(
                                                UUID.randomUUID(),
                                                proof.confirmedAt(),
                                                organizationId,
                                                proof.target(),
                                                proof.paymentRequestId(),
                                                proof.paymentId(),
                                                proof.purpose(),
                                                BigDecimal.ONE,
                                                proof.currency())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from merchant_ledger_entries where organization_id=?",
                                Integer.class,
                                organizationId))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from orders where organization_id=?",
                                Integer.class,
                                organizationId))
                .isZero();
    }

    @Test
    void standardMonthlyPaymentUsesOneCalendarMonthEvenWithTwoMonthTrialAtMonthEnd()
            throws Exception {
        expireTrial();
        completeBillingContact();
        clock.at(Instant.parse("2027-01-31T12:00:00Z"));
        var invoice = create(UUID.randomUUID().toString());
        assertThat(invoice.periodStart()).isEqualTo(clock.instant());
        assertThat(invoice.periodEnd()).isEqualTo(Instant.parse("2027-02-28T12:00:00Z"));
        var attempt = pay(invoice);
        receive(provider.success(attempt.id(), "TX-" + UUID.randomUUID()));
        awaitPaid(invoice);
        assertThat(subscriptions.history(organizationId).getFirst().periodEnd())
                .isEqualTo(invoice.periodEnd());
    }

    @Test
    void concurrentDuplicatePaymentCallbacksAppendOnlyOnePaidPeriod() throws Exception {
        completeBillingContact();
        var invoice = create(UUID.randomUUID().toString());
        var attempt = pay(invoice);
        var proof = provider.success(attempt.id(), "TX-" + UUID.randomUUID());
        var latch = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                latch.await();
                                return receive(proof);
                            });
            var b =
                    pool.submit(
                            () -> {
                                latch.await();
                                return receive(proof);
                            });
            latch.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
        }
        awaitPaid(invoice);
        assertThat(subscriptions.history(organizationId)).hasSize(1);
    }

    @Test
    void managerReadsButCannotCreateOrPayAndViewerCannotReadFinancialDetails() throws Exception {
        completeBillingContact();
        var invoice = create(UUID.randomUUID().toString());
        jdbc.update(
                "update organization_members set role='MANAGER' where organization_id=?",
                organizationId);
        mvc.perform(
                        get(subscriptionPath() + "/invoices")
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mvc.perform(
                        post(subscriptionPath() + "/invoices")
                                .header("Authorization", "Bearer " + ownerToken)
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(subscriptionPath() + "/invoices/" + invoice.id() + "/payment")
                                .header("Authorization", "Bearer " + ownerToken)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"provider\":\"TEST_BILLING\"}"))
                .andExpect(status().isForbidden());
        jdbc.update(
                "update organization_members set role='VIEWER' where organization_id=?",
                organizationId);
        mvc.perform(
                        get("/api/organizations/"
                                        + organizationId
                                        + "/payment-requests/"
                                        + invoice.paymentRequestId())
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/organizations/" + organizationId + "/payment-requests")
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void unavailableProviderLeavesTrialAndEntitlementUsable() throws Exception {
        completeBillingContact();
        var invoice = create(UUID.randomUUID().toString());
        mvc.perform(
                        post(subscriptionPath() + "/invoices/" + invoice.id() + "/payment")
                                .header("Authorization", "Bearer " + ownerToken)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"provider\":\"MISSING\"}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(entitlements.access(organizationId).status())
                .isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(subscriptions.history(organizationId)).isEmpty();
    }

    @Test
    void futurePeriodAndCheckoutNeverGrantEarlyAccessAndSuspensionSurvivesPayment()
            throws Exception {
        completeBillingContact();
        var invoice = create(UUID.randomUUID().toString());
        assertThat(invoice.periodStart())
                .isEqualTo(provisioning.ensure(organizationId).trialEndsAt());
        var attempt = pay(invoice);
        assertThat(subscriptions.history(organizationId)).isEmpty();
        receive(provider.success(attempt.id(), "TX-" + UUID.randomUUID()));
        awaitPaid(invoice);
        assertThat(entitlements.access(organizationId).status())
                .isEqualTo(SubscriptionStatus.TRIALING);
        mvc.perform(
                        post(subscriptionPath() + "/invoices")
                                .header("Authorization", "Bearer " + ownerToken)
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict());
        clock.at(invoice.periodStart().plusSeconds(1));
        assertThat(entitlements.access(organizationId).status())
                .isEqualTo(SubscriptionStatus.ACTIVE);
        var next = create(UUID.randomUUID().toString());
        assertThat(next.periodStart()).isEqualTo(invoice.periodEnd());
        jdbc.update(
                "update organization_subscriptions set status='SUSPENDED' where organization_id=?",
                organizationId);
        var second = pay(next);
        receive(provider.success(second.id(), "TX-" + UUID.randomUUID()));
        awaitPaid(next);
        assertThat(entitlements.access(organizationId).status())
                .isEqualTo(SubscriptionStatus.SUSPENDED);
        assertThat(subscriptions.history(organizationId)).hasSize(2);
    }

    @Test
    void frontendCannotForgeBillingAuthorityAndPaidPeriodHistoryCannotBeChanged() throws Exception {
        completeBillingContact();
        var invoice = create(UUID.randomUUID().toString());
        var attempt = pay(invoice);
        var good = provider.success(attempt.id(), "TX-" + UUID.randomUUID());
        for (String field : List.of("amount", "currency", "reference", "account", "transaction")) {
            var bad =
                    new ProviderObservation(
                            good.provider(),
                            field.equals("account") ? "wrong" : good.providerAccountReference(),
                            good.merchantReference(),
                            field.equals("transaction") ? null : good.providerTransactionId(),
                            null,
                            null,
                            null,
                            field.equals("amount") ? BigDecimal.ONE : good.amount(),
                            field.equals("currency") ? "EUR" : "XOF",
                            PaymentStatus.SUCCEEDED,
                            clock.instant(),
                            new ProviderMetadata(
                                    field.equals("reference") ? "wrong" : invoice.reference(),
                                    null,
                                    null,
                                    null));
            receive(bad);
            assertThat(subscriptions.history(organizationId)).isEmpty();
        }
        receive(good);
        awaitPaid(invoice);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update subscription_periods set amount=1 where organization_id=?",
                                        organizationId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update subscription_invoices set amount=1 where id=?",
                                        invoice.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void duplicateDebitCreatesReviewNotAnotherMonthAndManagerCannotResolveIt() throws Exception {
        completeBillingContact();
        var invoice = create(UUID.randomUUID().toString());
        var attempt = pay(invoice);
        receive(provider.success(attempt.id(), "TX-" + UUID.randomUUID()));
        awaitPaid(invoice);
        receive(provider.success(attempt.id(), "TX-" + UUID.randomUUID()));
        assertThat(subscriptions.history(organizationId)).hasSize(1);
        UUID review =
                jdbc.queryForObject(
                        "select id from payment_reviews where organization_id=?",
                        UUID.class,
                        organizationId);
        mvc.perform(
                        get("/api/organizations/" + organizationId + "/payment-reviews/" + review)
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canResolve").value(true));
        jdbc.update(
                "update organization_members set role='MANAGER' where organization_id=?",
                organizationId);
        mvc.perform(
                        get("/api/organizations/" + organizationId + "/payment-reviews/" + review)
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canResolve").value(false));
        jdbc.update(
                "update organization_members set role='VIEWER' where organization_id=?",
                organizationId);
        mvc.perform(
                        get("/api/organizations/" + organizationId + "/payment-reviews/" + review)
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void expiredInvoiceCannotGrantHistoryAndLongLapseRestartsNow() throws Exception {
        expireTrial();
        completeBillingContact();
        var old = create(UUID.randomUUID().toString());
        var attempt = pay(old);
        clock.at(old.periodEnd().plus(Duration.ofDays(40)));
        receive(provider.success(attempt.id(), "TX-" + UUID.randomUUID()));
        assertThat(subscriptions.history(organizationId)).isEmpty();
        assertThat(operations.request(organizationId, old.paymentRequestId()).settledPaymentId())
                .isNull();
        var current = create(UUID.randomUUID().toString());
        assertThat(current.periodStart()).isEqualTo(clock.instant());
        assertThat(current.periodEnd())
                .isEqualTo(
                        clock.instant()
                                .atZone(ZoneId.of("Africa/Dakar"))
                                .plusMonths(1)
                                .toInstant());
        assertThat(
                        jdbc.queryForObject(
                                "select status from subscription_invoices where id=?",
                                String.class,
                                old.id()))
                .isEqualTo("EXPIRED");
    }

    @Test
    void invoiceRetainsPriceAndContactWhenConfigurationChanges() throws Exception {
        completeBillingContact();
        var invoice = create(UUID.randomUUID().toString());
        var old = settings.getMonthlyXof();
        settings.setMonthlyXof("9999");
        try {
            mvc.perform(
                            put(subscriptionPath() + "/billing-contact")
                                    .header("Authorization", "Bearer " + ownerToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"name\":\"Changed\",\"phoneE164\":\"+221771111111\"}"))
                    .andExpect(status().isOk());
            var attempt = pay(invoice);
            var instruction = provider.instructions.get(attempt.id());
            assertThat(instruction.amount()).isEqualByComparingTo("1234");
            assertThat(instruction.checkout().phone()).isEqualTo("+221771234567");
            receive(provider.success(attempt.id(), "TX-" + UUID.randomUUID()));
            awaitPaid(invoice);
            assertThat(subscriptions.history(organizationId).getFirst().amount())
                    .isEqualByComparingTo("1234");
        } finally {
            settings.setMonthlyXof(old);
        }
    }

    @Test
    void invalidCommandsCannotCreateFinancialAuthorityOrCrossOrganizationAccess() throws Exception {
        completeBillingContact();
        mvc.perform(
                        post(subscriptionPath() + "/invoices")
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post(subscriptionPath() + "/invoices")
                                .header("Authorization", "Bearer " + ownerToken)
                                .header("Idempotency-Key", "short"))
                .andExpect(status().isBadRequest());
        var invoice = create(UUID.randomUUID().toString());
        mvc.perform(
                        post(subscriptionPath() + "/invoices/" + invoice.id() + "/payment")
                                .header("Authorization", "Bearer " + ownerToken)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"provider\":\"TEST_BILLING\",\"amount\":1}"))
                .andExpect(status().isBadRequest());
        UUID other = createOrganization(clock.instant());
        mvc.perform(
                        get("/api/organizations/"
                                        + other
                                        + "/subscription/invoices/"
                                        + invoice.id())
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        assertThat(provider.calls.get()).isZero();
        assertThat(subscriptions.history(organizationId)).isEmpty();
        mvc.perform(
                        get("/api/organizations/" + organizationId + "/payment-requests")
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        jdbc.update(
                "update organization_members set role='MANAGER' where organization_id=?",
                organizationId);
        mvc.perform(
                        get("/api/organizations/" + organizationId + "/payment-requests")
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        jdbc.update(
                "update organization_members set role='OPERATOR' where organization_id=?",
                organizationId);
        mvc.perform(
                        get("/api/organizations/" + organizationId + "/payment-requests")
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotResolveSubscriptionReviewAndViewerListHidesIt() throws Exception {
        completeBillingContact();
        var invoice = create(UUID.randomUUID().toString());
        var attempt = pay(invoice);
        receive(provider.success(attempt.id(), "TX-" + UUID.randomUUID()));
        awaitPaid(invoice);
        receive(provider.success(attempt.id(), "TX-" + UUID.randomUUID()));
        UUID review =
                jdbc.queryForObject(
                        "select id from payment_reviews where organization_id=?",
                        UUID.class,
                        organizationId);
        jdbc.update(
                "update organization_members set role='MANAGER' where organization_id=?",
                organizationId);
        mvc.perform(
                        post("/api/organizations/"
                                        + organizationId
                                        + "/payment-reviews/"
                                        + review
                                        + "/resolve-refunded")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"externalRefundReference\":\"EXT-TEST\",\"resolutionNote\":\"Verified receipt\"}"))
                .andExpect(status().isForbidden());
        jdbc.update(
                "update organization_members set role='VIEWER' where organization_id=?",
                organizationId);
        mvc.perform(
                        get("/api/organizations/" + organizationId + "/payment-reviews")
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(
                        get("/api/organizations/" + organizationId + "/payment-requests")
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.openReviews").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(
                        jdbc.queryForObject(
                                "select status from payment_reviews where id=?",
                                String.class,
                                review))
                .isEqualTo("OPEN");
    }

    @Test
    void historicalOrderEventAndPersistedProviderEvidenceRemainReadable() {
        UUID id = UUID.randomUUID();
        String legacy =
                """
            {"eventId":"%s","occurredAt":"2026-10-01T00:00:00Z","organizationId":"%s","orderId":"%s","paymentRequestId":"%s","paymentId":"%s","purpose":"INITIAL_ORDER","amount":1000,"currency":"XOF"}
            """
                        .formatted(id, id, id, id, id);
        var event = json.readValue(legacy, PaymentRequestSettled.class);
        assertThat(event.orderId()).isEqualTo(id);
        assertThat(event.purpose()).isEqualTo(PaymentPurpose.INITIAL_ORDER);
        assertThat(json.readTree(json.writeValueAsString(event)).size()).isEqualTo(9);
        var observation =
                json.readValue(
                        """
            {"provider":"LEGACY","merchantReference":"GP-old","providerTransactionId":"TX-old","paymentRequestId":"%s","orderId":"%s","purpose":"INITIAL_ORDER","amount":1000,"currency":"XOF","status":"SUCCEEDED","occurredAt":"2026-10-01T00:00:00Z"}
            """
                                .formatted(id, id),
                        ProviderObservation.class);
        assertThat(observation.target()).isEqualTo(PaymentTarget.order(id));
        assertThat(observation.orderId()).isEqualTo(id);
    }

    @Autowired SubscriptionSettings settings;

    private SubscriptionInvoiceView create(String key) throws Exception {
        return json.readValue(
                mvc.perform(
                                post(subscriptionPath() + "/invoices")
                                        .header("Authorization", "Bearer " + ownerToken)
                                        .header("Idempotency-Key", key))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                SubscriptionInvoiceView.class);
    }

    private PaymentAttemptView pay(SubscriptionInvoiceView invoice) throws Exception {
        return json.readValue(
                mvc.perform(
                                post(subscriptionPath() + "/invoices/" + invoice.id() + "/payment")
                                        .header("Authorization", "Bearer " + ownerToken)
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"provider\":\"TEST_BILLING\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                PaymentAttemptView.class);
    }

    private UUID receive(ProviderObservation o) {
        return webhooks.receive(
                provider.name(),
                json.writeValueAsBytes(o),
                Map.of("X-Test-Secret", provider.secret));
    }

    private void awaitPaid(SubscriptionInvoiceView invoice) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select status from subscription_invoices where id=?",
                                                        String.class,
                                                        invoice.id()))
                                        .isEqualTo("PAID"));
    }

    private void expireTrial() {
        provisioning.ensure(organizationId);
        jdbc.update(
                "update organization_subscriptions set trial_started_at=?,trial_ends_at=? where organization_id=?",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(90))),
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(60))),
                organizationId);
    }

    private void completeBillingContact() throws Exception {
        mvc.perform(
                        put(subscriptionPath() + "/billing-contact")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(contactInput())))
                .andExpect(status().isOk());
    }

    private Object contactInput() {
        return java.util.Map.of(
                "name",
                "Propriétaire Boutique",
                "phoneE164",
                "+221771234567",
                "email",
                "owner@example.test");
    }

    private UUID createOrganization(Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into organizations(id,name,status,created_at,updated_at) values (?,?,'ACTIVE',?,?)",
                id,
                "Boutique de test " + id,
                java.sql.Timestamp.from(createdAt),
                java.sql.Timestamp.from(createdAt));
        jdbc.update(
                """
                insert into organization_members(id,organization_id,user_account_id,role,status,joined_at)
                values (?,?,?,'OWNER','ACTIVE',?)
                """,
                UUID.randomUUID(),
                id,
                ownerId,
                java.sql.Timestamp.from(createdAt));
        return id;
    }

    private UUID account(Instant now) {
        UUID id = UUID.randomUUID();
        long digits = Math.floorMod(id.getLeastSignificantBits(), 1_000_000_000L);
        jdbc.update(
                """
                insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at)
                values (?, ?, ?, 'ACTIVE', ?, ?)
                """,
                id,
                "+221" + String.format("%09d", digits),
                passwords.encode("Subscription fixture password 2026!"),
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now));
        return id;
    }

    private String token(UUID userId) {
        var now = Instant.now();
        var claims =
                JwtClaimsSet.builder()
                        .issuer("https://groupia.test")
                        .audience(List.of("groupia-api-test"))
                        .subject(userId.toString())
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(600))
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private String subscriptionPath() {
        return "/api/organizations/" + organizationId + "/subscription";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
        }

        @Bean
        DynamicPropertyRegistrar subscriptionTestProperties() {
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
        @Bean
        @Primary
        BillingClock billingClock() {
            return new BillingClock();
        }

        @Bean
        ChargeProvider chargeProvider(ObjectMapper json, BillingClock clock) {
            return new ChargeProvider(json, clock);
        }
    }

    static class BillingClock extends Clock {
        private volatile Instant at =
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        void reset() {
            at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        }

        void at(Instant value) {
            at = value;
        }

        void advance(Duration duration) {
            at = at.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return at;
        }
    }

    static class ChargeProvider implements PaymentProvider {
        final ObjectMapper json;
        final BillingClock clock;
        final String secret = UUID.randomUUID().toString();
        final AtomicInteger calls = new AtomicInteger();
        final Map<UUID, ProviderInstruction> instructions =
                new java.util.concurrent.ConcurrentHashMap<>();

        ChargeProvider(ObjectMapper json, BillingClock clock) {
            this.json = json;
            this.clock = clock;
        }

        void reset() {
            instructions.clear();
            calls.set(0);
        }

        public String name() {
            return "TEST_BILLING";
        }

        public String accountReference() {
            return "sandbox";
        }

        public WebhookSuccessEvidence webhookSuccessEvidence() {
            return WebhookSuccessEvidence.AUTHENTICATED_WEBHOOK;
        }

        public ProviderObservation initiate(ProviderInstruction i) {
            assertThat(
                            org.springframework.transaction.support
                                    .TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            calls.incrementAndGet();
            instructions.put(i.paymentId(), i);
            return new ProviderObservation(
                    name(),
                    accountReference(),
                    i.merchantReference(),
                    null,
                    i.paymentRequestId(),
                    i.orderId(),
                    i.purpose(),
                    i.amount(),
                    i.currency(),
                    PaymentStatus.PENDING,
                    clock.instant(),
                    new ProviderMetadata(
                            i.checkout().businessReference(),
                            "https://pay.test.bictorys.com/test",
                            "CH-" + i.paymentId(),
                            null),
                    i.target());
        }

        public ProviderObservation verify(ProviderInstruction i) {
            throw new IllegalStateException("Authenticated webhook needs no status call");
        }

        public boolean verifyAuthenticity(byte[] bytes, Map<String, String> headers) {
            return secret.equals(headers.get("X-Test-Secret"));
        }

        public NormalizedWebhook parse(byte[] bytes) {
            return new NormalizedWebhook(null, json.readValue(bytes, ProviderObservation.class));
        }

        ProviderObservation success(UUID payment, String transaction) {
            var i = instructions.get(payment);
            return new ProviderObservation(
                    name(),
                    accountReference(),
                    i.merchantReference(),
                    transaction,
                    null,
                    null,
                    null,
                    i.amount(),
                    i.currency(),
                    PaymentStatus.SUCCEEDED,
                    clock.instant(),
                    new ProviderMetadata(i.checkout().businessReference(), null, null, null));
        }
    }
}
