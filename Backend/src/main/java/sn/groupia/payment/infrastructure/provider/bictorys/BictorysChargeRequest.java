package sn.groupia.payment.infrastructure.provider.bictorys;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonInclude;
@JsonInclude(JsonInclude.Include.NON_NULL)
record BictorysChargeRequest(BigDecimal amount,String currency,String country,String paymentReference,
        String merchantReference,Customer customerObject) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Customer(String name,String phone,String email,String country) {
        @Override public String toString(){return "Customer[redacted]";}
    }
    @Override public String toString(){return "BictorysChargeRequest[redacted]";}
}
