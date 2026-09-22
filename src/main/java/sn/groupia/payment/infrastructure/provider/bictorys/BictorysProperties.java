package sn.groupia.payment.infrastructure.provider.bictorys;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("groupia.payment.bictorys")
public record BictorysProperties(boolean enabled,URI apiUrl,String apiKey,String webhookSecret,
        Duration connectTimeout,Duration readTimeout) {
    public BictorysProperties {
        if(apiUrl==null){apiUrl=URI.create("https://api.test.bictorys.com");}
        if(connectTimeout==null){connectTimeout=Duration.ofSeconds(3);}
        if(readTimeout==null){readTimeout=Duration.ofSeconds(10);}
        if(enabled) {
            if(!"https".equals(apiUrl.getScheme()) || !"api.test.bictorys.com".equals(apiUrl.getHost())
                    || apiUrl.getUserInfo()!=null || apiUrl.getQuery()!=null || apiUrl.getFragment()!=null
                    || (apiUrl.getPort()!=-1 && apiUrl.getPort()!=443)
                    || !(apiUrl.getPath().isEmpty() || apiUrl.getPath().equals("/"))) {
                throw new IllegalArgumentException("Bictorys requires its HTTPS sandbox API");
            }
            if(apiKey==null || !apiKey.startsWith("test_public-") || apiKey.isBlank()
                    || webhookSecret==null || webhookSecret.isBlank()) {
                throw new IllegalArgumentException("Bictorys sandbox API key and webhook secret are required");
            }
            if(connectTimeout.isNegative() || connectTimeout.isZero() || connectTimeout.compareTo(Duration.ofSeconds(30))>0
                    || readTimeout.isNegative() || readTimeout.isZero() || readTimeout.compareTo(Duration.ofSeconds(60))>0) {
                throw new IllegalArgumentException("Invalid Bictorys timeouts");
            }
        }
    }
    @Override public String toString(){return "BictorysProperties[enabled="+enabled+", credentials=redacted]";}
}
