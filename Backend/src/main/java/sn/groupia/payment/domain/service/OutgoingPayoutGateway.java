package sn.groupia.payment.domain.service;

import java.util.Map;
import sn.groupia.payment.api.OutgoingPayoutOperations.*;

public interface OutgoingPayoutGateway {
    boolean enabled();

    String initiate(Registration operation, Recipient recipient);

    Evidence verify(Registration operation, String transactionId);

    Evidence parse(Registration operation, byte[] payload);

    boolean authentic(byte[] payload, Map<String, String> headers);

    /** Routing hints never constitute financial proof. */
    record Identity(String reference, String transactionId, boolean outgoingHint) {}

    Identity identify(byte[] payload);
}
