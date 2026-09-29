package sn.groupia.notification.infrastructure.whatsapp;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import sn.groupia.notification.api.NotificationType;

@ConfigurationProperties("groupia.notification.whatsapp")
public record WhatsAppProperties(
        @DefaultValue("false") boolean enabled,
        String graphApiVersion, String phoneNumberId, String accessToken,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("8s") Duration readTimeout, Templates templates) {

    public enum Source { ORDER_NUMBER, MAGIC_URL }
    public record UtilityTemplate(String name, String language, List<Source> headerParameters, List<Source> bodyParameters,
                                  Integer urlButtonIndex, String urlPrefix) {
        public UtilityTemplate {
            headerParameters = headerParameters == null ? List.of() : List.copyOf(headerParameters);
            bodyParameters = bodyParameters == null ? List.of() : List.copyOf(bodyParameters);
        }
    }
    public record OtpTemplate(String name, String language, Integer codeExpirationMinutes) {}
    public record Templates(UtilityTemplate orderConfirmed, UtilityTemplate orderTrackingLink,
                            OtpTemplate otp, Map<NotificationType, UtilityTemplate> additional) {
        public Templates { additional = additional == null ? Map.of() : Map.copyOf(additional); }
        UtilityTemplate forType(NotificationType type) {
            return switch (type) {
                case ORDER_CONFIRMED -> orderConfirmed;
                case ORDER_TRACKING_LINK -> orderTrackingLink;
                default -> additional.get(type);
            };
        }
    }
    void validate(int otpSeconds, boolean localProfile) {
        require(!localProfile, "LOCAL_PROFILE_CONFLICT");
        require(graphApiVersion != null && graphApiVersion.matches("v[0-9]{1,3}\\.[0-9]{1,2}"), "GRAPH_API_VERSION");
        require(phoneNumberId != null && phoneNumberId.matches("[0-9]{1,40}"), "PHONE_NUMBER_ID");
        require(accessToken != null && !accessToken.isBlank()
                && accessToken.chars().allMatch(c -> c > 32 && c < 127), "ACCESS_TOKEN");
        require(connectTimeout != null && connectTimeout.toMillis() >= 1 && connectTimeout.toSeconds() <= 10, "CONNECT_TIMEOUT");
        require(readTimeout != null && readTimeout.toMillis() >= 1 && readTimeout.toSeconds() <= 30, "READ_TIMEOUT");
        require(templates != null, "TEMPLATES");
        validateUtility(templates.orderConfirmed());
        validateUtility(templates.orderTrackingLink());
        templates.additional().forEach((type, template) -> {
            require(type != NotificationType.ORDER_CONFIRMED && type != NotificationType.ORDER_TRACKING_LINK, "ADDITIONAL_TEMPLATE");
            validateUtility(template);
        });
        var otp = templates.otp();
        require(otp != null, "OTP_TEMPLATE");
        validateIdentity(otp.name(), otp.language());
        require(otp.codeExpirationMinutes() != null && otp.codeExpirationMinutes() >= 1
                && otp.codeExpirationMinutes() <= 15 && otp.codeExpirationMinutes() * 60 == otpSeconds, "OTP_EXPIRATION");
    }
    private static void validateUtility(UtilityTemplate template) {
        require(template != null, "UTILITY_TEMPLATE");
        validateIdentity(template.name(), template.language());
        require(template.bodyParameters().contains(Source.MAGIC_URL) || template.urlButtonIndex() != null, "UTILITY_LINK_MAPPING");
        require(template.headerParameters().size() <= 1, "HEADER_PARAMETERS");
        require(template.bodyParameters().size() <= 10, "BODY_PARAMETERS");
        if (template.urlButtonIndex() != null) {
            require(template.urlButtonIndex() >= 0 && template.urlButtonIndex() <= 9, "URL_BUTTON_INDEX");
            require(template.urlPrefix() != null, "URL_PREFIX");
            try {
                var uri = URI.create(template.urlPrefix());
                require("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                        && uri.getQuery() == null && uri.getFragment() == null && template.urlPrefix().endsWith("/s/"), "URL_PREFIX");
            } catch (IllegalArgumentException e) { throw invalid("URL_PREFIX"); }
        } else {
            require(template.urlPrefix() == null || template.urlPrefix().isBlank(), "URL_BUTTON_INDEX");
        }
    }
    private static void validateIdentity(String name, String language) {
        require(name != null && name.matches("[a-z0-9_]{1,512}"), "TEMPLATE_NAME");
        require(language != null && language.matches("[a-z]{2,3}(_[A-Z]{2})?"), "TEMPLATE_LANGUAGE");
    }
    private static void require(boolean valid, String field) { if (!valid) throw invalid(field); }
    private static IllegalStateException invalid(String field) {
        return new IllegalStateException("WHATSAPP_CONFIGURATION_INVALID_" + field);
    }
    @Override public String toString() { return "WhatsAppProperties[redacted]"; }
}
