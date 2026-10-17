package sn.groupia.payment.infrastructure.provider.bictorys;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import sn.groupia.payment.domain.model.Refund;
import sn.groupia.payment.domain.service.*;

@TestConfiguration(proxyBeanMethods = false)
public class PayoutTestConfiguration {
    @Bean
    @Primary
    public Harness refundHarness(BictorysHttpStub stub) {
        var base =
                new BictorysProperties(
                        true,
                        null,
                        "test_public-fixture",
                        "fixture-webhook-secret",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2));
        var p =
                new BictorysPayoutProperties(
                        true,
                        "test_secret-fixture",
                        "fixture-merchant-code",
                        Duration.ofSeconds(1));
        var http = BictorysRefundProvider.client(base, p).mutate().baseUrl(stub.url()).build();
        return new Harness(new BictorysRefundProvider(p, "fixture-webhook-secret", http));
    }

    public static class Harness implements RefundProvider, OutgoingPayoutGateway {
        private final BictorysRefundProvider adapter;
        public boolean available = true;

        Harness(BictorysRefundProvider a) {
            adapter = a;
        }

        public boolean enabled() {
            return available;
        }

        public String initiate(Refund r) {
            outside();
            return adapter.initiate(r);
        }

        public RefundObservation verify(Refund r) {
            outside();
            return adapter.verify(r);
        }

        public boolean authentic(Map<String, String> h) {
            return adapter.authentic(h);
        }

        public boolean recognizes(byte[] p) {
            return adapter.recognizes(p);
        }

        public RefundObservation parse(byte[] p) {
            return adapter.parse(p);
        }

        public boolean authentic(byte[] p, Map<String, String> h) {
            return adapter.authentic(p, h);
        }

        public Identity identify(byte[] p) {
            return adapter.identify(p);
        }

        public String initiate(
                sn.groupia.payment.api.OutgoingPayoutOperations.Registration r,
                sn.groupia.payment.api.OutgoingPayoutOperations.Recipient c) {
            outside();
            return adapter.initiate(r, c);
        }

        public sn.groupia.payment.api.OutgoingPayoutOperations.Evidence parse(
                sn.groupia.payment.api.OutgoingPayoutOperations.Registration r, byte[] p) {
            return adapter.parse(r, p);
        }

        public sn.groupia.payment.api.OutgoingPayoutOperations.Evidence verify(
                sn.groupia.payment.api.OutgoingPayoutOperations.Registration r, String id) {
            outside();
            return adapter.verify(r, id);
        }

        private void outside() {
            if (TransactionSynchronizationManager.isActualTransactionActive())
                throw new AssertionError("Payout network inside transaction");
        }
    }
}
