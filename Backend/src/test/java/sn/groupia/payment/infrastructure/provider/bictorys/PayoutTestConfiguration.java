package sn.groupia.payment.infrastructure.provider.bictorys;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import sn.groupia.payment.domain.model.Refund;
import sn.groupia.payment.domain.service.*;
@TestConfiguration(proxyBeanMethods=false)
public class PayoutTestConfiguration {
    @Bean @Primary public Harness refundHarness(BictorysHttpStub stub){
        var base=new BictorysProperties(true,null,"test_public-fixture","fixture-webhook-secret",Duration.ofSeconds(1),Duration.ofSeconds(2));
        var p=new BictorysPayoutProperties(true,"test_secret-fixture","fixture-merchant-code",Duration.ofSeconds(1));
        var http=BictorysRefundProvider.client(base,p).mutate().baseUrl(stub.url()).build();
        return new Harness(new BictorysRefundProvider(p,"fixture-webhook-secret",http));
    }
    public static class Harness implements RefundProvider {
        private final RefundProvider adapter;public boolean available=true;
        Harness(RefundProvider a){adapter=a;}
        public boolean enabled(){return available;}
        public String initiate(Refund r){outside();return adapter.initiate(r);}
        public RefundObservation verify(Refund r){outside();return adapter.verify(r);}
        public boolean authentic(Map<String,String> h){return adapter.authentic(h);}
        public boolean recognizes(byte[] p){return adapter.recognizes(p);}
        public RefundObservation parse(byte[] p){return adapter.parse(p);}
        private void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new AssertionError("Payout network inside transaction");}
    }
}
