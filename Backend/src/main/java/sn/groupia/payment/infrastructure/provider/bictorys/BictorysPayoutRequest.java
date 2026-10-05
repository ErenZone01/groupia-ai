package sn.groupia.payment.infrastructure.provider.bictorys;
import java.math.BigDecimal;
record BictorysPayoutRequest(BigDecimal amount,String currency,String country,Customer customerObject,
    String transactionType,String paymentReason,String merchantReference,Merchant merchant) {
    record Customer(String name,String phone,String country,String locale){@Override public String toString(){return "PayoutCustomer[redacted]";}}
    record Merchant(String secretCode){@Override public String toString(){return "PayoutMerchant[redacted]";}}
    @Override public String toString(){return "BictorysPayoutRequest[redacted]";}
}
