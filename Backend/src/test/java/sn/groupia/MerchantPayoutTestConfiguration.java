package sn.groupia;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import sn.groupia.settlement.domain.model.MerchantPayout;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;

@TestConfiguration(proxyBeanMethods = false)
class MerchantPayoutTestConfiguration {
    @Bean
    @Primary
    FakeMerchantProvider merchantProvider() {
        return new FakeMerchantProvider();
    }

    static class FakeMerchantProvider implements MerchantPayoutProvider {
        volatile boolean available = true, timeout;
        volatile String sourceProvider = "SIMULATED", sourceAccount = "test-merchant";
        volatile String status = "SUCCEEDED", defect, transactionOverride;
        final AtomicInteger sends = new AtomicInteger();
        volatile MerchantPayout sent;
        volatile CountDownLatch entered, release;

        void reset() {
            sourceProvider = "SIMULATED";
            sourceAccount = "test-merchant";
            available = true;
            timeout = false;
            status = "SUCCEEDED";
            defect = null;
            transactionOverride = null;
            sends.set(0);
            sent = null;
            entered = null;
            release = null;
        }

        public boolean enabled() {
            return available;
        }

        public String name() {
            return "SIMULATED_MERCHANT";
        }

        public String account() {
            return "fixture";
        }

        public String fundingProvider() {
            return sourceProvider;
        }

        public String fundingAccount() {
            return sourceAccount;
        }

        public String initiate(MerchantPayout p) {
            outside();
            sends.incrementAndGet();
            sent = p;
            if (entered != null) entered.countDown();
            if (release != null)
                try {
                    if (!release.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("Fixture gate timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            if (timeout) throw new IllegalStateException("Fixture timeout");
            return transactionOverride == null ? "MP-" + p.id() : transactionOverride;
        }

        public Proof verify(MerchantPayout p) {
            outside();
            if (timeout) throw new IllegalStateException("Fixture timeout");
            return new Proof(
                    !"auth".equals(defect),
                    "provider".equals(defect) ? "WRONG" : name(),
                    "account".equals(defect) ? "WRONG" : account(),
                    "reference".equals(defect) ? "WRONG" : p.businessReference(),
                    "transaction".equals(defect)
                            ? null
                            : transactionOverride == null ? "MP-" + p.id() : transactionOverride,
                    "amount".equals(defect) ? p.amount().add(java.math.BigDecimal.ONE) : p.amount(),
                    "currency".equals(defect) ? "EUR" : "XOF",
                    "method".equals(defect) ? "OTHER" : p.method(),
                    "phone".equals(defect) ? "+221770000000" : p.phoneE164(),
                    status,
                    Instant.now());
        }

        private void outside() {
            if (TransactionSynchronizationManager.isActualTransactionActive())
                throw new AssertionError("Network in transaction");
        }
    }
}
