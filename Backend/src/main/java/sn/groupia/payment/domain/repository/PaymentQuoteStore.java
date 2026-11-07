package sn.groupia.payment.domain.repository;

import java.util.Optional;
import java.util.UUID;
import sn.groupia.payment.domain.model.PaymentFeeQuote;

public interface PaymentQuoteStore {
    void save(PaymentFeeQuote quote);

    Optional<PaymentFeeQuote> find(UUID id);
}
