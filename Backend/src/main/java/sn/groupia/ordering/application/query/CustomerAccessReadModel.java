package sn.groupia.ordering.application.query;
import java.util.*;
import java.math.BigDecimal;
import java.time.Instant;
import sn.groupia.ordering.api.dto.CustomerOrderIdentity;
public interface CustomerAccessReadModel {
    CustomerOrderIdentity identity(UUID org,UUID id);
    CustomerOrderIdentity forPhone(String phone,String number);
    List<Row> list(String phone,int offset,int limit);
    long count(String phone);
    record Row(UUID campaign,String number,Instant placedAt,String status,String fulfillment,BigDecimal amount,String currency) {}
}
