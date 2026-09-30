package sn.groupia.notification.infrastructure.whatsapp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import sn.groupia.notification.api.*;
import sn.groupia.notification.infrastructure.LocalDeliveryGateway;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class WhatsAppConfigurationTests {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(WhatsAppConfiguration.class)
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());
    }
    private String[] valid() {
        return new String[]{
            "groupia.notification.whatsapp.enabled=true",
            "groupia.notification.whatsapp.otp-enabled=true",
            "groupia.notification.whatsapp.links-enabled=true",
            "groupia.notification.whatsapp.templates.otp.mode=AUTHENTICATION",
            "groupia.notification.whatsapp.graph-api-version=v23.0",
            "groupia.notification.whatsapp.phone-number-id=123456789",
            "groupia.notification.whatsapp.access-token=configuration-test-only",
            "groupia.notification.whatsapp.templates.order-confirmed.name=confirmation_fixture",
            "groupia.notification.whatsapp.templates.order-confirmed.language=fr",
            "groupia.notification.whatsapp.templates.order-confirmed.header-parameters=ORDER_NUMBER",
            "groupia.notification.whatsapp.templates.order-confirmed.body-parameters=MAGIC_URL",
            "groupia.notification.whatsapp.templates.order-tracking-link.name=tracking_fixture",
            "groupia.notification.whatsapp.templates.order-tracking-link.language=fr",
            "groupia.notification.whatsapp.templates.order-tracking-link.body-parameters=MAGIC_URL",
            "groupia.notification.whatsapp.templates.otp.name=otp_fixture",
            "groupia.notification.whatsapp.templates.otp.language=fr",
            "groupia.notification.whatsapp.templates.otp.code-expiration-minutes=5"};
    }
    private String[] connection() {
        return new String[]{
            "groupia.notification.whatsapp.enabled=true",
            "groupia.notification.whatsapp.graph-api-version=v23.0",
            "groupia.notification.whatsapp.phone-number-id=123456789",
            "groupia.notification.whatsapp.access-token=configuration-test-only"};
    }
    private String[] utilityOtp() {
        return new String[]{
            "groupia.notification.whatsapp.otp-enabled=true",
            "groupia.notification.whatsapp.links-enabled=false",
            "groupia.notification.whatsapp.templates.otp.mode=UTILITY",
            "groupia.notification.whatsapp.templates.otp.name=utility_fixture",
            "groupia.notification.whatsapp.templates.otp.language=fr",
            "groupia.notification.whatsapp.templates.otp.body-parameters=OTP_CODE"};
    }
    @Test void utilityOtpOnlyStartsWithoutLinkTemplatesOrMetaExpiry() {
        runner().withPropertyValues(connection()).withPropertyValues(utilityOtp())
                .withPropertyValues("groupia.customer-access.otp-seconds=301")
                .run(c -> assertThat(c).hasNotFailed().hasSingleBean(DeliveryGateway.class));
    }
    @Test void disabledCapabilitiesNeedNoTemplatesAndDefaultToFalse() {
        runner().withPropertyValues(connection()).run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(WhatsAppProperties.class).otpEnabled()).isFalse();
            assertThat(c.getBean(WhatsAppProperties.class).linksEnabled()).isFalse();
        });
    }
    @Test void enabledOtpRequiresItsTemplate() {
        runner().withPropertyValues(connection()).withPropertyValues("groupia.notification.whatsapp.otp-enabled=true")
                .run(c -> assertThat(c).hasFailed());
    }
    @Test void enabledLinksRequireBothLinkTemplatesEvenInOtpOnlyConfiguration() {
        runner().withPropertyValues(connection()).withPropertyValues(utilityOtp())
                .withPropertyValues("groupia.notification.whatsapp.links-enabled=true")
                .run(c -> assertThat(c.getStartupFailure()).hasStackTraceContaining("UTILITY_TEMPLATE"));
    }
    @Test void linksCanStartWithOtpDisabledAndWithoutOtpTemplate() {
        var links = java.util.Arrays.stream(valid()).filter(p -> !p.startsWith("groupia.notification.whatsapp.templates.otp.")
                && !p.startsWith("groupia.notification.whatsapp.otp-enabled=")).toArray(String[]::new);
        runner().withPropertyValues(links).run(c -> assertThat(c).hasNotFailed().hasSingleBean(DeliveryGateway.class));
    }
    @Test void utilityModeMustDeclareExactlyOneOtpCodeBodyParameter() {
        for (var mapping : new String[]{"", "OTP_CODE,OTP_CODE"}) {
            runner().withPropertyValues(connection()).withPropertyValues(utilityOtp())
                    .withPropertyValues("groupia.notification.whatsapp.templates.otp.body-parameters=" + mapping)
                    .run(c -> assertThat(c.getStartupFailure()).hasStackTraceContaining("OTP_BODY_PARAMETERS"));
        }
    }
    @Test void authenticationRejectsLeftoverUtilityMappingRatherThanIgnoringIt() {
        runner().withPropertyValues(valid()).withPropertyValues("groupia.notification.whatsapp.templates.otp.body-parameters=OTP_CODE")
                .run(c -> assertThat(c.getStartupFailure()).hasStackTraceContaining("OTP_BODY_PARAMETERS"));
    }
    @Test void blankModeDoesNotImplicitlySelectAuthenticationOrUtility() {
        runner().withPropertyValues(connection()).withPropertyValues(utilityOtp())
                .withPropertyValues("groupia.notification.whatsapp.templates.otp.mode=")
                .run(c -> assertThat(c.getStartupFailure()).hasStackTraceContaining("OTP_MODE"));
    }
    @Test void applicationDefaultsWithEmptyInactiveTemplatesBindInOtpOnlyMode() {
        runner().withInitializer(context -> {
            try {
                context.getEnvironment().getPropertySources().addLast(new org.springframework.core.io.support.ResourcePropertySource("classpath:application.properties"));
            } catch (java.io.IOException e) { throw new AssertionError(e); }
        }).withPropertyValues(connection()).withPropertyValues(utilityOtp())
                .run(c -> assertThat(c).hasNotFailed().hasSingleBean(DeliveryGateway.class));
    }
    @Test void explicitlyConfiguredTransportStarts() {
        runner().withPropertyValues(valid()).run(c -> assertThat(c).hasNotFailed().hasSingleBean(DeliveryGateway.class));
    }
    @Test void disabledNeedsNoSecretsOrTemplates() {
        runner().run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(DeliveryGateway.class));
    }
    @ParameterizedTest @ValueSource(strings = {
        "graph-api-version", "phone-number-id", "access-token", "templates.order-confirmed.name",
        "templates.order-confirmed.language", "templates.order-tracking-link.name",
        "templates.order-tracking-link.language", "templates.otp.name", "templates.otp.language",
        "templates.otp.mode", "templates.otp.code-expiration-minutes", "templates.order-confirmed.body-parameters"})
    void missingRequiredConfigurationFailsWithoutLeakingToken(String key) {
        runner().withPropertyValues(valid()).withPropertyValues("groupia.notification.whatsapp." + key + "=")
                .run(c -> {
                    assertThat(c).hasFailed();
                    assertThat(c.getStartupFailure()).hasStackTraceContaining("WHATSAPP_CONFIGURATION_INVALID");
                    assertThat(c.getStartupFailure()).hasStackTraceContaining("BeanCreationException");
                    assertThat(c.getStartupFailure().toString()).doesNotContain("configuration-test-only");
                });
    }
    @Test void localCannotEnableRealTransport() {
        runner().withPropertyValues(valid()).withInitializer(c -> c.getEnvironment().setActiveProfiles("customer-access-local"))
                .run(c -> assertThat(c.getStartupFailure()).hasStackTraceContaining("LOCAL_PROFILE_CONFLICT"));
    }
    @Test void approvedOtpExpiryMustMatchBackendSecondsExactly() {
        runner().withPropertyValues(valid()).withPropertyValues("groupia.customer-access.otp-seconds=301")
                .run(c -> assertThat(c.getStartupFailure()).hasStackTraceContaining("OTP_EXPIRATION"));
    }
    @Test void templateWithOnlyOrderNumberCannotOmitTheMagicLink() {
        runner().withPropertyValues(valid()).withPropertyValues("groupia.notification.whatsapp.templates.order-confirmed.body-parameters=ORDER_NUMBER")
                .run(c -> assertThat(c.getStartupFailure()).hasStackTraceContaining("UTILITY_LINK_MAPPING"));
    }
    @Test void neutralTemplateMayServeBothTypes() {
        runner().withPropertyValues(valid()).withPropertyValues("groupia.notification.whatsapp.templates.order-tracking-link.name=confirmation_fixture")
                .run(c -> assertThat(c).hasNotFailed());
    }
    @Test void defaultTransportRemainsExplicitlyUnavailable() {
        transports().run(c -> {
            assertThat(c).hasNotFailed().hasSingleBean(DeliveryGateway.class);
            assertThatThrownBy(() -> c.getBean(DeliveryGateway.class).sendOtp(java.util.UUID.randomUUID(), "+221771234567", "123456"))
                    .hasMessage("NOTIFICATION_PROVIDER_NOT_CONFIGURED");
            assertThat(c).doesNotHaveBean(LocalDeliveryGateway.class);
        });
    }
    @Test void localProfileSelectsOnlyLocalGatewayAndInbox() {
        transports().withInitializer(c -> c.getEnvironment().setActiveProfiles("customer-access-local")).run(c -> {
            assertThat(c).hasNotFailed().hasSingleBean(DeliveryGateway.class).hasSingleBean(LocalDeliveryGateway.class);
            assertThat(c).hasBean("localInboxController");
        });
    }
    @ParameterizedTest @ValueSource(strings={"prod","production"})
    void productionNeverExposesLocalGatewayOrInbox(String production) {
        transports().withInitializer(c -> c.getEnvironment().setActiveProfiles("customer-access-local",production)).run(c -> {
            assertThat(c).hasNotFailed().hasSingleBean(DeliveryGateway.class).doesNotHaveBean(LocalDeliveryGateway.class);
            assertThat(c).doesNotHaveBean("localInboxController");
        });
    }
    @Test void realConfigurationSelectsOnlyMeta() {
        transports().withPropertyValues(valid()).run(c -> {
            assertThat(c).hasNotFailed().hasSingleBean(DeliveryGateway.class).doesNotHaveBean(LocalDeliveryGateway.class);
            assertThat(c.getBean(DeliveryGateway.class)).isInstanceOf(MetaWhatsAppDeliveryGateway.class);
        });
    }
    private ApplicationContextRunner transports() {
        try {
            return runner().withUserConfiguration(LocalDeliveryGateway.class,
                Class.forName("sn.groupia.notification.infrastructure.UnavailableDeliveryGateway"),
                Class.forName("sn.groupia.notification.infrastructure.web.LocalInboxController"));
        } catch (ClassNotFoundException e) { throw new AssertionError(e); }
    }
    @Test void localInboxRemainsTransientAndIdempotent() {
        var local = new LocalDeliveryGateway(); var id = java.util.UUID.randomUUID();
        var link = new NotificationContentSource.LinkContent("+221771234567", "GRP-1", "https://groupia.test/s/test-only");
        local.sendLink(id, NotificationType.ORDER_TRACKING_LINK, link);
        local.sendLink(id, NotificationType.ORDER_TRACKING_LINK, link);
        local.sendOtp(java.util.UUID.randomUUID(), link.phone(), "123456");
        assertThat(local.messages()).hasSize(2);
        assertThat(local.messages().getFirst().type()).isEqualTo("ORDER_TRACKING_LINK");
        assertThat(local.messages().getFirst().content()).isEqualTo(link.url());
        assertThat(local.messages().toString()).doesNotContain(link.phone(), link.url(), "123456");
    }
}
