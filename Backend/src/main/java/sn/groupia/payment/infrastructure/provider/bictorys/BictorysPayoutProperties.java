package sn.groupia.payment.infrastructure.provider.bictorys;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("groupia.payment.bictorys.payouts")
public record BictorysPayoutProperties(boolean enabled,String privateKey,String merchantSecretCode,Duration readTimeout) {
    public BictorysPayoutProperties {
        if(readTimeout==null)readTimeout=Duration.ofSeconds(30);
        if(enabled && (privateKey==null||!privateKey.matches("test_secret-[A-Za-z0-9._-]+")
            ||merchantSecretCode==null||merchantSecretCode.isBlank()||merchantSecretCode.length()>128
            ||merchantSecretCode.chars().anyMatch(Character::isISOControl)
            ||readTimeout.isNegative()||readTimeout.isZero()||readTimeout.compareTo(Duration.ofSeconds(60))>0))
            throw new IllegalArgumentException("Valid sandbox payout credentials and timeout required");
    }
    @Override public String toString(){return "BictorysPayoutProperties[enabled="+enabled+", credentials=redacted]";}
}

