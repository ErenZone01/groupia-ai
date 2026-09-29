package sn.groupia.customeraccess.infrastructure;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("groupia.customer-access")
public record AccessSettings(@DefaultValue("false") boolean enabled,
    @DefaultValue("") String magicKeyBase64,@DefaultValue("") String otpPepperBase64,
    @DefaultValue("true") boolean secureCookie,@DefaultValue("http://localhost:4200") String publicBaseUrl,
    @DefaultValue("604800") int sessionSeconds,@DefaultValue("300") int otpSeconds,
    @DefaultValue("60") int resendSeconds,@DefaultValue("5") int maxOtpAttempts,
    @DefaultValue("5") int phoneRequestsPerHour,@DefaultValue("30") int ipRequestsPerHour,
    @DefaultValue("100") int ipVerificationsPerQuarterHour,@DefaultValue("90") int archiveDays) {
    @Override public String toString(){return "AccessSettings[redacted]";}
}
