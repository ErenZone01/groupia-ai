package sn.groupia.payment.infrastructure.provider.bictorys;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import sn.groupia.payment.domain.service.*;
@TestConfiguration(proxyBeanMethods=false)
public class BictorysTestServerConfiguration {
    @Bean(destroyMethod="close") BictorysHttpStub bictorysStub() throws Exception {return new BictorysHttpStub();}
    @Bean PaymentProvider bictorysTestProvider(BictorysHttpStub stub) {
        var properties=new BictorysProperties(true,null,"test_public-fixture","fixture-webhook-secret",
                Duration.ofSeconds(1),Duration.ofSeconds(2));
        var adapter=new BictorysPaymentProvider(new BictorysClient(BictorysClient.builder(properties).baseUrl(stub.url()).build()),properties);
        return new PaymentProvider() {
            public String name(){return adapter.name();}
            public String accountReference(){return adapter.accountReference();}
            public ProviderObservation initiate(ProviderInstruction i){outsideTransaction();return adapter.initiate(i);}
            public ProviderObservation verify(ProviderInstruction i){outsideTransaction();return adapter.verify(i);}
            public boolean verifyAuthenticity(byte[] payload,Map<String,String> headers){outsideTransaction();return adapter.verifyAuthenticity(payload,headers);}
            public NormalizedWebhook parse(byte[] payload){outsideTransaction();return adapter.parse(payload);}
            private void outsideTransaction(){
                if(TransactionSynchronizationManager.isActualTransactionActive()){throw new AssertionError("Bictorys called inside database transaction");}
            }
        };
    }
}
