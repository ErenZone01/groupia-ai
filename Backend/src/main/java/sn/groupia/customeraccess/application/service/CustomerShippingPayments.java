package sn.groupia.customeraccess.application.service;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.ordering.api.CustomerOrderAccess;
import sn.groupia.ordering.api.dto.CustomerPaymentAttempt;
@Service
public class CustomerShippingPayments {
    private final CustomerAccess access;private final CustomerOrderAccess orders;
    CustomerShippingPayments(CustomerAccess access,CustomerOrderAccess orders){this.access=access;this.orders=orders;}
    @Transactional(propagation=Propagation.NEVER)
    public CustomerPaymentAttempt initiate(String token,String number,String provider,String key){
        var identity=access.paymentIdentity(token,number);
        return orders.initiateShippingBalance(identity.organizationId(),identity.id(),provider,key);
    }
}
