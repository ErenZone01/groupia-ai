package sn.groupia.payment.domain.service;
import java.util.Map;
/** Implemented only by infrastructure adapters; no database transaction may surround these calls. */
public interface PaymentProvider {
    String name();
    /** Server-selected merchant account, never trusted from public input. */
    String accountReference();
    ProviderObservation initiate(ProviderInstruction instruction);
    ProviderObservation verify(ProviderInstruction instruction);
    boolean verifyAuthenticity(byte[] payload, Map<String, String> headers);
    NormalizedWebhook parse(byte[] payload);
}
