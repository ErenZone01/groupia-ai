package sn.groupia.payment.domain.service;

import java.util.Map;
import sn.groupia.payment.domain.model.Refund;

public interface RefundProvider {
    boolean enabled();

    String initiate(Refund refund);

    RefundObservation verify(Refund refund);

    boolean recognizes(byte[] payload);

    boolean authentic(Map<String, String> headers);

    default boolean authentic(byte[] payload, Map<String, String> headers) {
        return authentic(headers);
    }

    RefundObservation parse(byte[] payload);
}
