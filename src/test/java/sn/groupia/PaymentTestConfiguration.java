package sn.groupia;
import java.math.BigDecimal;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import sn.groupia.payment.api.*;
import sn.groupia.payment.domain.service.*;

@TestConfiguration(proxyBeanMethods=false)
class PaymentTestConfiguration {
    @Bean @Primary MutablePaymentClock paymentClock() { return new MutablePaymentClock(); }
    @Bean SimulatedProvider simulatedProvider(ObjectMapper json,MutablePaymentClock clock) { return new SimulatedProvider(json,clock); }

    static class MutablePaymentClock extends Clock {
        private volatile Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        void reset() { now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
        void advance(Duration duration) { now=now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    /** Exists only under src/test. It is not a production/dev sandbox endpoint. */
    static class SimulatedProvider implements PaymentProvider {
        final Map<String,ProviderObservation> observations=new ConcurrentHashMap<>();
        final AtomicInteger initiationCalls=new AtomicInteger();
        final AtomicInteger verificationCalls=new AtomicInteger();
        volatile PaymentStatus initialStatus=PaymentStatus.PENDING;
        volatile boolean initiationTimeout;
        volatile boolean verificationTimeout;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        private final byte[] signingKey=new byte[32];
        private final ObjectMapper json;
        private final MutablePaymentClock clock;
        SimulatedProvider(ObjectMapper json,MutablePaymentClock clock) { this.json=json; this.clock=clock; new SecureRandom().nextBytes(signingKey); }
        void reset() {
            observations.clear(); initiationCalls.set(0); verificationCalls.set(0); initialStatus=PaymentStatus.PENDING;
            initiationTimeout=false; verificationTimeout=false; entered=null; release=null;
        }
        @Override public String name() { return "SIMULATED"; }
        @Override public String accountReference() { return "test-merchant"; }
        @Override public ProviderObservation initiate(ProviderInstruction i) {
            assertOutsideTransaction(); initiationCalls.incrementAndGet();
            var o=new ProviderObservation(name(),accountReference(),i.merchantReference(),"TX-"+i.paymentId(),
                    i.paymentRequestId(),i.orderId(),i.purpose(),i.amount(),i.currency(),initialStatus,clock.instant());
            observations.put(i.merchantReference(),o);
            if(entered!=null){entered.countDown();}
            if(release!=null) { try { if(!release.await(10,TimeUnit.SECONDS)){throw new IllegalStateException("Timed out test gate");} }
                catch(InterruptedException ex){Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted test gate");} }
            if(initiationTimeout){throw new IllegalStateException("Simulated network timeout");}
            return o;
        }
        @Override public ProviderObservation verify(ProviderInstruction i) {
            assertOutsideTransaction(); verificationCalls.incrementAndGet();
            if(verificationTimeout){throw new IllegalStateException("Simulated verification timeout");}
            return observations.get(i.merchantReference());
        }
        @Override public boolean verifyAuthenticity(byte[] payload,Map<String,String> headers) {
            assertOutsideTransaction();
            String supplied=headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("X-Test-Signature"))
                    .map(Map.Entry::getValue).findFirst().orElse("");
            try { return MessageDigest.isEqual(HexFormat.of().parseHex(signature(payload)),HexFormat.of().parseHex(supplied)); }
            catch(IllegalArgumentException ex){return false;}
        }
        @Override public NormalizedWebhook parse(byte[] payload) { assertOutsideTransaction(); return json.readValue(payload,NormalizedWebhook.class); }
        String signature(byte[] payload) {
            try { var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(signingKey,"HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(payload)); }
            catch(GeneralSecurityException ex){throw new IllegalStateException(ex);}
        }
        ProviderObservation status(String merchant,PaymentStatus status) {
            var before=observations.get(merchant);
            var result=new ProviderObservation(before.provider(),before.providerAccountReference(),before.merchantReference(),
                    before.providerTransactionId(),before.paymentRequestId(),before.orderId(),before.purpose(),
                    before.amount(),before.currency(),status,clock.instant());
            observations.put(merchant,result); return result;
        }
        private void assertOutsideTransaction() {
            if(TransactionSynchronizationManager.isActualTransactionActive()) { throw new AssertionError("Provider called inside database transaction"); }
        }
    }
}
