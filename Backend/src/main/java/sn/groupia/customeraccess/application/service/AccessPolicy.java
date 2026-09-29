package sn.groupia.customeraccess.application.service;
import java.net.URI;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import sn.groupia.customeraccess.infrastructure.AccessSettings;
import sn.groupia.shared.api.ApiException;
@Component
public class AccessPolicy {
    public final AccessSettings settings;
    public AccessPolicy(AccessSettings settings,Environment environment) {
        this.settings=settings;
        if(settings.enabled()) {
            if(!settings.secureCookie() && !environment.acceptsProfiles(Profiles.of("customer-access-local & !prod & !production")))
                throw new IllegalStateException("Insecure customer cookies require the explicit local profile.");
            URI url=URI.create(settings.publicBaseUrl());
            if(url.getHost()==null || url.getRawQuery()!=null || url.getRawFragment()!=null || url.getUserInfo()!=null
                || (!url.getScheme().equals("https") && settings.secureCookie()))throw new IllegalStateException("A secure public origin is required.");
            if(settings.sessionSeconds()<60 || settings.sessionSeconds()>2592000 || settings.otpSeconds()<30 || settings.otpSeconds()>900
                || settings.resendSeconds()<1 || settings.maxOtpAttempts()<1 || settings.maxOtpAttempts()>10
                || settings.phoneRequestsPerHour()<1 || settings.ipRequestsPerHour()<1 || settings.ipVerificationsPerQuarterHour()<1
                || settings.archiveDays()<1)throw new IllegalStateException("Invalid customer access limits.");
        }
    }
    public void requireEnabled(){if(!settings.enabled())throw new ApiException(503,"CUSTOMER_ACCESS_UNAVAILABLE","La récupération n’est pas encore disponible.");}
    public String cookieName(){return settings.secureCookie()?"__Host-groupia_customer":"groupia_customer";}
}
