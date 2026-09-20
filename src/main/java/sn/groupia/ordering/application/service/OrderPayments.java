package sn.groupia.ordering.application.service;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.payment.api.PaymentOperations;
import sn.groupia.payment.api.dto.PaymentAttemptView;
@Service
public class OrderPayments {
    private final OrderPaymentGate gate; private final PaymentOperations payments;
    OrderPayments(OrderPaymentGate gate,PaymentOperations payments) { this.gate=gate; this.payments=payments; }
    @Transactional(propagation=Propagation.NEVER)
    public PaymentAttemptView initiate(String number,String token,String provider,String key) {
        var context=gate.authorize(number,token);
        return payments.initiateInitial(context.organizationId(),context.orderId(),provider,key);
    }
}
